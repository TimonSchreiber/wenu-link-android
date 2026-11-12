package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** Erzeugt eine Zustandsmaschine und führt die übergebenen Transitionen der Reihe nach aus. */
private fun machineAfter(vararg transitions: StateTransition): AircraftStateMachine {
    val machine = AircraftStateMachine()
    transitions.forEach { machine.dispatch(it) }
    return machine
}

private fun standbyDisarmed() = machineAfter(StandbyTransition)

private fun armedOnGround() = machineAfter(StandbyTransition, ArmTransition)

private fun takingOff() = machineAfter(StandbyTransition, ArmTransition, TakeoffTransition)

private fun flying() =
    machineAfter(StandbyTransition, ArmTransition, TakeoffTransition, FlyingTransition)

private fun landingInMode(mode: ArduCopterFlightMode) = machineAfter(
    StandbyTransition,
    ArmTransition,
    TakeoffTransition,
    FlyingTransition,
    FlightModeTransition(mode),
    LandTransition
)

private fun Int.hasArmedFlag(): Boolean =
    (this and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) != 0

class AircraftStateMachineSyncTest : StringSpec({

    // ---------------------------------------------------------------------
    // Ausgangszustand
    // ---------------------------------------------------------------------

    "Initialzustand der Zustandsmaschine ist uninitialisiert, unbewaffnet und undefiniert gelandet" {
        val machine = AircraftStateMachine()

        machine.state.isUninitialized().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
        machine.state.flightMode shouldBe ArduCopterFlightMode.STABILIZE
        machine.state.homeCoordinates.shouldBeNull()
    }

    // ---------------------------------------------------------------------
    // Disarmed -> Standby
    // ---------------------------------------------------------------------

    "sync: disarmed und nicht fliegend aus dem Initialzustand liefert StandbyTransition" {
        val machine = AircraftStateMachine()

        val result = machine.sync(isArmed = false, isFlying = false)

        result shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeFalse()
    }

    "sync: disarmed aber fliegend (laut Telemetrie) liefert dennoch StandbyTransition" {
        val machine = AircraftStateMachine()

        val result = machine.sync(isArmed = false, isFlying = true)

        result shouldBe StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync: disarmed im Standby liefert wiederholt StandbyTransition und ändert den Zustand nicht" {
        val machine = standbyDisarmed()
        val before = machine.state

        val first = machine.sync(isArmed = false, isFlying = false)
        val second = machine.sync(isArmed = false, isFlying = false)

        first shouldBe StandbyTransition
        second shouldBe StandbyTransition
        machine.state shouldBe before
    }

    "sync: Disarm während des Fluges liefert StandbyTransition und setzt auf Boden/unbewaffnet" {
        val machine = flying()

        val result = machine.sync(isArmed = false, isFlying = true)

        result shouldBe StandbyTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeFalse()
    }

    "sync: Disarm am Boden nach dem Flug liefert StandbyTransition" {
        val machine = flying()
        machine.dispatch(LandTransition)
        machine.dispatch(TouchdownTransition)
        machine.state.isArmed().shouldBeTrue()

        val result = machine.sync(isArmed = false, isFlying = false)

        result shouldBe StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync: Disarm während der Landung liefert StandbyTransition" {
        val machine = landingInMode(ArduCopterFlightMode.LAND)

        val result = machine.sync(isArmed = false, isFlying = true)

        result shouldBe StandbyTransition
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    // ---------------------------------------------------------------------
    // Armed -> Arm
    // ---------------------------------------------------------------------

    "sync: armed im Standby liefert ArmTransition und setzt armed" {
        val machine = standbyDisarmed()

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    }

    "sync: armed aus dem Initialzustand liefert ArmTransition ohne MAVLink-Zustand zu ändern" {
        val machine = AircraftStateMachine()

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_UNINIT
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED
    }

    "sync: armed und fliegend bei unbewaffnetem Zustand liefert zuerst ArmTransition (Arm hat Vorrang)" {
        val machine = standbyDisarmed()

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe ArmTransition
        machine.state.isArmed().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isTakingOff().shouldBeFalse()
    }

    "sync: zweiter Aufruf nach Arm mit fliegender Telemetrie liefert TakeoffTransition" {
        val machine = standbyDisarmed()
        machine.sync(isArmed = true, isFlying = true) shouldBe ArmTransition

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe TakeoffTransition
        machine.state.isTakingOff().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Armed am Boden -> Takeoff / null
    // ---------------------------------------------------------------------

    "sync: armed, fliegend und am Boden liefert TakeoffTransition" {
        val machine = armedOnGround()

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe TakeoffTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_TAKEOFF
        machine.state.isArmed().shouldBeTrue()
    }

    "sync: armed, nicht fliegend und am Boden liefert null und lässt den Zustand unverändert" {
        val machine = armedOnGround()
        val before = machine.state

        val result = machine.sync(isArmed = true, isFlying = false)

        result.shouldBeNull()
        machine.state shouldBe before
    }

    "sync: armed am Boden nach Touchdown mit fliegender Telemetrie liefert erneut TakeoffTransition" {
        val machine = flying()
        machine.dispatch(TouchdownTransition)
        machine.state.isOnTheGround().shouldBeTrue()

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe TakeoffTransition
        machine.state.isTakingOff().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Takeoff -> Flying / Touchdown
    // ---------------------------------------------------------------------

    "sync: armed, fliegend während des Starts liefert FlyingTransition" {
        val machine = takingOff()

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe FlyingTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.isFlying().shouldBeTrue()
    }

    "sync: armed, nicht fliegend während des Starts liefert TouchdownTransition" {
        val machine = takingOff()

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Flying -> null / Touchdown
    // ---------------------------------------------------------------------

    "sync: armed und fliegend im Flug liefert null und lässt den Zustand unverändert" {
        val machine = flying()
        val before = machine.state

        val result = machine.sync(isArmed = true, isFlying = true)

        result.shouldBeNull()
        machine.state shouldBe before
    }

    "sync: wiederholte Aufrufe im stabilen Flug liefern immer null" {
        val machine = flying()
        val before = machine.state

        repeat(3) {
            machine.sync(isArmed = true, isFlying = true).shouldBeNull()
        }

        machine.state shouldBe before
    }

    "sync: armed und nicht mehr fliegend im Flug liefert TouchdownTransition" {
        val machine = flying()

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeTrue()
    }

    "sync: Touchdown behält den Flugmodus bei" {
        val machine = flying()
        machine.dispatch(FlightModeTransition(ArduCopterFlightMode.GUIDED))

        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition

        machine.state.flightMode shouldBe ArduCopterFlightMode.GUIDED
    }

    // ---------------------------------------------------------------------
    // Landing
    // ---------------------------------------------------------------------

    "sync: Landung im LAND-Modus mit fliegender Telemetrie liefert null und bleibt LANDING" {
        val machine = landingInMode(ArduCopterFlightMode.LAND)
        val before = machine.state

        val result = machine.sync(isArmed = true, isFlying = true)

        result.shouldBeNull()
        machine.state shouldBe before
        machine.state.isLanding().shouldBeTrue()
    }

    "sync: Landung im RTL-Modus mit fliegender Telemetrie liefert null" {
        val machine = landingInMode(ArduCopterFlightMode.RTL)

        val result = machine.sync(isArmed = true, isFlying = true)

        result.shouldBeNull()
        machine.state.isLanding().shouldBeTrue()
    }

    "sync: Landung im AUTO-Modus mit fliegender Telemetrie liefert null" {
        val machine = landingInMode(ArduCopterFlightMode.AUTO)

        val result = machine.sync(isArmed = true, isFlying = true)

        result.shouldBeNull()
        machine.state.isLanding().shouldBeTrue()
    }

    "sync: Landung im SMART_RTL-Modus mit fliegender Telemetrie liefert null" {
        val machine = landingInMode(ArduCopterFlightMode.SMART_RTL)

        val result = machine.sync(isArmed = true, isFlying = true)

        result.shouldBeNull()
        machine.state.isLanding().shouldBeTrue()
    }

    "sync: Landung im STABILIZE-Modus (kann LANDING nicht melden) liefert FlyingTransition" {
        val machine = landingInMode(ArduCopterFlightMode.STABILIZE)
        machine.state.isLandingOutsideLandingMode().shouldBeTrue()

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe FlyingTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_ACTIVE
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
        machine.state.isLanding().shouldBeFalse()
    }

    "sync: Landung im GUIDED-Modus (kann LANDING nicht melden) liefert FlyingTransition" {
        val machine = landingInMode(ArduCopterFlightMode.GUIDED)

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe FlyingTransition
        machine.state.isFlying().shouldBeTrue()
        machine.state.flightMode shouldBe ArduCopterFlightMode.GUIDED
    }

    "sync: Landung im LOITER-Modus (kann LANDING nicht melden) liefert FlyingTransition" {
        val machine = landingInMode(ArduCopterFlightMode.LOITER)

        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe FlyingTransition
        machine.state.isFlying().shouldBeTrue()
    }

    "sync: Moduswechsel von LAND zu STABILIZE während der Landung führt beim nächsten sync zu FlyingTransition" {
        val machine = landingInMode(ArduCopterFlightMode.LAND)
        machine.sync(isArmed = true, isFlying = true).shouldBeNull()

        machine.updateFlightMode(ArduCopterFlightMode.STABILIZE)
        val result = machine.sync(isArmed = true, isFlying = true)

        result shouldBe FlyingTransition
        machine.state.isFlying().shouldBeTrue()
    }

    "sync: Landung im LAND-Modus und nicht mehr fliegend liefert TouchdownTransition" {
        val machine = landingInMode(ArduCopterFlightMode.LAND)

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe TouchdownTransition
        machine.state.mavlink shouldBe MAV_STATE.MAV_STATE_STANDBY
        machine.state.landed shouldBe MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
        machine.state.isArmed().shouldBeTrue()
    }

    "sync: Landung im STABILIZE-Modus und nicht mehr fliegend liefert TouchdownTransition" {
        val machine = landingInMode(ArduCopterFlightMode.STABILIZE)

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBe TouchdownTransition
        machine.state.isOnTheGround().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Mode-Flag (updateArmFlag) nach sync
    // ---------------------------------------------------------------------

    "sync: nach ArmTransition enthält modeFlag das SAFETY_ARMED-Flag" {
        val machine = standbyDisarmed()
        machine.state.modeFlag.hasArmedFlag().shouldBeFalse()

        machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition

        machine.state.modeFlag.hasArmedFlag().shouldBeTrue()
        machine.state.modeFlag shouldBe
            (ArduCopterFlightMode.STABILIZE.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
    }

    "sync: nach StandbyTransition enthält modeFlag kein SAFETY_ARMED-Flag mehr" {
        val machine = flying()
        machine.state.modeFlag.hasArmedFlag().shouldBeTrue()

        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition

        machine.state.modeFlag.hasArmedFlag().shouldBeFalse()
        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
    }

    "sync: modeFlag basiert auf dem aktuellen Flugmodus" {
        val machine = standbyDisarmed()
        machine.updateFlightMode(ArduCopterFlightMode.GUIDED)

        machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition

        machine.state.modeFlag shouldBe
            (ArduCopterFlightMode.GUIDED.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
        machine.state.modeFlag shouldNotBe
            (ArduCopterFlightMode.STABILIZE.baseMode or MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED)
    }

    "sync: aus dem Initialzustand wird modeFlag durch StandbyTransition auf den Basis-Modus gesetzt" {
        val machine = AircraftStateMachine()
        machine.state.modeFlag shouldBe MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED

        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition

        machine.state.modeFlag shouldBe ArduCopterFlightMode.STABILIZE.baseMode
    }

    // ---------------------------------------------------------------------
    // Erhalt anderer Attribute
    // ---------------------------------------------------------------------

    "sync: Home-Koordinaten bleiben über alle Transitionen erhalten" {
        val machine = AircraftStateMachine()
        val home = Coordinates3D(lat = 48.137154, long = 11.576124, alt = 519.0f)
        machine.updateHomePosition(home)

        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        machine.state.homeCoordinates shouldBe home

        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.state.homeCoordinates shouldBe home
        machine.state.isHomeSet().shouldBeTrue()
    }

    "sync: Flugmodus bleibt über Standby/Arm/Takeoff/Flying erhalten" {
        val machine = AircraftStateMachine()
        machine.updateFlightMode(ArduCopterFlightMode.AUTO)

        machine.sync(isArmed = false, isFlying = false)
        machine.sync(isArmed = true, isFlying = false)
        machine.sync(isArmed = true, isFlying = true)
        machine.sync(isArmed = true, isFlying = true)

        machine.state.flightMode shouldBe ArduCopterFlightMode.AUTO
        machine.state.isFlying().shouldBeTrue()
    }

    // ---------------------------------------------------------------------
    // Rückgabewert
    // ---------------------------------------------------------------------

    "sync: gibt genau die Transition zurück, die auch angewendet wurde" {
        val machine = standbyDisarmed()

        val result = machine.sync(isArmed = true, isFlying = false)

        result shouldBeSameInstanceAs ArmTransition
        machine.state shouldBe ArmTransition.reduce(StandbyTransition.reduce(AircraftState())).copy(
            modeFlag = ArduCopterFlightMode.STABILIZE.baseMode or
                MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED
        )
    }

    // ---------------------------------------------------------------------
    // Kompletter Lebenszyklus
    // ---------------------------------------------------------------------

    "sync: kompletter Flugzyklus von Boot bis Disarm" {
        val machine = AircraftStateMachine()

        // Flugcontroller meldet: disarmed, am Boden
        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()

        // Arm
        machine.sync(isArmed = true, isFlying = false) shouldBe ArmTransition
        machine.state.isArmed().shouldBeTrue()

        // Armed am Boden, noch kein Start
        machine.sync(isArmed = true, isFlying = false).shouldBeNull()

        // Start
        machine.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        machine.state.isTakingOff().shouldBeTrue()

        // Im Flug
        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.state.isFlying().shouldBeTrue()

        // Stabiler Flug
        machine.sync(isArmed = true, isFlying = true).shouldBeNull()

        // Landung einleiten (über explizite Transition, da Telemetrie LANDING nicht liefert)
        machine.updateFlightMode(ArduCopterFlightMode.LAND)
        machine.dispatch(LandTransition)
        machine.sync(isArmed = true, isFlying = true).shouldBeNull()
        machine.state.isLanding().shouldBeTrue()

        // Aufsetzen
        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeTrue()

        // Disarm
        machine.sync(isArmed = false, isFlying = false) shouldBe StandbyTransition
        machine.state.isStandBy().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
        machine.state.modeFlag.hasArmedFlag().shouldBeFalse()
        machine.state.flightMode shouldBe ArduCopterFlightMode.LAND
    }

    "sync: Abbruch des Starts durch Disarm führt zurück in Standby" {
        val machine = takingOff()

        machine.sync(isArmed = false, isFlying = true) shouldBe StandbyTransition

        machine.state.isStandBy().shouldBeTrue()
        machine.state.isOnTheGround().shouldBeTrue()
        machine.state.isArmed().shouldBeFalse()
    }

    "sync: Telemetrie-Flackern (fliegend / nicht fliegend) im Flug wechselt zwischen Touchdown und Takeoff" {
        val machine = flying()

        machine.sync(isArmed = true, isFlying = false) shouldBe TouchdownTransition
        machine.state.isOnTheGround().shouldBeTrue()

        machine.sync(isArmed = true, isFlying = true) shouldBe TakeoffTransition
        machine.state.isTakingOff().shouldBeTrue()

        machine.sync(isArmed = true, isFlying = true) shouldBe FlyingTransition
        machine.state.isFlying().shouldBeTrue()
    }
})
