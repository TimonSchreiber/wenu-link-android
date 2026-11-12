package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AircraftStateMachineSyncTest : StringSpec({

    "sync schaltet auf StandbyTransition um, wenn isArmed false ist" {
        val fsm = AircraftStateMachine()

        val transition = fsm.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        fsm.state.isArmed() shouldBe false
    }

    "sync schaltet auch aus der Luft auf StandbyTransition um, wenn disarmed gemeldet wird" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false) // Standby
        fsm.sync(isArmed = true, isFlying = false)  // Arm
        fsm.sync(isArmed = true, isFlying = true)   // Takeoff
        fsm.sync(isArmed = true, isFlying = true)   // Flying

        val transition = fsm.sync(isArmed = false, isFlying = true)

        transition shouldBe StandbyTransition
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        fsm.state.isArmed() shouldBe false
    }

    "sync schaltet auf ArmTransition um, wenn isArmed true und System noch disarmed ist" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false) // Standby auf dem Boden

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
        fsm.state.isOnTheGround() shouldBe true
        (fsm.state.modeFlag and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) shouldBe MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
    }

    "sync schaltet auf TakeoffTransition um, wenn armed, fliegend und noch auf dem Boden" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false) // Standby
        fsm.sync(isArmed = true, isFlying = false)  // Armed

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe TakeoffTransition
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        fsm.state.isTakingOff() shouldBe true
    }

    "sync schaltet auf FlyingTransition um, wenn armed, fliegend und zuvor im Takeoff" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false) // Standby
        fsm.sync(isArmed = true, isFlying = false)  // Armed
        fsm.sync(isArmed = true, isFlying = true)   // Takeoff

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        fsm.state.isFlying() shouldBe true
    }

    "sync schaltet auf FlyingTransition um, wenn Landung ausserhalb eines Landemodus gemeldet wird" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false)
        fsm.sync(isArmed = true, isFlying = false)
        fsm.sync(isArmed = true, isFlying = true)
        fsm.sync(isArmed = true, isFlying = true) // Fliegend

        fsm.updateFlightMode(ArduCopterFlightMode.STABILIZE) // canReportLanding == false
        fsm.dispatch(LandTransition) // landed = LANDING

        fsm.state.isLandingOutsideLandingMode() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    }

    "sync schaltet auf TouchdownTransition um, wenn armed, nicht mehr fliegend und nicht am Boden" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false)
        fsm.sync(isArmed = true, isFlying = false)
        fsm.sync(isArmed = true, isFlying = true)
        fsm.sync(isArmed = true, isFlying = true) // Fliegend

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        fsm.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        fsm.state.isOnTheGround() shouldBe true
    }

    "sync liefert null, wenn bereits im Flug und weiterhin fliegend" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false)
        fsm.sync(isArmed = true, isFlying = false)
        fsm.sync(isArmed = true, isFlying = true)
        fsm.sync(isArmed = true, isFlying = true) // Flying

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        fsm.state.isFlying() shouldBe true
    }

    "sync liefert null, wenn armed und bereits am Boden und nicht fliegend" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false)
        fsm.sync(isArmed = true, isFlying = false) // Armed on ground

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition.shouldBeNull()
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.isArmed() shouldBe true
    }

    "sync liefert null, wenn in einem echten Landemodus gelandet wird" {
        val fsm = AircraftStateMachine()
        fsm.sync(isArmed = false, isFlying = false)
        fsm.sync(isArmed = true, isFlying = false)
        fsm.sync(isArmed = true, isFlying = true)
        fsm.sync(isArmed = true, isFlying = true) // Flying

        fsm.updateFlightMode(ArduCopterFlightMode.LAND) // canReportLanding == true
        fsm.dispatch(LandTransition)

        fsm.state.isLandingOutsideLandingMode() shouldBe false

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        fsm.state.isLanding() shouldBe true
    }

    "vollstaendiger Flugzyklus ueber sync durchlaufen" {
        val fsm = AircraftStateMachine()

        // 1. Initialisieren / Disarmed am Boden
        fsm.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.isArmed() shouldBe false

        // 2. Armen
        fsm.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
        fsm.state.isOnTheGround() shouldBe true

        // 3. Startlauf / Takeoff
        fsm.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        fsm.state.isTakingOff() shouldBe true

        // 4. Im Flug
        fsm.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true

        // 5. Weiterhin stabiler Flug -> keine Transition
        fsm.sync(isArmed = true, isFlying = true).shouldBeNull()
        fsm.state.isFlying() shouldBe true

        // 6. Aufsetzen / Touchdown
        fsm.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        fsm.state.isOnTheGround() shouldBe true

        // 7. Disarm am Boden
        fsm.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
    }
})
