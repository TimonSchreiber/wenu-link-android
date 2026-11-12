package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Hilfsfunktionen, um die Zustandsmaschine deterministisch in definierte Zustände zu bringen.
 * Es werden ausschließlich `sync()` bzw. die öffentlichen `dispatch`/`updateFlightMode`-Methoden
 * verwendet.
 */
private fun machineInitial(): AircraftStateMachine = AircraftStateMachine()

private fun machineDisarmedOnGround(): AircraftStateMachine =
    machineInitial().apply { sync(isArmed = false, isFlying = false) }

private fun machineArmedOnGround(): AircraftStateMachine =
    machineDisarmedOnGround().apply { sync(isArmed = true, isFlying = false) }

private fun machineTakingOff(): AircraftStateMachine =
    machineArmedOnGround().apply { sync(isArmed = true, isFlying = true) }

private fun machineFlying(): AircraftStateMachine =
    machineTakingOff().apply { sync(isArmed = true, isFlying = true) }

private fun machineLandingIn(mode: ArduCopterFlightMode): AircraftStateMachine =
    machineFlying().apply {
        updateFlightMode(mode)
        dispatch(LandTransition)
    }

private fun Int.hasArmedFlag(): Boolean =
    (this and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) != 0

class AircraftStateMachineSyncTest : StringSpec({

    // ------------------------------------------------------------------
    // Vorbedingungen der Hilfsfunktionen (damit die Folgetests belastbar sind)
    // ------------------------------------------------------------------

    "initial state is uninitialized, undefined landed state and disarmed" {
        val machine = machineInitial()

        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_UNINIT
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
        machine.state.armed shouldBe false
        machine.state.flightMode shouldBe ArduCopterFlightMode.STABILIZE
        machine.state.homeCoordinates shouldBe null
    }

    "helper: flying machine is armed, active and in the air" {
        val machine = machineFlying()

        machine.state.armed shouldBe true
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    }

    "helper: landing machine reports LANDING in the requested flight mode" {
        val machine = machineLandingIn(ArduCopterFlightMode.LAND)

        machine.state.armed shouldBe true
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
        machine.state.flightMode shouldBe ArduCopterFlightMode.LAND
        machine.state.isLandingOutsideLandingMode() shouldBe false
    }

    // ------------------------------------------------------------------
    // Disarmed -> Standby
    // ------------------------------------------------------------------

    "sync disarmed and not flying from initial state returns StandbyTransition" {
        val machine = machineInitial()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
    }

    "sync disarmed from initial state sets mode flag to base mode without armed flag" {
        val machine = machineInitial()

        machine.sync(isArmed = false, isFlying = false)

        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
        machine.state.modeFlag.hasArmedFlag() shouldBe false
    }

    "sync disarmed but flying from initial state still returns StandbyTransition" {
        val machine = machineInitial()

        val transition = machine.sync(isArmed = false, isFlying = true)

        transition shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
    }

    "sync disarmed on already standby state returns StandbyTransition again" {
        val machine = machineDisarmedOnGround()
        val before = machine.state

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state shouldBe before
    }

    "sync disarmed while flying returns StandbyTransition and puts aircraft on the ground" {
        val machine = machineFlying()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
        machine.state.modeFlag.hasArmedFlag() shouldBe false
    }

    "sync disarmed while flying telemetry still reports flying returns StandbyTransition" {
        val machine = machineFlying()

        val transition = machine.sync(isArmed = false, isFlying = true)

        transition shouldBe StandbyTransition
        machine.state.armed shouldBe false
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    "sync disarmed while taking off returns StandbyTransition" {
        val machine = machineTakingOff()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
    }

    "sync disarmed while landing in LAND mode returns StandbyTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.LAND)

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
        machine.state.flightMode shouldBe ArduCopterFlightMode.LAND
    }

    "sync disarmed while armed on ground returns StandbyTransition and disarms" {
        val machine = machineArmedOnGround()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        machine.state.armed shouldBe false
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.modeFlag.hasArmedFlag() shouldBe false
    }

    // ------------------------------------------------------------------
    // Arming
    // ------------------------------------------------------------------

    "sync armed from disarmed standby returns ArmTransition" {
        val machine = machineDisarmedOnGround()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        machine.state.armed shouldBe true
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    "sync armed sets the SAFETY_ARMED flag on the mode flag" {
        val machine = machineDisarmedOnGround()

        machine.sync(isArmed = true, isFlying = false)

        machine.state.modeFlag shouldBe
            (ArduCopterFlightMode.STABILIZE.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
        machine.state.modeFlag.hasArmedFlag() shouldBe true
    }

    "sync armed from initial state returns ArmTransition without changing mavlink or landed state" {
        val machine = machineInitial()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        machine.state.armed shouldBe true
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_UNINIT
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
    }

    "sync armed and flying on a disarmed state arms first before taking off" {
        val machine = machineDisarmedOnGround()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe ArmTransition
        machine.state.armed shouldBe true
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
    }

    "sync armed on a disarmed in-air state has arming precedence over everything else" {
        val machine = machineInitial().apply { dispatch(FlyingTransition) }
        machine.state.armed shouldBe false
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe ArmTransition
        machine.state.armed shouldBe true
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    }

    "sync armed on a disarmed in-air state with not flying telemetry arms instead of touching down" {
        val machine = machineInitial().apply { dispatch(FlyingTransition) }

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        machine.state.armed shouldBe true
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    }

    // ------------------------------------------------------------------
    // Takeoff
    // ------------------------------------------------------------------

    "sync armed and flying from armed on ground returns TakeoffTransition" {
        val machine = machineArmedOnGround()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe TakeoffTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        machine.state.armed shouldBe true
        machine.state.modeFlag.hasArmedFlag() shouldBe true
    }

    "sync armed and not flying on armed on ground state returns null" {
        val machine = machineArmedOnGround()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe null
        machine.state shouldBe before
    }

    // ------------------------------------------------------------------
    // Flying
    // ------------------------------------------------------------------

    "sync armed and flying from taking off returns FlyingTransition" {
        val machine = machineTakingOff()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.armed shouldBe true
    }

    "sync armed and flying while already flying returns null and keeps state" {
        val machine = machineFlying()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe null
        machine.state shouldBe before
    }

    "repeated sync armed and flying while flying stays stable" {
        val machine = machineFlying()
        val before = machine.state

        repeat(5) {
            machine.sync(isArmed = true, isFlying = true) shouldBe null
        }

        machine.state shouldBe before
    }

    // ------------------------------------------------------------------
    // Landing-Reconciliation
    // ------------------------------------------------------------------

    "sync armed and flying while landing in STABILIZE returns FlyingTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.STABILIZE)
        machine.state.isLandingOutsideLandingMode() shouldBe true

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.armed shouldBe true
        machine.state.flightMode shouldBe ArduCopterFlightMode.STABILIZE
    }

    "sync armed and flying while landing in LOITER returns FlyingTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.LOITER)

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.flightMode shouldBe ArduCopterFlightMode.LOITER
    }

    "sync armed and flying while landing in GUIDED returns FlyingTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.GUIDED)

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    }

    "sync armed and flying while landing in LAND mode returns null and keeps LANDING" {
        val machine = machineLandingIn(ArduCopterFlightMode.LAND)
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe null
        machine.state shouldBe before
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
    }

    "sync armed and flying while landing in RTL mode returns null" {
        val machine = machineLandingIn(ArduCopterFlightMode.RTL)

        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
    }

    "sync armed and flying while landing in AUTO mode returns null" {
        val machine = machineLandingIn(ArduCopterFlightMode.AUTO)

        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
    }

    "sync armed and flying while landing in SMART_RTL mode returns null" {
        val machine = machineLandingIn(ArduCopterFlightMode.SMART_RTL)

        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING
    }

    // ------------------------------------------------------------------
    // Touchdown
    // ------------------------------------------------------------------

    "sync armed and not flying while flying returns TouchdownTransition" {
        val machine = machineFlying()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe true
        machine.state.modeFlag.hasArmedFlag() shouldBe true
    }

    "sync armed and not flying while taking off returns TouchdownTransition" {
        val machine = machineTakingOff()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe true
    }

    "sync armed and not flying while landing in LAND mode returns TouchdownTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.LAND)

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.armed shouldBe true
        machine.state.flightMode shouldBe ArduCopterFlightMode.LAND
    }

    "sync armed and not flying while landing in STABILIZE returns TouchdownTransition" {
        val machine = machineLandingIn(ArduCopterFlightMode.STABILIZE)

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    "sync armed and not flying from armed initial state returns TouchdownTransition" {
        val machine = machineInitial().apply { sync(isArmed = true, isFlying = false) }
        machine.state.armed shouldBe true
        machine.state.isOnTheGround() shouldBe false

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    // ------------------------------------------------------------------
    // Erhalt anderer Attribute
    // ------------------------------------------------------------------

    "sync keeps the home position" {
        val home = Coordinates3D(lat = 48.137154, long = 11.576124, alt = 520.5f)
        val machine = machineInitial().apply { updateHomePosition(home) }

        machine.sync(isArmed = false, isFlying = false)
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = false)
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = true)
        machine.state.homeCoordinates shouldBe home
        machine.state.isHomeSet() shouldBe true
    }

    "sync keeps the flight mode and composes mode flag from its base mode" {
        val machine = machineDisarmedOnGround().apply {
            updateFlightMode(ArduCopterFlightMode.GUIDED)
        }

        machine.sync(isArmed = true, isFlying = false)

        machine.state.flightMode shouldBe ArduCopterFlightMode.GUIDED
        machine.state.modeFlag shouldBe
            (ArduCopterFlightMode.GUIDED.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
    }

    "sync disarmed removes armed flag but keeps the flight mode base mode" {
        val machine = machineFlying().apply { updateFlightMode(ArduCopterFlightMode.AUTO) }
        machine.state.modeFlag.hasArmedFlag() shouldBe true

        machine.sync(isArmed = false, isFlying = false)

        machine.state.flightMode shouldBe ArduCopterFlightMode.AUTO
        machine.state.modeFlag shouldBe ArduCopterFlightMode.AUTO.baseMode
        machine.state.modeFlag.hasArmedFlag() shouldBe false
    }

    "sync with null transition does not touch the mode flag" {
        val machine = machineFlying()
        val flagBefore = machine.state.modeFlag

        machine.sync(isArmed = true, isFlying = true) shouldBe null

        machine.state.modeFlag shouldBe flagBefore
    }

    // ------------------------------------------------------------------
    // Vollständiger Lebenszyklus
    // ------------------------------------------------------------------

    "full flight lifecycle via sync produces the expected transition sequence" {
        val machine = machineInitial()
        val transitions = mutableListOf<StateTransition?>()

        transitions += machine.sync(isArmed = false, isFlying = false) // boot -> standby
        transitions += machine.sync(isArmed = true, isFlying = false)  // arm
        transitions += machine.sync(isArmed = true, isFlying = false)  // nothing
        transitions += machine.sync(isArmed = true, isFlying = true)   // takeoff
        transitions += machine.sync(isArmed = true, isFlying = true)   // flying
        transitions += machine.sync(isArmed = true, isFlying = true)   // nothing
        transitions += machine.sync(isArmed = true, isFlying = false)  // touchdown
        transitions += machine.sync(isArmed = true, isFlying = false)  // nothing
        transitions += machine.sync(isArmed = false, isFlying = false) // disarm -> standby

        transitions shouldBe listOf(
            StandbyTransition,
            ArmTransition,
            null,
            TakeoffTransition,
            FlyingTransition,
            null,
            TouchdownTransition,
            null,
            StandbyTransition
        )

        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.armed shouldBe false
        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
    }

    "second flight after touchdown and re-arm works via sync" {
        val machine = machineFlying()

        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
        machine.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.sync(isArmed = true, isFlying = true) shouldBe null

        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.armed shouldBe true
    }

    "landing abort in a non-landing mode followed by real landing in LAND mode" {
        val machine = machineLandingIn(ArduCopterFlightMode.STABILIZE)

        // ArduCopter cannot report LANDING in STABILIZE -> back in the air
        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

        // switch to LAND and start landing
        machine.updateFlightMode(ArduCopterFlightMode.LAND)
        machine.dispatch(LandTransition)
        machine.sync(isArmed = true, isFlying = true) shouldBe null
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_LANDING

        // land_complete
        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.armed shouldBe true

        // disarm
        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.state.armed shouldBe false
        machine.state.modeFlag shouldNotBe
            (ArduCopterFlightMode.LAND.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
        machine.state.modeFlag shouldBe ArduCopterFlightMode.LAND.baseMode
    }

    "sync returned transition is the one that was applied to the state" {
        val machine = machineArmedOnGround()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBe TakeoffTransition
        machine.state.copy(modeFlag = before.modeFlag) shouldBe
            TakeoffTransition.reduce(before).copy(modeFlag = before.modeFlag)
    }
})
