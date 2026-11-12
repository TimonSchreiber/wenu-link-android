package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private data class SyncTelemetry(
    val armed: Boolean,
    val flying: Boolean
)

private data class SyncScenario(
    val name: String,
    val setup: List<StateTransition>,
    // Reihenfolge: disarmed/am Boden, disarmed/fliegend, armed/am Boden, armed/fliegend.
    val expectedTransitions: List<StateTransition?>
)

private fun syncTestMachine(
    transitions: List<StateTransition> = emptyList()
): AircraftStateMachine = AircraftStateMachine().apply {
    transitions.forEach { dispatch(it) }
    updateHomePosition(Coordinates3D(lat = 48.1, long = 11.5, alt = 520f))
}

/**
 * Erwartete Zustandsänderungen, ohne die produktiven Reducer aufzurufen.
 * copy() prüft zugleich, dass alle übrigen Felder erhalten bleiben.
 */
private fun expectedSyncState(
    before: AircraftState,
    transition: StateTransition?
): AircraftState {
    val after = when (transition) {
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

        else -> error("Unerwartete sync-Transition: $transition")
    }

    return after.copy(
        modeFlag = if (after.armed) {
            after.flightMode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        } else {
            after.flightMode.baseMode
        }
    )
}

class AircraftStateMachineSyncTest : StringSpec({

    val telemetryCases = listOf(
        SyncTelemetry(armed = false, flying = false),
        SyncTelemetry(armed = false, flying = true),
        SyncTelemetry(armed = true, flying = false),
        SyncTelemetry(armed = true, flying = true)
    )

    val scenarios = listOf(
        SyncScenario(
            name = "Initialzustand",
            setup = emptyList(),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Bootzustand",
            setup = listOf(BootTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Poweroff",
            setup = listOf(StandbyTransition, PowerOffTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Standby",
            setup = listOf(StandbyTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Armed mit undefiniertem landed-Zustand",
            setup = listOf(ArmTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        ),
        SyncScenario(
            name = "Armed am Boden",
            setup = listOf(StandbyTransition, ArmTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, null, TakeoffTransition
            )
        ),
        SyncScenario(
            name = "Startend",
            setup = listOf(StandbyTransition, ArmTransition, TakeoffTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, FlyingTransition
            )
        ),
        SyncScenario(
            name = "Fliegend",
            setup = listOf(StandbyTransition, ArmTransition, FlyingTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        ),
        SyncScenario(
            name = "Landend in STABILIZE",
            setup = listOf(
                StandbyTransition, ArmTransition, FlyingTransition, LandTransition
            ),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, FlyingTransition
            )
        ),
        SyncScenario(
            name = "Flight Termination",
            setup = listOf(
                StandbyTransition, ArmTransition, FlyingTransition, FlightTerminationTransition
            ),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, TouchdownTransition, null
            )
        ),
        SyncScenario(
            name = "Nach Touchdown weiterhin armed",
            setup = listOf(
                StandbyTransition, ArmTransition, FlyingTransition, TouchdownTransition
            ),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, null, TakeoffTransition
            )
        ),
        // dispatch() prüft canTransition() nicht. Auch solche Zustände sind erreichbar.
        SyncScenario(
            name = "Disarmed im Startzustand",
            setup = listOf(TakeoffTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Disarmed im Flugzustand",
            setup = listOf(FlyingTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        ),
        SyncScenario(
            name = "Disarmed im Landezustand",
            setup = listOf(FlyingTransition, LandTransition),
            expectedTransitions = listOf(
                StandbyTransition, StandbyTransition, ArmTransition, ArmTransition
            )
        )
    )

    scenarios.forEach { scenario ->
        telemetryCases.forEachIndexed { index, telemetry ->
            "sync: ${scenario.name}, armed=${telemetry.armed}, flying=${telemetry.flying}" {
                val machine = syncTestMachine(scenario.setup)
                val before = machine.state
                val expectedTransition = scenario.expectedTransitions[index]

                val result = machine.sync(
                    isArmed = telemetry.armed,
                    isFlying = telemetry.flying
                )

                result shouldBe expectedTransition
                machine.state shouldBe expectedSyncState(before, expectedTransition)
            }
        }
    }

    val landingModes = setOf(
        ArduCopterFlightMode.AUTO,
        ArduCopterFlightMode.RTL,
        ArduCopterFlightMode.LAND,
        ArduCopterFlightMode.SMART_RTL
    )

    ArduCopterFlightMode.entries.forEach { mode ->
        telemetryCases.forEach { telemetry ->
            "sync: LANDING in $mode, armed=${telemetry.armed}, flying=${telemetry.flying}" {
                val machine = syncTestMachine(
                    listOf(
                        StandbyTransition,
                        ArmTransition,
                        FlyingTransition,
                        FlightModeTransition(mode),
                        LandTransition
                    )
                )
                val before = machine.state
                val expectedTransition = when {
                    !telemetry.armed -> StandbyTransition
                    !telemetry.flying -> TouchdownTransition
                    mode in landingModes -> null
                    else -> FlyingTransition
                }

                val result = machine.sync(
                    isArmed = telemetry.armed,
                    isFlying = telemetry.flying
                )

                result shouldBe expectedTransition
                machine.state shouldBe expectedSyncState(before, expectedTransition)
            }
        }

        "sync: Arming und Disarming erhalten $mode und aktualisieren das Armed-Bit" {
            val machine = syncTestMachine(
                listOf(StandbyTransition, FlightModeTransition(mode))
            )
            val before = machine.state

            machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
            machine.state shouldBe before.copy(
                armed = true,
                modeFlag = mode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
            )

            machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
            machine.state shouldBe before
        }
    }

    "sync führt pro Aufruf nur eine Transition aus und durchläuft den Flugzyklus" {
        val machine = syncTestMachine(listOf(StandbyTransition))

        val steps = listOf(
            SyncTelemetry(armed = true, flying = true) to ArmTransition,
            SyncTelemetry(armed = true, flying = true) to TakeoffTransition,
            SyncTelemetry(armed = true, flying = true) to FlyingTransition,
            SyncTelemetry(armed = true, flying = true) to null,
            SyncTelemetry(armed = true, flying = false) to TouchdownTransition,
            SyncTelemetry(armed = true, flying = false) to null,
            SyncTelemetry(armed = false, flying = false) to StandbyTransition,
            SyncTelemetry(armed = false, flying = false) to StandbyTransition
        )

        steps.forEach { (telemetry, expectedTransition) ->
            val before = machine.state

            machine.sync(telemetry.armed, telemetry.flying) shouldBe expectedTransition
            machine.state shouldBe expectedSyncState(before, expectedTransition)
        }
    }

    "sync armt auch dann, wenn canDispatch das Arming im Initialzustand ablehnt" {
        val machine = syncTestMachine()
        val before = machine.state

        machine.canDispatch(ArmTransition).hasError shouldBe true

        machine.sync(isArmed = true, isFlying = true) shouldBe ArmTransition
        machine.state shouldBe expectedSyncState(before, ArmTransition)

        // UNDEFINED wird bei fliegender Telemetrie nicht automatisch zu TAKEOFF oder IN_AIR.
        val armedState = machine.state
        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state shouldBe armedState
    }

    "sync setzt bei Disarming trotz fliegender Telemetrie unmittelbar auf Standby zurück" {
        val machine = syncTestMachine(
            listOf(StandbyTransition, ArmTransition, FlyingTransition)
        )
        val before = machine.state

        machine.canDispatch(StandbyTransition).hasError shouldBe true

        machine.sync(isArmed = false, isFlying = true) shouldBe StandbyTransition
        machine.state shouldBe expectedSyncState(before, StandbyTransition)

        val standbyState = machine.state
        machine.sync(isArmed = false, isFlying = true) shouldBe StandbyTransition
        machine.state shouldBe standbyState
    }

    "sync bewertet einen Flugmoduswechsel während LANDING beim nächsten Aufruf neu" {
        val machine = syncTestMachine(
            listOf(
                StandbyTransition,
                ArmTransition,
                FlyingTransition,
                FlightModeTransition(ArduCopterFlightMode.LAND),
                LandTransition
            )
        )
        val landingState = machine.state

        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state shouldBe landingState

        machine.updateFlightMode(ArduCopterFlightMode.GUIDED)
        val before = machine.state

        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.state shouldBe expectedSyncState(before, FlyingTransition)

        val flyingState = machine.state
        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state shouldBe flyingState
    }
})
