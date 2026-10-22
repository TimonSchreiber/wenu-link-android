package org.WenuLink.adapters.aircraft

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.Constraints
import io.kotest.property.PropTestConfig
import io.kotest.property.PropertyContext
import io.kotest.property.Shrinker
import io.kotest.property.ShrinkingMode
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import org.WenuLink.commands.CommandResult

/*
 * Generators for the property catalog v1.0 (state-model/docs/eigenschaftskatalog.md).
 *
 * Every property quantifies over S, the states reachable from the state after
 * BootTransition and StandbyTransition (catalog, section 1). This file generates S as
 * sequences of events, applies them through the same guards the app uses and records
 * a trace for readable counterexamples. Telemetry inputs t in T are not generated here:
 * T has four elements, so properties iterate over Telemetry.all.
 *
 * The oracle (view, projection, target mapping) lives in Oracle.kt, which also provides the
 * MAVLink names used in traces.
 */

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * Clock reading at the start of every execution. The production [SystemClock] returns epoch
 * milliseconds. The value must stay well above 0: `armTimestamp == 0` is the sentinel for
 * "no arm requested" ([AircraftState.armRequested]), so an arm at clock time 0 would be
 * indistinguishable from no arm at all.
 */
private const val START_TIME_MS = 1_700_000_000_000L

/** Minimum period of the monitor loop, `minimumDelay` in `WenuLinkHandler.startMonitorJob`. */
const val SYNC_INTERVAL_MS = 200L

/**
 * Arm timeout in branch 5 of [AircraftStateMachine.sync]. Written out instead of read from
 * the model, so that a boundary mutant cannot move the generated values along with it.
 */
const val ARM_TIMEOUT_MS = 10_000L

/**
 * Number of monitor steps over which "for all m >= n" is checked (E-02, E-04). A test can only
 * check finitely many steps. Up to G-007 the arm timeout was the only time-dependent branch of
 * sync, and HORIZON was derived from it so that a departure caused by the timeout falls inside
 * the range. The corrected sync has no time-dependent branch; HORIZON is kept so that runs
 * before and after the corrections check the same range.
 */
const val HORIZON = (ARM_TIMEOUT_MS / SYNC_INTERVAL_MS).toInt() + 1

/** Length range of generated event sequences. */
private val SEQUENCE_LENGTH = 0..30

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

/** One element of T: the DJI telemetry pair the monitor loop passes to [AircraftStateMachine.sync]. */
data class Telemetry(val isArmed: Boolean, val isFlying: Boolean) {
    override fun toString(): String = "($isArmed, $isFlying)"

    companion object {
        /** All of T, in the row order of the target mapping (catalog, section 3). */
        val all: List<Telemetry> = listOf(
            Telemetry(isArmed = false, isFlying = false),
            Telemetry(isArmed = true, isFlying = false),
            Telemetry(isArmed = true, isFlying = true),
            Telemetry(isArmed = false, isFlying = true)
        )
    }
}

/**
 * Transitions that commands dispatch within the takeoff and landing cycle, modeled as
 * dispatch(τ) under canDispatch(τ) (catalog, section 1). Call sites in :app:
 *
 *  - ARM:     ArmCommand, RequestStartMission
 *  - STANDBY: DisarmCommand
 *  - TAKEOFF: TakeoffCommand, RequestTakeoff, RequestStartMission
 *  - FLYING:  TakeoffCommand, RequestMissionAction
 *  - LAND:    RequestLand
 *
 * Not included: BootTransition, InitialTransition and PowerOffTransition belong to power-up
 * and shutdown, and InitialTransition leads back to the state before boot that section 1
 * excludes. FlightTerminationTransition is an emergency state, also excluded by section 1.
 */
enum class Command(val transition: StateTransition) {
    ARM(ArmTransition),
    STANDBY(StandbyTransition),
    TAKEOFF(TakeoffTransition),
    FLYING(FlyingTransition),
    LAND(LandTransition)
}

/** An event that moves an execution within S, or let's time pass. */
sealed interface Event {
    /** Telemetry reconciliation by the monitor loop. */
    data class Sync(val telemetry: Telemetry) : Event {
        override fun toString(): String = "sync$telemetry"
    }

