package org.WenuLink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.getstream.log.taggedLogger
import org.WenuLink.adapters.aircraft.AircraftHandler
import org.WenuLink.adapters.aircraft.DisarmCommand
import org.WenuLink.sdk.FCManager

/**
 * Experiment trigger: fires disarm requests via adb broadcast instead of the GCS UI.
 * force=true  -> direct SDK call, bypasses the state machine (like MAVLink force=21196)
 * force=false -> regular DisarmCommand path, including validation
 * Not intended for merging.
 */
object DisarmTrigger {
    const val ACTION = "org.WenuLink.debug.DISARM"
    private val logger by taggedLogger("DisarmTrigger")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val force = intent.getBooleanExtra("force", false)
            DisarmProbe.logEvent("TRIGGER_RECEIVED force=$force")

            if (force) {
                DisarmProbe.logEvent("DISARM_SDK_CALL path=force")
                FCManager.disarmMotors { error ->
                    DisarmProbe.logEvent("DISARM_SDK_RESULT path=force error=${error ?: "none"}")
                }
            } else {
                val command = DisarmCommand()
                AircraftHandler.getInstance().dispatchCommand(command) { result ->
                    DisarmProbe.logEvent(
                        "COMMAND_RESULT cmd=${command::class.simpleName} " +
                            "ok=${result.isOk} reason=${result.errorReason}"
                    )
                }
            }
        }
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        logger.i { "DisarmTrigger registered" }
    }
}
