package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AircraftStateMachineSyncTest : StringSpec({

    "sync advances to ArmTransition when telemetry reports armed and aircraft is disarmed" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.state.isArmed() shouldBe false

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
        (fsm.state.modeFlag and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) shouldBe MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
    }

    "sync advances to TakeoffTransition when armed, on the ground and telemetry reports flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.isArmed() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe TakeoffTransition
        fsm.state.isTakingOff() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
    }

    "sync advances from taking off to FlyingTransition when armed and telemetry reports flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(TakeoffTransition)
        fsm.state.isTakingOff() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
    }

    "sync advances to FlyingTransition when landing outside landing mode and telemetry reports flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.updateFlightMode(ArduCopterFlightMode.STABILIZE)
        fsm.dispatch(LandTransition)

        fsm.state.isLandingOutsideLandingMode() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true
    }

    "sync returns null when landing in a valid landing mode and telemetry reports flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.updateFlightMode(ArduCopterFlightMode.LAND)
        fsm.dispatch(LandTransition)

        fsm.state.isLandingOutsideLandingMode() shouldBe false

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe null
        fsm.state.isLanding() shouldBe true
    }

    "sync advances to TouchdownTransition when armed, not flying and not on the ground" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.state.isFlying() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        fsm.state.isArmed() shouldBe true
    }

    "sync advances to StandbyTransition when telemetry reports disarmed while on the ground" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.state.isArmed() shouldBe true

        val transition = fsm.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.isStandBy() shouldBe true
        (fsm.state.modeFlag and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) shouldBe 0
    }

    "sync advances to StandbyTransition when telemetry reports disarmed while flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.state.isFlying() shouldBe true

        val transition = fsm.sync(isArmed = false, isFlying = true)

        transition shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.isStandBy() shouldBe true
    }

    "sync returns null when state is armed, on the ground and telemetry confirms armed and not flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe null
        fsm.state.isArmed() shouldBe true
        fsm.state.isOnTheGround() shouldBe true
    }

    "sync returns null when state is armed, flying and telemetry confirms armed and flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(TakeoffTransition)
        fsm.dispatch(FlyingTransition)

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition shouldBe null
        fsm.state.isArmed() shouldBe true
        fsm.state.isFlying() shouldBe true
    }

    "sync correctly handles a full nominal flight lifecycle" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)

        // 1. Telemetry signals arming
        val armTransition = fsm.sync(isArmed = true, isFlying = false)
        armTransition shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
        fsm.state.isOnTheGround() shouldBe true

        // 2. Telemetry signals liftoff / takeoff
        val takeoffTransition = fsm.sync(isArmed = true, isFlying = true)
        takeoffTransition shouldBe TakeoffTransition
        fsm.state.isTakingOff() shouldBe true

        // 3. Telemetry signals in-air flight
        val flyingTransition = fsm.sync(isArmed = true, isFlying = true)
        flyingTransition shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true

        // 4. Steady flight telemetry changes nothing
        val steadyFlightTransition = fsm.sync(isArmed = true, isFlying = true)
        steadyFlightTransition shouldBe null
        fsm.state.isFlying() shouldBe true

        // 5. Telemetry signals touchdown
        val touchdownTransition = fsm.sync(isArmed = true, isFlying = false)
        touchdownTransition shouldBe TouchdownTransition
        fsm.state.isOnTheGround() shouldBe true

        // 6. Telemetry signals disarm
        val disarmTransition = fsm.sync(isArmed = false, isFlying = false)
        disarmTransition shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
    }
})