    /** A command, modeled as dispatch(τ) under canDispatch(τ). */
    data class Dispatch(val command: Command) : Event {
        override fun toString(): String = "dispatch($command)"
    }

    /** A mode change, modeled as updateFlightMode(m) under isModeAllowed(m). */
    data class ModeChange(val mode: ArduCopterFlightMode) : Event {
        override fun toString(): String = "mode($mode)"
    }

    /** Time passing between two events. The clock is environment, not part of the state. */
    data class Elapse(val millis: Long) : Event {
        override fun toString(): String = "+${millis}ms"
    }
}

// ---------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------

/** A clock that only moves when told to. Replaces the former TickingClock. */
private class ManualClock(private var current: Long = START_TIME_MS) : Clock {
    override fun now(): Long = current

    fun advance(millis: Long) {
        require(millis >= 0) { "Time cannot run backwards: $millis ms" }
        current += millis
    }
}

/** What an event did when it was performed. */
sealed interface Outcome {
    /** sync ran and dispatched [transition], or nothing if null. */
    data class Synced(val transition: StateTransition?) : Outcome

    /** A command or mode change passed its guard and was dispatched. */
    data object Dispatched : Outcome

    /** A command or mode change failed its guard and was skipped. */
    data class Rejected(val reason: String) : Outcome

    /** The clock was advanced. */
    data object TimePassed : Outcome
}

/** One entry of an execution's trace. [at] is milliseconds since [START_TIME_MS]. */
data class Step(val at: Long, val event: Event, val outcome: Outcome, val after: AircraftState)

/**
 * A state machine together with its clock and the trace of everything performed on it.
 *
 * Every execution starts in the state after BootTransition and StandbyTransition
 * (catalog, section 1). Both are dispatched without guard, as BootCommand does.
 * All further changes go through [perform], so that the trace stays complete.
 */
class Execution private constructor(private val clock: ManualClock) {
    private val fsm = AircraftStateMachine(clock)
    private val steps = mutableListOf<Step>()
    val initial: AircraftState

    init {
        fsm.dispatch(BootTransition)
        fsm.dispatch(StandbyTransition)
        initial = fsm.state
    }

    val state: AircraftState
        get() = fsm.state

    val trace: List<Step>
        get() = steps

    /** Milliseconds since [START_TIME_MS]. */
    val elapsed: Long
        get() = clock.now() - START_TIME_MS

    fun perform(event: Event) {
        val outcome: Outcome = when (event) {
            is Event.Sync -> {
                val transition = fsm.sync(event.telemetry.isArmed, event.telemetry.isFlying)
                Outcome.Synced(transition)
            }

            is Event.Dispatch -> {
                val check = fsm.canDispatch(event.command.transition)
                if (check == CommandResult.ok) {
                    fsm.dispatch(event.command.transition)
                    Outcome.Dispatched
                } else {
                    Outcome.Rejected(check.errorReason)
                }
            }

            // AircraftHandler.requestMode returns early if the mode is already set. Dispatching
            // anyway yields the same state, because updateArmFlag recomputes modeFlag from
            // unchanged inputs, so the catalog's formulation is followed literally here.
            is Event.ModeChange -> {
                val check = fsm.isModeAllowed(event.mode)
                if (check == CommandResult.ok) {
                    fsm.updateFlightMode(event.mode)
                    Outcome.Dispatched
                } else {
                    Outcome.Rejected(check.errorReason)
                }
            }

            is Event.Elapse -> {
                clock.advance(event.millis)
                Outcome.TimePassed
            }
        }
        steps.add(Step(elapsed, event, outcome, fsm.state))
    }

    fun performAll(events: List<Event>) {
        for (event in events) {
            perform(event)
        }
    }

    /** One iteration of the monitor loop under the given telemetry: wait one interval, then sync. */
    fun monitorTick(telemetry: Telemetry) {
        perform(Event.Elapse(SYNC_INTERVAL_MS))
        perform(Event.Sync(telemetry))
    }

