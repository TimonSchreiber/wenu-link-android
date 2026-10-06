package org.WenuLink.adapters.aircraft

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long

/** Telemetry sync like the one triggered by the monitor loop every tick. */
data class SyncEvent(val isArmed: Boolean, val isFlying: Boolean)

/** An arbitrary SyncEvent */
val syncEvent: Arb<SyncEvent> =
    Arb.bind(Arb.boolean(), Arb.boolean()) { armed, flying -> SyncEvent(armed, flying) }

/** An arbitrarily long List of arbitrary SyncEvents */
val syncSequence: Arb<List<SyncEvent>> = Arb.list(syncEvent, 0..12)

/** DJI-Telemetrie, which can be stable over multiple ticks */
enum class StableSituation(val isArmed: Boolean, val isFlying: Boolean) {
    GROUNDED(isArmed = false, isFlying = false),
    ARMED_ON_GROUND(isArmed = true, isFlying = false),
    AIRBORNE(isArmed = true, isFlying = true)
}

/** Deterministic yet advancing clock. */
class TickingClock(private val step: Long = 100L, startAt: Long = 0L) : Clock {
    private var t = startAt

    override fun now(): Long {
        t += step
        return t
    }
}

/** Build a stable Bride state via dispatch() and without sync() */
fun machineIn(
    situation: StableSituation,
    flightMode: ArduCopterFlightMode = ArduCopterFlightMode.STABILIZE,
    clock: Clock = TickingClock()
): AircraftStateMachine = AircraftStateMachine(clock).apply {
    dispatch(BootTransition)
    dispatch(StandbyTransition)
    dispatch(FlightModeTransition(flightMode))
    when (situation) {
        StableSituation.GROUNDED -> Unit

        StableSituation.ARMED_ON_GROUND -> dispatch(ArmTransition(clock.now()))

        StableSituation.AIRBORNE -> {
            dispatch(ArmTransition(clock.now()))
            dispatch(TakeoffTransition)
            dispatch(FlyingTransition)
        }
    }
}

data class StableSetup(
    val situation: StableSituation,
    val flightMode: ArduCopterFlightMode,
    val clockStart: Long,
    val repeats: Int
)

val stableSetup: Arb<StableSetup> = Arb.bind(
    Arb.enum<StableSituation>(),
    Arb.enum<ArduCopterFlightMode>(),
    Arb.long(0L..100_000L),
    Arb.int(1..5)
) { situation, mode, start, repeats -> StableSetup(situation, mode, start, repeats) }

/** Random prefix followed by a stable situation */
data class ConvergenceSetup(val prefix: List<SyncEvent>, val target: StableSituation)

val convergenceSetup: Arb<ConvergenceSetup> =
    Arb.bind(syncSequence, Arb.enum<StableSituation>()) { prefix, target ->
        ConvergenceSetup(prefix, target)
    }

/** Initial state of preserved scenario */
fun groundedMachine(clock: Clock = TickingClock()): AircraftStateMachine =
    machineIn(StableSituation.GROUNDED, clock = clock)

fun convergedMachine(
    setup: ConvergenceSetup,
    repeats: Int = 10,
    clock: Clock = TickingClock()
): AircraftStateMachine = groundedMachine(clock).apply {
    for (e in setup.prefix) sync(e.isArmed, e.isFlying)
    repeat(repeats) { sync(setup.target.isArmed, setup.target.isFlying) }
}
