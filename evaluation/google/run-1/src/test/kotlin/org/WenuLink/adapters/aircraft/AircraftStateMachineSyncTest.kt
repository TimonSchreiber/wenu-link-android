package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AircraftStateMachineSyncTest : StringSpec({

    "sync should trigger ArmTransition when telemetry reports armed but state is disarmed" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.state.isArmed() shouldBe false

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
    }

    "sync should trigger TakeoffTransition when armed, flying reported and vehicle is on the ground" {
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

    "sync should trigger FlyingTransition when armed, flying reported and vehicle is taking off" {
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

    "sync should return null when vehicle is already armed and flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        val previousState = fsm.state

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        fsm.state shouldBe previousState
    }

    "sync should trigger FlyingTransition when landing in a flight mode that cannot report landing" {
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

    "sync should return null when vehicle is landing in a mode that can report landing and telemetry reports flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.updateFlightMode(ArduCopterFlightMode.LAND)
        fsm.dispatch(LandTransition)

        fsm.state.isLandingOutsideLandingMode() shouldBe false

        val transition = fsm.sync(isArmed = true, isFlying = true)

        transition.shouldBeNull()
        fsm.state.isLanding() shouldBe true
    }

    "sync should trigger TouchdownTransition when armed, not flying and currently in air" {
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

    "sync should trigger TouchdownTransition when armed, not flying and currently taking off" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(TakeoffTransition)
        fsm.state.isTakingOff() shouldBe true

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition shouldBe TouchdownTransition
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
    }

    "sync should return null when armed, not flying and already on the ground" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.state.isOnTheGround() shouldBe true
        val previousState = fsm.state

        val transition = fsm.sync(isArmed = true, isFlying = false)

        transition.shouldBeNull()
        fsm.state shouldBe previousState
    }

    "sync should trigger StandbyTransition when telemetry reports disarmed while flying" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)
        fsm.dispatch(FlyingTransition)
        fsm.state.isArmed() shouldBe true

        val transition = fsm.sync(isArmed = false, isFlying = true)

        transition shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
    }

    "sync should trigger StandbyTransition when telemetry reports disarmed on the ground" {
        val fsm = AircraftStateMachine()
        fsm.dispatch(StandbyTransition)
        fsm.dispatch(ArmTransition)

        val transition = fsm.sync(isArmed = false, isFlying = false)

        transition shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
        fsm.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
    }

    "sync should handle a complete lifecycle through sequential telemetry updates" {
        val fsm = AircraftStateMachine()

        // 1. Initial sync to standby
        fsm.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true

        // 2. Armed signal received
        fsm.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
        fsm.state.isArmed() shouldBe true
        fsm.state.isOnTheGround() shouldBe true

        // 3. Takeoff detected
        fsm.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        fsm.state.isTakingOff() shouldBe true

        // 4. In air detected
        fsm.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        fsm.state.isFlying() shouldBe true

        // 5. In air stable (no transition)
        fsm.sync(isArmed = true, isFlying = true).shouldBeNull()
        fsm.state.isFlying() shouldBe true

        // 6. Touchdown detected
        fsm.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        fsm.state.isOnTheGround() shouldBe true

        // 7. Disarm signal received
        fsm.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        fsm.state.isArmed() shouldBe false
        fsm.state.isOnTheGround() shouldBe true
    }
})
