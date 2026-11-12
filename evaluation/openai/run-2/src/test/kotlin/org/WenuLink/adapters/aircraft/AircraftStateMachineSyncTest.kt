package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private data class SyncTelemetry(
    val armed: Boolean,
    val flying: Boolean
)

private data class SyncScenario(
    val name: String,
    val setup: List<StateTransition>,
    val expectedTransitions: List<StateTransition?>
)

private val testHome = Coordinates3D(
    lat = 48.137154,
    long = 11.576124,
    alt = 520.5f
)

private fun createMachine(
    transitions: List<StateTransition>,
    mode: ArduCopterFlightMode = ArduCopterFlightMode.STABILIZE
): AircraftStateMachine = AircraftStateMachine().apply {
    // dispatch führt die Reducer unabhängig von canTransition aus.
    transitions.forEach { dispatch(it) }
    updateFlightMode(mode)
    updateHomePosition(testHome)
}

/**
 * Erwartete Änderungen unabhängig von den produktiven Reducern.
 * Alle nicht betroffenen Felder müssen erhalten bleiben.
 */
private fun expectedStateAfterSync(
    before: AircraftState,
    transition: StateTransition?
): AircraftState {
    val expected = when (transition) {
        null -> return before

        ArmTransition -> before.copy(armed = true)

        TakeoffTransition -> before.copy(
            mavlink = MAV_STATE.MAV_STATE_ACTIVE,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        )

        FlyingTransition -> before.copy(
            mavlink = MAV_STATE.MAV_STATE_ACTIVE,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        )

        TouchdownTransition -> before.copy(
            mavlink = MAV_STATE.MAV_STATE_STANDBY,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        )

        StandbyTransition -> before.copy(
            mavlink = MAV_STATE.MAV_STATE_STANDBY,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND,
            armed = false
        )

        else -> error("Unerwartete Transition im Test: $transition")
    }

    return expected.copy(
        modeFlag = if (expected.armed) {
            expected.flightMode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        } else {
            expected.flightMode.baseMode
        }
    )
}

private fun assertSync(
    machine: AircraftStateMachine,
    telemetry: SyncTelemetry,
    expectedTransition: StateTransition?
) {
    val before = machine.state

    machine.sync(
        isArmed = telemetry.armed,
        isFlying = telemetry.flying
    ) shouldBe expectedTransition

    machine.state shouldBe expectedStateAfterSync(before, expectedTransition)

    if (expectedTransition == null) {
        machine.state shouldBeSameInstanceAs before
    }
}