    /** The trace as text, for withClue: every event, its outcome and the full state afterward. */
    fun describe(): String {
        val lines = mutableListOf("start: ${renderState(initial)}")
        for ((index, step) in steps.withIndex()) {
            lines.add(
                "${index + 1}. t=+${step.at}ms ${step.event} -> ${render(step.outcome)} | " +
                    renderState(step.after)
            )
        }
        return lines.joinToString("\n")
    }

    companion object {
        fun start(): Execution = Execution(ManualClock())

        fun reach(events: List<Event>): Execution {
            val execution = start()
            execution.performAll(events)
            return execution
        }
    }
}

// ---------------------------------------------------------------------------
// Generators
// ---------------------------------------------------------------------------

/** Telemetry for Sync events within a sequence. */
private val anyTelemetry: Arb<Telemetry> = Arb.element(Telemetry.all)

/**
 * Flight modes, drawn from the classes the state model distinguishes: GUIDED and AUTO allow
 * a takeoff without arm (TakeoffTransition.hasDelayedArmMode), LAND and RTL require flying
 * (FlightModeTransition.canTransition). Uniformly over all 25 modes each class would come up
 * in only 8 % of mode changes, so each gets a third, the last third is uniform over all modes.
 * The classes are written out instead of read from the model, for the same reason as
 * [ARM_TIMEOUT_MS].
 */
private val anyFlightMode: Arb<ArduCopterFlightMode> = Arb.choice(
    Arb.element(ArduCopterFlightMode.GUIDED, ArduCopterFlightMode.AUTO),
    Arb.element(ArduCopterFlightMode.LAND, ArduCopterFlightMode.RTL),
    Arb.enum<ArduCopterFlightMode>()
)

/**
 * Time between two events: mostly one monitor interval, otherwise short gaps, the values
 * around [ARM_TIMEOUT_MS] that decide branch 5 of sync, and long gaps beyond it.
 */
private val anyElapse: Arb<Long> = Arb.choose(
    3 to Arb.constant(SYNC_INTERVAL_MS),
    2 to Arb.long(0L..ARM_TIMEOUT_MS),
    2 to Arb.element(ARM_TIMEOUT_MS - 1, ARM_TIMEOUT_MS, ARM_TIMEOUT_MS + 1),
    1 to Arb.long((ARM_TIMEOUT_MS + 2)..(3 * ARM_TIMEOUT_MS))
)

/**
 * A single event. The weights are a starting point and are checked against the statistics
 * collected in the specs.
 */
private val anyEvent: Arb<Event> = Arb.choose(
    4 to anyTelemetry.map { Event.Sync(it) },
    3 to Arb.enum<Command>().map { Event.Dispatch(it) },
    2 to anyFlightMode.map { Event.ModeChange(it) },
    2 to anyElapse.map { Event.Elapse(it) }
)

/**
 * Sequences that every property run should see. They replace the former StableSituation
 * recipes and add call patterns that random sequences rarely produce. All of them go through
 * the guards, so the states they reach lie in S by construction.
 */
private val canonicalSequences: List<List<Event>> = listOf(
    // Grounded and disarmed (former GROUNDED)
    emptyList(),
    // ArmCommand (former ARMED_ON_GROUND)
    listOf(Event.Dispatch(Command.ARM)),
    // ArmCommand, then TakeoffCommand (former AIRBORNE)
    listOf(
        Event.Dispatch(Command.ARM),
        Event.Dispatch(Command.TAKEOFF),
        Event.Dispatch(Command.FLYING)
    ),
    // RequestLand during flight
    listOf(
        Event.Dispatch(Command.ARM),
        Event.Dispatch(Command.TAKEOFF),
        Event.Dispatch(Command.FLYING),
        Event.Dispatch(Command.LAND)
    ),
    // RequestTakeoff in GUIDED without arm
    listOf(
        Event.ModeChange(ArduCopterFlightMode.GUIDED),
        Event.Dispatch(Command.TAKEOFF)
    ),
    // Former delayed arm in GUIDED (until G-007): ArmTransition without confirmation by the
    // telemetry, then waiting up to and just past the former timeout. Kept as inputs: the
    // first disarmed sync must clear the arm bit.
    listOf(
        Event.ModeChange(ArduCopterFlightMode.GUIDED),
        Event.Dispatch(Command.ARM),
        Event.Elapse(ARM_TIMEOUT_MS),
        Event.Sync(Telemetry(isArmed = false, isFlying = false))
    ),
    listOf(
        Event.ModeChange(ArduCopterFlightMode.GUIDED),
        Event.Dispatch(Command.ARM),
        Event.Elapse(ARM_TIMEOUT_MS + 1),
        Event.Sync(Telemetry(isArmed = false, isFlying = false))
    )
)

