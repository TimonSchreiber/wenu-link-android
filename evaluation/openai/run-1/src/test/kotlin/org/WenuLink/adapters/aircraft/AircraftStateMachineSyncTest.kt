package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun aircraftMachine(vararg transitions: StateTransition): AircraftStateMachine =
    AircraftStateMachine().apply {
        transitions.forEach { dispatch(it) }
    }

private fun AircraftStateMachine.expectSync(
    isArmed: Boolean,
    isFlying: Boolean,
    expectedTransition: StateTransition?,
    expectedState: AircraftState
) {
    sync(isArmed = isArmed, isFlying = isFlying) shouldBe expectedTransition
    state shouldBe expectedState
}

class AircraftStateMachineSyncTest : StringSpec({

    "gleichzeitiges Armed- und Flying-Signal führt pro Aufruf nur einen Übergang aus" {
        val machine = aircraftMachine(StandbyTransition)
        val initial = machine.state
        val armed = initial.copy(
            armed = true,
            modeFlag = initial.flightMode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        )
        val takingOff = armed.copy(
            mavlink = MAV_STATE.MAV_STATE_ACTIVE,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        )
        val flying = takingOff.copy(
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        )

        machine.expectSync(true, true, ArmTransition, armed)
        machine.expectSync(true, true, TakeoffTransition, takingOff)
        machine.expectSync(true, true, FlyingTransition, flying)
        machine.expectSync(true, true, null, flying)
    }

    "armed am Boden ohne Flying-Signal bleibt unverändert" {
        val machine = aircraftMachine(StandbyTransition, ArmTransition)
        val before = machine.state

        repeat(2) {
            machine.expectSync(true, false, null, before)
        }
    }

    for (telemetryFlying in listOf(false, true)) {
        "ArmTransition hat im Initialzustand Vorrang bei isFlying=$telemetryFlying" {
            val machine = AircraftStateMachine()
            val before = machine.state

            machine.canDispatch(ArmTransition).hasError shouldBe true

            machine.expectSync(
                isArmed = true,
                isFlying = telemetryFlying,
                expectedTransition = ArmTransition,
                expectedState = before.copy(
                    armed = true,
                    modeFlag = before.flightMode.baseMode or
                        MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
                )
            )
        }
    }

    "armed mit undefiniertem Landestatus und Flying-Signal wird nicht automatisch fliegend" {
        val machine = aircraftMachine(ArmTransition)
        val before = machine.state

        machine.expectSync(true, true, null, before)
    }

    "armed mit undefiniertem Landestatus ohne Flying-Signal führt zum Touchdown" {
        val machine = aircraftMachine(ArmTransition)
        val before = machine.state
        val grounded = before.copy(
            mavlink = MAV_STATE.MAV_STATE_STANDBY,
            landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        )

        machine.expectSync(true, false, TouchdownTransition, grounded)
        machine.expectSync(true, false, null, grounded)
    }

    "ArmTransition hat auch bei bereits fliegendem Landestatus Vorrang" {
        // dispatch validiert canTransition absichtlich nicht.
        val machine = aircraftMachine(FlyingTransition)
        val before = machine.state
        val armed = before.copy(
            armed = true,
            modeFlag = before.flightMode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        )

        machine.expectSync(true, true, ArmTransition, armed)
        machine.expectSync(true, true, null, armed)
    }

    "Disarm während des Fluges dispatcht Standby trotz negativer Vorbedingung" {
        val machine = aircraftMachine(
            StandbyTransition,
            ArmTransition,
            TakeoffTransition,
            FlyingTransition
        )
        val before = machine.state

        machine.canDispatch(StandbyTransition).hasError shouldBe true

        machine.expectSync(
            isArmed = false,
            isFlying = true,
            expectedTransition = StandbyTransition,
            expectedState = before.copy(
                mavlink = MAV_STATE.MAV_STATE_STANDBY,
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND,
                armed = false,
                modeFlag = before.flightMode.baseMode
            )
        )
    }

    val disarmSetups = listOf(
        "Initialzustand" to emptyList<StateTransition>(),
        "Boot" to listOf(BootTransition),
        "Standby" to listOf(StandbyTransition),
        "Armed" to listOf(StandbyTransition, ArmTransition),
        "Takeoff" to listOf(StandbyTransition, ArmTransition, TakeoffTransition),
        "Flying" to listOf(StandbyTransition, ArmTransition, FlyingTransition),
        "Landing" to listOf(
            StandbyTransition, ArmTransition, FlyingTransition, LandTransition
        ),
        "FlightTermination" to listOf(
            StandbyTransition, ArmTransition, FlyingTransition, FlightTerminationTransition
        ),
        "PowerOff" to listOf(StandbyTransition, PowerOffTransition)
    )

    for ((name, transitions) in disarmSetups) {
        for (telemetryFlying in listOf(false, true)) {
            "Disarm aus $name mit isFlying=$telemetryFlying liefert immer StandbyTransition" {
                val machine = aircraftMachine(*transitions.toTypedArray())
                val before = machine.state
                val standby = before.copy(
                    mavlink = MAV_STATE.MAV_STATE_STANDBY,
                    landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND,
                    armed = false,
                    modeFlag = before.flightMode.baseMode
                )

                machine.expectSync(false, telemetryFlying, StandbyTransition, standby)
                machine.expectSync(false, telemetryFlying, StandbyTransition, standby)
            }
        }
    }

    val touchdownSetups = listOf(
        "Takeoff" to listOf(StandbyTransition, ArmTransition, TakeoffTransition),
        "Flying" to listOf(StandbyTransition, ArmTransition, FlyingTransition),
        "Landing" to listOf(
            StandbyTransition, ArmTransition, FlyingTransition, LandTransition
        ),
        "FlightTermination" to listOf(
            StandbyTransition, ArmTransition, FlyingTransition, FlightTerminationTransition
        )
    )

    for ((name, transitions) in touchdownSetups) {
        "fehlendes Flying-Signal aus $name führt zum Touchdown ohne Disarm" {
            val machine = aircraftMachine(*transitions.toTypedArray())
            val before = machine.state
            val grounded = before.copy(
                mavlink = MAV_STATE.MAV_STATE_STANDBY,
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
            )

            machine.expectSync(true, false, TouchdownTransition, grounded)
            machine.expectSync(true, false, null, grounded)
        }
    }

    val landingReportingModes = setOf(
        ArduCopterFlightMode.AUTO,
        ArduCopterFlightMode.RTL,
        ArduCopterFlightMode.LAND,
        ArduCopterFlightMode.SMART_RTL
    )

    for (mode in ArduCopterFlightMode.entries) {
        "Landing bei Flying-Signal in $mode wird entsprechend dem Flugmodus synchronisiert" {
            val machine = aircraftMachine(
                StandbyTransition,
                ArmTransition,
                FlyingTransition,
                FlightModeTransition(mode),
                LandTransition
            )
            val before = machine.state

            if (mode in landingReportingModes) {
                machine.expectSync(true, true, null, before)
                machine.expectSync(true, true, null, before)
            } else {
                val flying = before.copy(
                    mavlink = MAV_STATE.MAV_STATE_ACTIVE,
                    landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
                )
                machine.expectSync(true, true, FlyingTransition, flying)
                machine.expectSync(true, true, null, flying)
            }
        }

        "Landing ohne Flying-Signal in $mode führt unabhängig vom Flugmodus zum Touchdown" {
            val machine = aircraftMachine(
                StandbyTransition,
                ArmTransition,
                FlyingTransition,
                FlightModeTransition(mode),
                LandTransition
            )
            val before = machine.state

            machine.expectSync(
                isArmed = true,
                isFlying = false,
                expectedTransition = TouchdownTransition,
                expectedState = before.copy(
                    mavlink = MAV_STATE.MAV_STATE_STANDBY,
                    landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
                )
            )
        }

        "Synchronisation in $mode erhält Home und Flugmodus und aktualisiert die Armed-Flags" {
            val machine = aircraftMachine(
                StandbyTransition,
                FlightModeTransition(mode)
            )
            val home = Coordinates3D(lat = 48.125, long = 11.5, alt = 525.25f)
            machine.updateHomePosition(home)
            val standby = machine.state

            val armed = standby.copy(
                armed = true,
                modeFlag = mode.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
            )
            val takingOff = armed.copy(
                mavlink = MAV_STATE.MAV_STATE_ACTIVE,
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
            )
            val flying = takingOff.copy(
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
            )
            val grounded = armed.copy(
                mavlink = MAV_STATE.MAV_STATE_STANDBY,
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
            )

            machine.expectSync(true, false, ArmTransition, armed)
            machine.expectSync(true, true, TakeoffTransition, takingOff)
            machine.expectSync(true, true, FlyingTransition, flying)
            machine.expectSync(true, true, null, flying)
            machine.expectSync(true, false, TouchdownTransition, grounded)
            machine.expectSync(false, false, StandbyTransition, standby)
        }
    }

    "Wechsel von LAND zu STABILIZE beendet Landing erst beim nächsten Flying-Signal" {
        val machine = aircraftMachine(
            StandbyTransition,
            ArmTransition,
            FlyingTransition,
            FlightModeTransition(ArduCopterFlightMode.LAND),
            LandTransition
        )
        val landing = machine.state

        machine.expectSync(true, true, null, landing)

        machine.updateFlightMode(ArduCopterFlightMode.STABILIZE)
        val before = machine.state
        before.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING

        machine.expectSync(
            isArmed = true,
            isFlying = true,
            expectedTransition = FlyingTransition,
            expectedState = before.copy(
                mavlink = MAV_STATE.MAV_STATE_ACTIVE,
                landed = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
            )
        )
    }

    "Flying-Signal verändert einen bereits fliegenden FlightTermination-Zustand nicht" {
        val machine = aircraftMachine(
            StandbyTransition,
            ArmTransition,
            FlyingTransition,
            FlightTerminationTransition
        )
        val before = machine.state

        machine.expectSync(true, true, null, before)
    }
})