class AircraftStateMachineSyncTest : StringSpec({

    val telemetryCases = listOf(
        SyncTelemetry(armed = false, flying = false),
        SyncTelemetry(armed = false, flying = true),
        SyncTelemetry(armed = true, flying = false),
        SyncTelemetry(armed = true, flying = true)
    )

    val armedOnGround = listOf(StandbyTransition, ArmTransition)
    val takingOff = armedOnGround + TakeoffTransition
    val flying = takingOff + FlyingTransition
    val landing = flying + LandTransition

    val scenarios = listOf(
        SyncScenario(
            name = "uninitialisiert",
            setup = emptyList(),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Boot",
            setup = listOf(BootTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Standby und unbewaffnet am Boden",
            setup = listOf(StandbyTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "bewaffnet am Boden",
            setup = armedOnGround,
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, null, TakeoffTransition
            )
        ),
        SyncScenario(
            name = "Startphase",
            setup = takingOff,
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, FlyingTransition
            )
        ),
        SyncScenario(
            name = "in der Luft",
            setup = flying,
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        ),
        SyncScenario(
            name = "Landung in STABILIZE",
            setup = landing,
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, FlyingTransition
            )
        ),
        SyncScenario(
            name = "Flugabbruch in der Luft",
            setup = flying + FlightTerminationTransition,
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        ),
        SyncScenario(
            name = "ausgeschaltet",
            setup = listOf(StandbyTransition, PowerOffTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "bewaffnet mit undefiniertem Bodenzustand",
            setup = listOf(ArmTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        )
    )

    scenarios.forEach { scenario ->
        telemetryCases.forEachIndexed { index, telemetry ->
            "sync aus ${scenario.name}: armed=${telemetry.armed}, flying=${telemetry.flying}" {
                val machine = createMachine(scenario.setup)

                assertSync(machine, telemetry, scenario.expectedTransitions[index])
            }
        }
    }

    val landingReportingModes = setOf(
        ArduCopterFlightMode.AUTO,
        ArduCopterFlightMode.RTL,
        ArduCopterFlightMode.LAND,
        ArduCopterFlightMode.SMART_RTL
    )

    ArduCopterFlightMode.entries.forEach { mode ->
        "sync behandelt LANDING bei fliegender Telemetrie im Modus $mode korrekt" {
            val machine = createMachine(landing, mode)
            val expectedTransition = if (mode in landingReportingModes) {
                null
            } else {
                FlyingTransition
            }

            assertSync(
                machine,
                SyncTelemetry(armed = true, flying = true),
                expectedTransition
            )
        }

        "sync bestätigt den Bodenkontakt während LANDING im Modus $mode" {
            val machine = createMachine(landing, mode)

            assertSync(
                machine,
                SyncTelemetry(armed = true, flying = false),
                TouchdownTransition
            )

            machine.state.armed shouldBe true
        }

        "sync setzt und entfernt das Armed-Bit unter Erhalt des Flugmodus $mode" {
            val machine = createMachine(listOf(StandbyTransition), mode)

            assertSync(
                machine,
                SyncTelemetry(armed = true, flying = false),
                ArmTransition
            )
            machine.state.modeFlag shouldBe (
                mode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
            )

            assertSync(
                machine,
                SyncTelemetry(armed = false, flying = false),
                StandbyTransition
            )
            machine.state.modeFlag shouldBe mode.baseMode
            machine.state.flightMode shouldBe mode
            machine.state.homeCoordinates shouldBe testHome
        }
    }

    "sync führt pro Aufruf nur eine Transition aus und durchläuft einen vollständigen Flug" {
        val machine = createMachine(listOf(StandbyTransition))
        val flyingTelemetry = SyncTelemetry(armed = true, flying = true)

        // Arm hat Vorrang vor Takeoff, obwohl die Telemetrie bereits Flug meldet.
        assertSync(machine, flyingTelemetry, ArmTransition)
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND

        assertSync(machine, flyingTelemetry, TakeoffTransition)
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF

        assertSync(machine, flyingTelemetry, FlyingTransition)
        assertSync(machine, flyingTelemetry, null)

        machine.updateFlightMode(ArduCopterFlightMode.LAND)
        machine.dispatch(LandTransition)

        assertSync(machine, flyingTelemetry, null)

        assertSync(
            machine,
            SyncTelemetry(armed = true, flying = false),
            TouchdownTransition
        )
        machine.state.armed shouldBe true

        assertSync(
            machine,
            SyncTelemetry(armed = true, flying = false),
            null
        )

        assertSync(
            machine,
            SyncTelemetry(armed = false, flying = false),
            StandbyTransition
        )

        // Disarmed liefert auch im bereits erreichten Standby erneut eine Transition.
        assertSync(
            machine,
            SyncTelemetry(armed = false, flying = false),
            StandbyTransition
        )

        machine.state.flightMode shouldBe ArduCopterFlightMode.LAND
        machine.state.homeCoordinates shouldBe testHome
    }

    "sync bewahrt beim Armieren den undefinierten Bodenzustand des frischen Automaten" {
        val machine = AircraftStateMachine()
        val initial = machine.state

        machine.canDispatch(ArmTransition).hasError shouldBe true
        machine.sync(isArmed = true, isFlying = true) shouldBe ArmTransition

        machine.state shouldBe initial.copy(
            armed = true,
            modeFlag = initial.flightMode.baseMode or
                MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        )

        // UNDEFINED wird bei fliegender Telemetrie nicht direkt zu TAKEOFF oder IN_AIR.
        assertSync(
            machine,
            SyncTelemetry(armed = true, flying = true),
            null
        )
    }

    "sync erzwingt Standby bei Disarm in der Luft trotz abgelehnter canDispatch-Prüfung" {
        val machine = createMachine(flying, ArduCopterFlightMode.GUIDED)

        machine.canDispatch(StandbyTransition).hasError shouldBe true

        assertSync(
            machine,
            SyncTelemetry(armed = false, flying = true),
            StandbyTransition
        )

        machine.state.armed shouldBe false
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
    }

    "sync korrigiert LANDING nach einem Wechsel von LAND zu GUIDED" {
        val machine = createMachine(landing, ArduCopterFlightMode.LAND)
        val telemetry = SyncTelemetry(armed = true, flying = true)

        assertSync(machine, telemetry, null)

        machine.updateFlightMode(ArduCopterFlightMode.GUIDED)
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING

        assertSync(machine, telemetry, FlyingTransition)
        assertSync(machine, telemetry, null)

        machine.state.flightMode shouldBe ArduCopterFlightMode.GUIDED
    }
})