/**
 * Shrinks an event sequence by removing events: first one half at a time, then every single
 * event. Kotest's default ListShrinker only cuts at the ends, so an irrelevant event in the
 * middle of a counterexample would survive. With single removals, the shrunk sequence is
 * 1-minimal: removing any one event makes the failure disappear. This holds as long as
 * shrinking is not cut off by a bound, see [catalogConfig].
 */
private object EventSequenceShrinker : Shrinker<List<Event>> {
    override fun shrink(value: List<Event>): List<List<Event>> {
        val candidates = mutableListOf<List<Event>>()
        if (value.size > 1) {
            val half = value.size / 2
            candidates.add(value.take(half))
            candidates.add(value.drop(half))
        }
        for (index in value.indices) {
            candidates.add(value.take(index) + value.drop(index + 1))
        }
        return candidates
    }
}

/** An event sequence; executed with [Execution.reach], it yields a state in S. */
val eventSequence: Arb<List<Event>> = arbitrary(
    edgecases = canonicalSequences,
    shrinker = EventSequenceShrinker
) { _ ->
    val length = Arb.int(SEQUENCE_LENGTH).bind()
    List(length) { anyEvent.bind() }
}

/**
 * Property test config for the catalog specs. Shrinking is unbounded so that counterexamples
 * are 1-minimal (see [EventSequenceShrinker]). It still terminates: every shrinker in this file
 * only removes events, so every accepted step makes the input strictly shorter.
 */
@OptIn(ExperimentalKotest::class)
val catalogConfig: PropTestConfig = PropTestConfig(shrinkingMode = ShrinkingMode.Unbounded)

/** Number of cases checked per test for properties with a precondition (E-03, E-04). */
const val CHECKED_CASES = 1000

/**
 * Generation budget per test for properties with a precondition. It suffices for
 * [CHECKED_CASES] checked cases as long as at least 5 % of the generated cases meet the
 * precondition. If it is used up, the test ends with fewer checked cases.
 */
const val GENERATION_BUDGET = 20_000

/**
 * Config for properties with a precondition: a test runs until [CHECKED_CASES] cases are
 * checked or [GENERATION_BUDGET] cases are generated. Kotest's discard limit is switched off;
 * instead the specs report checked and discarded cases with [reportCoverage].
 */
@OptIn(ExperimentalKotest::class)
val conditionalConfig: PropTestConfig = catalogConfig.copy(
    constraints = Constraints { it.attempts() < CHECKED_CASES && it.evals() < GENERATION_BUDGET },
    maxDiscardPercentage = 100
)

/**
 * Prints how many cases were checked and discarded, and flags a gap below [CHECKED_CASES].
 * Only reached when no counterexample was found. The output is German like the test names.
 */
internal fun reportCoverage(property: String, t: Telemetry, context: PropertyContext) {
    val checked = context.attempts()
    val discarded = context.evals() - checked
    println("[$property $t] $checked Fälle geprüft, $discarded verworfen")
    if (checked < CHECKED_CASES) {
        println("[$property $t] Leerstelle: weniger als $CHECKED_CASES Fälle im Budget erreicht")
    }
}

// ---------------------------------------------------------------------------
// E-04: candidates for stable states
// ---------------------------------------------------------------------------

/**
 * Command suffix that steers a sequence towards a state with π(view(s)) ∈ soll(t), following
 * the call patterns in AircraftCommands. It only shapes the distribution: whether the reached
 * state is stable is decided by the oracle in the spec, and a wrong assumption here only shows
 * up as a higher discard rate. There is deliberately no sync suffix: every candidate would then
 * already have passed through sync(·, t), which hides violations on the first sync.
 */
