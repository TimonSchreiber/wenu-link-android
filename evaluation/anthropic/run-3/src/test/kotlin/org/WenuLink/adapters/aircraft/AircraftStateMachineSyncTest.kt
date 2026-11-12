package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class AircraftStateMachineSyncTest : StringSpec({

    // ---------------------------------------------------------------------
    // Helpers to build deterministic starting states via the public API
    // ---------------------------------------------------------------------

    fun standby(): AircraftStateMachine = AircraftStateMachine().apply {
        sync(isArmed = false, isFlying = false)
    }

    fun armedOnGround(): AircraftStateMachine = standby().apply {
        sync(isArmed = true, isFlying = false)
    }

    fun takingOff(): AircraftStateMachine = armedOnGround().apply {
        sync(isArmed = true, isFlying = true)
    }

    fun flying(): AircraftStateMachine = takingOff().apply {
        sync(isArmed = true, isFlying = true)
    }

    fun landing(mode: ArduCopterFlightMode = ArduCopterFlightMode.STABILIZE): AircraftStateMachine =
        flying().apply {
            updateFlightMode(mode)
            dispatch(LandTransition)
        }

    val armedFlag = MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED

    // ---------------------------------------------------------------------
    // Initial state sanity
    // ---------------------------------------------------------------------

    "fresh state machine starts uninitialized, disarmed and with undefined landed state" {
        val machine = AircraftStateMachine()

        machine.state.isUninitialized().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
        machine.state.flightMode shouldBe ArduCopterFlightMode.STABILIZE
        machine.state.isHomeSet().shouldBeFalse()
    }

    // ---------------------------------------------------------------------
    // Disarmed -> Standby
    // ---------------------------------------------------------------------

    "sync disarmed on a fresh machine returns StandbyTransition and moves to standby on the ground" {
        val machine = AircraftStateMachine()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync disarmed on a fresh machine sets modeFlag to the flight mode base mode without armed flag" {
        val machine = AircraftStateMachine()

        machine.sync(isArmed = false, isFlying = false)

        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
        (machine.state.modeFlag and armedFlag) shouldBe 0
    }

    "sync disarmed while already in standby returns StandbyTransition and keeps the state unchanged" {
        val machine = standby()
        val before = machine.state

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state shouldBe before
    }

    "sync disarmed while flying returns StandbyTransition and lands the aircraft" {
        val machine = flying()
        machine.state.isFlying().shouldBeTrue()

        val transition = machine.sync(isArmed = false, isFlying = true)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeFalse()
    }

    "sync disarmed and not flying while flying returns StandbyTransition" {
        val machine = flying()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync disarmed while taking off returns StandbyTransition" {
        val machine = takingOff()

        val transition = machine.sync(isArmed = false, isFlying = true)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync disarmed while landing returns StandbyTransition" {
        val machine = landing()

        val transition = machine.sync(isArmed = false, isFlying = true)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync disarmed removes the armed flag from modeFlag" {
        val machine = flying()
        (machine.state.modeFlag and armedFlag) shouldBe armedFlag

        machine.sync(isArmed = false, isFlying = false)

        (machine.state.modeFlag and armedFlag) shouldBe 0
        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
    }

    // ---------------------------------------------------------------------
    // Armed -> Arm
    // ---------------------------------------------------------------------

    "sync armed on a disarmed standby machine returns ArmTransition and arms the aircraft" {
        val machine = standby()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBeSameInstanceAs ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    "sync armed sets the safety armed flag in modeFlag" {
        val machine = standby()

        machine.sync(isArmed = true, isFlying = false)

        machine.state.modeFlag shouldBe (ArduCopterFlightMode.STABILIZE.baseMode or armedFlag)
    }

    "sync armed and flying on a disarmed machine first returns ArmTransition" {
        val machine = standby()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
    }

    "sync armed on a fresh uninitialized machine returns ArmTransition without touching mavlink or landed state" {
        val machine = AircraftStateMachine()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBeSameInstanceAs ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_UNINIT
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
    }

    "sync armed and not flying while armed on the ground returns null and keeps the state" {
        val machine = armedOnGround()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    // ---------------------------------------------------------------------
    // Armed + flying on ground -> Takeoff
    // ---------------------------------------------------------------------

    "sync armed and flying while armed on the ground returns TakeoffTransition" {
        val machine = armedOnGround()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs TakeoffTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        machine.state.isTakingOff().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()
    }

    "sync armed and flying with undefined landed state returns null" {
        val machine = AircraftStateMachine()
        machine.sync(isArmed = true, isFlying = false) // ArmTransition, landed stays UNDEFINED
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    // ---------------------------------------------------------------------
    // Taking off -> Flying
    // ---------------------------------------------------------------------

    "sync armed and flying while taking off returns FlyingTransition" {
        val machine = takingOff()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs FlyingTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.isFlying().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()
    }

    "sync armed and flying while already flying returns null and keeps the state" {
        val machine = flying()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    // ---------------------------------------------------------------------
    // Landing outside landing mode -> Flying
    // ---------------------------------------------------------------------

    "sync armed and flying while landing in STABILIZE returns FlyingTransition" {
        val machine = landing(ArduCopterFlightMode.STABILIZE)
        machine.state.isLanding().shouldBeTrue()
        machine.state.isLandingOutsideLandingMode().shouldBeTrue()

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs FlyingTransition
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.flightMode shouldBe ArduCopterFlightMode.STABILIZE
    }

    "sync armed and flying while landing in LOITER returns FlyingTransition" {
        val machine = landing(ArduCopterFlightMode.LOITER)

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs FlyingTransition
        machine.state.isFlying().shouldBeTrue()
        machine.state.flightMode shouldBe ArduCopterFlightMode.LOITER
    }

    "sync armed and flying while landing in GUIDED returns FlyingTransition" {
        val machine = landing(ArduCopterFlightMode.GUIDED)

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition shouldBeSameInstanceAs FlyingTransition
        machine.state.isFlying().shouldBeTrue()
    }

    "sync armed and flying while landing in LAND returns null and keeps landing" {
        val machine = landing(ArduCopterFlightMode.LAND)
        machine.state.isLandingOutsideLandingMode().shouldBeFalse()
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
        machine.state.isLanding().shouldBeTrue()
    }

    "sync armed and flying while landing in RTL returns null and keeps landing" {
        val machine = landing(ArduCopterFlightMode.RTL)
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    "sync armed and flying while landing in AUTO returns null and keeps landing" {
        val machine = landing(ArduCopterFlightMode.AUTO)
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    "sync armed and flying while landing in SMART_RTL returns null and keeps landing" {
        val machine = landing(ArduCopterFlightMode.SMART_RTL)
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    // ---------------------------------------------------------------------
    // Armed + not flying in the air -> Touchdown
    // ---------------------------------------------------------------------

    "sync armed and not flying while flying returns TouchdownTransition" {
        val machine = flying()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBeSameInstanceAs TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeTrue()
    }

    "sync armed and not flying while landing returns TouchdownTransition" {
        val machine = landing(ArduCopterFlightMode.LAND)

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBeSameInstanceAs TouchdownTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()
    }

    "sync armed and not flying while taking off returns TouchdownTransition" {
        val machine = takingOff()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition shouldBeSameInstanceAs TouchdownTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
    }

    "sync armed and not flying after touchdown keeps the armed flag in modeFlag" {
        val machine = flying()

        machine.sync(isArmed = true, isFlying = false)

        machine.state.modeFlag shouldBe (ArduCopterFlightMode.STABILIZE.baseMode or armedFlag)
    }

    "sync armed and not flying after touchdown returns null on the second call" {
        val machine = flying()
        machine.sync(isArmed = true, isFlying = false)
        val before = machine.state

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition.shouldBeNull()
        machine.state shouldBe before
    }

    "sync disarmed after touchdown returns StandbyTransition and disarms" {
        val machine = flying()
        machine.sync(isArmed = true, isFlying = false)
        machine.state.isArmed().shouldBeTrue()

        val transition = machine.sync(isArmed = false, isFlying = false)

        transition shouldBeSameInstanceAs StandbyTransition
        machine.state.isArmed().shouldBeFalse()
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Preservation of unrelated state fields
    // ---------------------------------------------------------------------

    "sync preserves the home coordinates" {
        val machine = AircraftStateMachine()
        val home = Coordinates3D(lat = 48.137154, long = 11.576124, alt = 520.5f)
        machine.updateHomePosition(home)

        machine.sync(isArmed = false, isFlying = false)
        machine.sync(isArmed = true, isFlying = false)
        machine.sync(isArmed = true, isFlying = true)
        machine.sync(isArmed = true, isFlying = true)

        machine.state.homeCoordinates shouldBe home
        machine.state.isHomeSet().shouldBeTrue()
    }

    "sync preserves the flight mode" {
        val machine = standby()
        machine.updateFlightMode(ArduCopterFlightMode.GUIDED)

        machine.sync(isArmed = true, isFlying = false)
        machine.sync(isArmed = true, isFlying = true)
        machine.sync(isArmed = true, isFlying = true)
        machine.sync(isArmed = false, isFlying = false)

        machine.state.flightMode shouldBe ArduCopterFlightMode.GUIDED
    }

    "sync uses the base mode of the current flight mode for modeFlag" {
        val machine = standby()
        machine.updateFlightMode(ArduCopterFlightMode.GUIDED)

        machine.sync(isArmed = true, isFlying = false)
        machine.state.modeFlag shouldBe (ArduCopterFlightMode.GUIDED.baseMode or armedFlag)

        machine.sync(isArmed = false, isFlying = false)
        machine.state.modeFlag shouldBe ArduCopterFlightMode.GUIDED.baseMode
        machine.state.modeFlag shouldNotBe ArduCopterFlightMode.STABILIZE.baseMode
    }

    "sync returns the same instance that is applied to the state" {
        val machine = standby()

        val transition = machine.sync(isArmed = true, isFlying = false)

        transition.shouldNotBeNull()
        val expected = transition.reduce(standby().state)
        machine.state.armed shouldBe expected.armed
        machine.state.mavlink shouldBe expected.mavlink
        machine.state.landed shouldBe expected.landed
    }

    // ---------------------------------------------------------------------
    // Full flight cycle
    // ---------------------------------------------------------------------

    "sync drives a complete flight cycle from boot to standby" {
        val machine = AircraftStateMachine()

        machine.sync(isArmed = false, isFlying = false) shouldBeSameInstanceAs StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()

        machine.sync(isArmed = true, isFlying = false) shouldBeSameInstanceAs ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()

        machine.sync(isArmed = true, isFlying = false).shouldBeNull()

        machine.sync(isArmed = true, isFlying = true) shouldBeSameInstanceAs TakeoffTransition
        machine.state.isTakingOff().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE

        machine.sync(isArmed = true, isFlying = true) shouldBeSameInstanceAs FlyingTransition
        machine.state.isFlying().shouldBeTrue()

        machine.sync(isArmed = true, isFlying = true).shouldBeNull()

        machine.updateFlightMode(ArduCopterFlightMode.LAND)
        machine.dispatch(LandTransition)
        machine.state.isLanding().shouldBeTrue()

        machine.sync(isArmed = true, isFlying = true).shouldBeNull()

        machine.sync(isArmed = true, isFlying = false) shouldBeSameInstanceAs TouchdownTransition
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()

        machine.sync(isArmed = false, isFlying = false) shouldBeSameInstanceAs StandbyTransition
        machine.state.isArmed().shouldBeFalse()
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.modeFlag shouldBe ArduCopterFlightMode.LAND.baseMode
    }

    "sync can re-arm and take off again after a completed flight" {
        val machine = flying()
        machine.sync(isArmed = true, isFlying = false) // touchdown
        machine.sync(isArmed = false, isFlying = false) // standby

        machine.sync(isArmed = true, isFlying = false) shouldBeSameInstanceAs ArmTransition
        machine.sync(isArmed = true, isFlying = true) shouldBeSameInstanceAs TakeoffTransition
        machine.sync(isArmed = true, isFlying = true) shouldBeSameInstanceAs FlyingTransition

        machine.state.isFlying().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
    }
})
