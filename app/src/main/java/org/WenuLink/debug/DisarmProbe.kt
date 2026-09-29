package org.WenuLink.debug

import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.simulator.SimulatorState
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.AircraftState

/**
 * Experiment instrumentation for the disarm-in-flight test.
 * Emits one key=value line per event under a single tag for logcat filtering.
 * Not intended for merging.
 */
object DisarmProbe {
    private val logger by taggedLogger("DisarmProbe")

    fun logFc(state: FlightControllerState) {
        logger.i {
            "src=FC t=${System.currentTimeMillis()} " +
                "motors=${state.areMotorsOn()} flying=${state.isFlying} " +
                "alt=${state.aircraftLocation.altitude} mode=${state.flightMode}"
        }
    }

    fun logSim(state: SimulatorState) {
        logger.i {
            "src=SIM t=${System.currentTimeMillis()} " +
                "motors=${state.areMotorsOn()} flying=${state.isFlying} " +
                "posZ=${state.positionZ}"
        }
    }

    fun logSync(
        hasData: Boolean,
        isArmed: Boolean,
        isFlying: Boolean,
        before: AircraftState,
        after: AircraftState
    ) {
        logger.i {
            "src=SYNC t=${System.currentTimeMillis()} hasData=$hasData " +
                "in=($isArmed,$isFlying) " +
                "before=(mavlink=${before.mavlink},landed=${before.landed}," +
                "modeFlag=${before.modeFlag},armTs=${before.armTimestamp}) " +
                "after=(mavlink=${after.mavlink},landed=${after.landed}," +
                "modeFlag=${after.modeFlag},armTs=${after.armTimestamp})"
        }
    }

    fun logEvent(event: String) {
        logger.w { "src=EVENT t=${System.currentTimeMillis()} $event" }
    }
}