private fun steeringSuffix(t: Telemetry): List<Event> = when {
    // Row 3, ArmCommand and TakeoffCommand
    t.isArmed && t.isFlying -> listOf(
        Event.Dispatch(Command.ARM),
        Event.Dispatch(Command.TAKEOFF),
        Event.Dispatch(Command.FLYING)
    )

    // Row 2, ArmCommand
    t.isArmed -> listOf(Event.Dispatch(Command.ARM))

    // Rows 1 and 4 share their target view, DisarmCommand
    else -> listOf(Event.Dispatch(Command.STANDBY))
}

/**
 * Candidates for E-04 under telemetry [t]: half of the sequences are random as in
 * [eventSequence], the other half end with the [steeringSuffix] for t. Both go through
 * [EventSequenceShrinker], so suffix events can be shrunk away like any other event.
 */
fun stableCandidates(t: Telemetry): Arb<List<Event>> {
    val suffix = steeringSuffix(t)
    return arbitrary(
        edgecases = canonicalSequences + canonicalSequences.map { it + suffix },
        shrinker = EventSequenceShrinker
    ) { _ ->
        val prefix = eventSequence.bind()
        val steered = Arb.boolean().bind()
        if (steered) prefix + suffix else prefix
    }
}

// ---------------------------------------------------------------------------
// E-03: pairs of executions with the same view
// ---------------------------------------------------------------------------

/** Two executions to be compared at the same point in time. */
data class ExecutionPair(val first: Execution, val second: Execution)

/** Input for E-03: two event sequences whose end states are compared. */
sealed interface ViewPair {
    val first: List<Event>
    val second: List<Event>

    /**
     * Executes both sequences, then advances the clock that is behind, so that both executions
     * are compared at the same point in time. Otherwise, a difference after the next sync could
     * stem from the clocks instead of from the internal state.
     */
    fun reach(): ExecutionPair {
        val a = Execution.reach(first)
        val b = Execution.reach(second)
        if (a.elapsed < b.elapsed) {
            a.perform(Event.Elapse(b.elapsed - a.elapsed))
        }
        if (b.elapsed < a.elapsed) {
            b.perform(Event.Elapse(a.elapsed - b.elapsed))
        }
        return ExecutionPair(a, b)
    }
}

/**
 * The same event sequence twice, the second time with an extra delay of [skew] before position
 * [insertAt]. Both executions see the same events, so their views usually agree, while every
 * arm after the insertion point gets a later timestamp.
 */
data class Twins(val events: List<Event>, val insertAt: Int, val skew: Long) : ViewPair {
    override val first: List<Event>
        get() = events

    override val second: List<Event>
        get() {
            val at = minOf(insertAt, events.size)
            return events.take(at) + Event.Elapse(skew) + events.drop(at)
        }
}

/**
 * Two different event sequences. When their end states share a view, they may differ in more
 * than timing, for instance in whether the arm was requested by a command or by sync.
 */
data class HistoryPair(override val first: List<Event>, override val second: List<Event>) :
    ViewPair

/**
 * Shrinks a [ViewPair] with [EventSequenceShrinker]: for [Twins] the shared sequence, so that
 * both executions keep seeing the same events, for a [HistoryPair] one sequence at a time. A
 * history pair additionally gets candidates that remove the same event from the common end of
 * both sequences at once. Without them, an event that both need for equal views, such as a
 * shared mode change, could never be removed: taking it from one sequence alone breaks the
 * precondition. A shrunk pair is 1-minimal with respect to all three kinds of removal.
 */
private object ViewPairShrinker : Shrinker<ViewPair> {
    override fun shrink(value: ViewPair): List<ViewPair> = when (value) {
        is Twins -> EventSequenceShrinker.shrink(value.events).map { value.copy(events = it) }

        is HistoryPair -> {
            val firstShrunk = EventSequenceShrinker.shrink(value.first).map {
                value.copy(first = it)
            }
            val secondShrunk = EventSequenceShrinker.shrink(value.second).map {
                value.copy(second = it)
            }
            firstShrunk + secondShrunk + commonEndRemovals(value)
        }
    }

