package org.WenuLink.adapters.aircraft

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.Shrinker
import io.kotest.property.ShrinkingMode
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
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
 * Transitions that commands dispatch within the takeoff and landing cycle, modelled as
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

/** An event that moves an execution within S, or lets time pass. */
sealed interface Event {
    /** Telemetry reconciliation by the monitor loop. */
    data class Sync(val telemetry: Telemetry) : Event {
        override fun toString(): String = "sync$telemetry"
    }

    /** A command, modelled as dispatch(τ) under canDispatch(τ). */
    data class Dispatch(val command: Command) : Event {
        override fun toString(): String = "dispatch($command)"
    }

    /** A mode change, modelled as updateFlightMode(m) under isModeAllowed(m). */
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

    /** The trace as text, for withClue: every event, its outcome and the full state afterwards. */
    fun describe(): String {
        val lines = mutableListOf("start: ${render(initial)}")
        for ((index, step) in steps.withIndex()) {
            lines.add(
                "${index + 1}. t=+${step.at}ms ${step.event} -> ${render(step.outcome)} | " +
                    render(step.after)
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
    // ArmCommand in GUIDED dispatches ArmTransition without arming the motors; the telemetry
    // stays disarmed until the timeout boundary and one millisecond past it
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
 * are 1-minimal (see [EventSequenceShrinker]). It still terminates: the shrinker only removes
 * events and the Int shrinker for [Twins.insertAt] only lowers the value, so every accepted
 * step makes the input strictly smaller.
 */
val catalogConfig: PropTestConfig = PropTestConfig(shrinkingMode = ShrinkingMode.Unbounded)

// ---------------------------------------------------------------------------
// E-03: executions with the same view and different internal bookkeeping
// ---------------------------------------------------------------------------

/** Two executions to be compared at the same point in time. */
data class TwinExecutions(val first: Execution, val second: Execution)

/**
 * The same event sequence executed twice, the second time with an extra delay of [skew]
 * inserted before position [insertAt]. Both executions see the same events, so their views
 * usually agree, while every arm after the insertion point gets a later timestamp.
 */
data class Twins(val events: List<Event>, val insertAt: Int, val skew: Long) {
    fun delayedEvents(): List<Event> {
        val at = minOf(insertAt, events.size)
        return events.take(at) + Event.Elapse(skew) + events.drop(at)
    }

    /**
     * Executes both sequences, then advances the first clock by [skew]. Without this, a
     * difference after the next sync could stem from the two clocks instead of from the
     * internal state.
     */
    fun reach(): TwinExecutions {
        val first = Execution.reach(events)
        first.perform(Event.Elapse(skew))
        val second = Execution.reach(delayedEvents())
        return TwinExecutions(first, second)
    }
}

val twins: Arb<Twins> = Arb.bind(
    eventSequence,
    Arb.int(0..SEQUENCE_LENGTH.last),
    anyElapse
) { events, insertAt, skew -> Twins(events, insertAt, skew) }

// ---------------------------------------------------------------------------
// Rendering for traces
// ---------------------------------------------------------------------------

private fun render(state: AircraftState): String {
    val armTimestamp = if (state.armTimestamp == 0L) {
        "none"
    } else {
        "+${state.armTimestamp - START_TIME_MS}ms"
    }
    return "mavlink=${mavStateName(state.mavlink)} " +
        "landed=${landedStateName(state.landed)} " +
        "modeFlag=${modeFlagName(state.modeFlag)} " +
        "mode=${state.flightMode} " +
        "armTimestamp=$armTimestamp"
}

private fun render(outcome: Outcome): String = when (outcome) {
    is Outcome.Synced -> {
        val name = outcome.transition?.let { it::class.simpleName }
        "sync dispatched ${name ?: "nothing"}"
    }

    Outcome.Dispatched -> "applied"

    is Outcome.Rejected -> "rejected (${outcome.reason})"

    Outcome.TimePassed -> "time passed"
}