    /** Removes the k-th event from the end of both sequences, for every k within their common end. */
    private fun commonEndRemovals(value: HistoryPair): List<ViewPair> {
        val candidates = mutableListOf<ViewPair>()
        var k = 1
        while (k <= value.first.size && k <= value.second.size) {
            val i = value.first.size - k
            val j = value.second.size - k
            if (value.first[i] != value.second[j]) {
                break
            }
            candidates.add(
                HistoryPair(
                    value.first.take(i) + value.first.drop(i + 1),
                    value.second.take(j) + value.second.drop(j + 1)
                )
            )
            k++
        }
        return candidates
    }
}

/** Length range of the suffix that both sequences of a [HistoryPair] share. */
private val SHARED_SUFFIX_LENGTH = 0..10

/**
 * Candidates for E-03: half [Twins], half [HistoryPair]. Twins reach equal views reliably and
 * vary the arm timestamps. A history pair consists of two independent prefixes followed by a
 * shared suffix: the suffix tends to bring both executions to the same view, while the
 * prefixes leave different internal state behind. Fully independent sequences share a view
 * far less often (suffix length 0 still covers them). The edge cases shift each canonical
 * sequence by just over [ARM_TIMEOUT_MS], so that only one of the twins is past the timeout
 * when compared.
 */
val viewPairs: Arb<ViewPair> = arbitrary(
    edgecases = canonicalSequences.map { Twins(it, insertAt = 0, skew = ARM_TIMEOUT_MS + 1) },
    shrinker = ViewPairShrinker
) { _ ->
    val twins = Arb.boolean().bind()
    if (twins) {
        Twins(eventSequence.bind(), Arb.int(0..SEQUENCE_LENGTH.last).bind(), anyElapse.bind())
    } else {
        val suffixLength = Arb.int(SHARED_SUFFIX_LENGTH).bind()
        val suffix = List(suffixLength) { anyEvent.bind() }
        HistoryPair(eventSequence.bind() + suffix, eventSequence.bind() + suffix)
    }
}

// ---------------------------------------------------------------------------
// Rendering for traces
// ---------------------------------------------------------------------------

/**
 * The positions from [from] on where [values] differ from [target], grouped into runs of equal
 * value, e.g. "m = 3..54: (ACTIVE, armed, ON_GROUND)". [index] names the position variable.
 */
internal fun <T> deviationRanges(
    values: List<T>,
    target: T,
    from: Int,
    index: String,
    show: (T) -> String = { it.toString() }
): List<String> = deviationRuns(values, from, index, show) { it == target }

/**
 * Like [deviationRanges], but against a set of accepted values: soll(t) is set-valued since
 * catalog v1.2. Passing such a set to [deviationRanges] would still compile with T = Any and
 * report every position, so call sites with soll(t) must use this function.
 */
internal fun <T> deviationRangesOutside(
    values: List<T>,
    accepted: Set<T>,
    from: Int,
    index: String,
    show: (T) -> String = { it.toString() }
): List<String> = deviationRuns(values, from, index, show) { it in accepted }

private fun <T> deviationRuns(
    values: List<T>,
    from: Int,
    index: String,
    show: (T) -> String,
    isAccepted: (T) -> Boolean
): List<String> {
    val result = mutableListOf<String>()
    var position = from
    while (position < values.size) {
        if (isAccepted(values[position])) {
            position++
            continue
        }
        val start = position
        while (position + 1 < values.size && values[position + 1] == values[start]) {
            position++
        }
        val range = if (start == position) "$start" else "$start..$position"
        result.add("$index = $range: ${show(values[start])}")
        position++
    }
    return result
}

/** A state with all fields, internal ones included, for traces and E-04b. */
internal fun renderState(state: AircraftState): String = "mavlink=${mavStateName(state.mavlink)} " +
    "landed=${landedStateName(state.landed)} " +
    "modeFlag=${modeFlagName(state.modeFlag)} " +
    "mode=${state.flightMode} " +
    "armed=${state.armed} "

private fun render(outcome: Outcome): String = when (outcome) {
    is Outcome.Synced -> {
        val name = outcome.transition?.let { it::class.simpleName }
        "sync dispatched ${name ?: "nothing"}"
    }

    Outcome.Dispatched -> "applied"

    is Outcome.Rejected -> "rejected (${outcome.reason})"

    Outcome.TimePassed -> "time passed"
}
