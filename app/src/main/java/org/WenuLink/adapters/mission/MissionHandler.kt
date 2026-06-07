package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import com.MAVLink.enums.MISSION_STATE
import dji.sdk.mission.timeline.actions.MissionAction
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.commands.CommandHandler
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult
import org.WenuLink.mavlink.messages.ConditionYawMessage
import org.WenuLink.mavlink.messages.ImageStartCaptureMessage
import org.WenuLink.mavlink.messages.NavDelayMessage
import org.WenuLink.mavlink.messages.NavTakeoffMissionItem
import org.WenuLink.mavlink.messages.NavWaypointMissionItem
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

data class MissionState(
    val mavlink: Int = MISSION_STATE.MISSION_STATE_UNKNOWN,
    val id: Int = 202512,
    val startSequence: Int = 1,
    val currentSequence: Int? = null,
    val assembler: MissionAssembler? = null,
    val unvisitedSequence: Boolean = true,
    val isComplete: Boolean = false
) {
    // Report the next sequence as target
    val targetSequence: Int get() = currentSequence?.let { it + 1 } ?: 0

    val currentMissionSize: Int get() = assembler?.size() ?: 0

    val isActive: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    val isPaused: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_PAUSED

    val canUploadMission: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_NO_MISSION && assembler != null

    val canStartMission: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_NOT_STARTED

    val mustProcessSequence: Boolean get() = isActive && unvisitedSequence

    val itemTime: Long get() = if (assembler != null) {
        System.currentTimeMillis() - assembler.timestamp
    } else {
        0
    }

    fun createMission(id: Int): MissionState = copy(assembler = MissionAssembler(id))

    fun setStartSequence(sequence: Int): MissionState =
        copy(startSequence = sequence, isComplete = false)

    fun updateItemSequence(sequence: Int?): MissionState =
        copy(currentSequence = sequence, unvisitedSequence = true)

    fun setComplete(): MissionState = copy(isComplete = true)

    fun markVisited(): MissionState = copy(unvisitedSequence = false)

    fun fromMissionManager(): MissionState = copy(
        mavlink = when {
            isComplete -> MISSION_STATE.MISSION_STATE_COMPLETE
            MissionManager.isWaitingMission() -> MISSION_STATE.MISSION_STATE_NO_MISSION
            MissionManager.isMissionReady() -> MISSION_STATE.MISSION_STATE_NOT_STARTED
            MissionManager.isMissionStarted() -> MISSION_STATE.MISSION_STATE_ACTIVE
            MissionManager.isMissionPaused() -> MISSION_STATE.MISSION_STATE_PAUSED
            else -> MISSION_STATE.MISSION_STATE_UNKNOWN
        }
    )

    fun reset(): MissionState = copy(
        startSequence = 1,
        currentSequence = null,
        isComplete = false,
        unvisitedSequence = true,
        assembler = null
    ).fromMissionManager()

    fun clearWaypoints() {
        assembler?.reset()
        if (!canUploadMission) MissionManager.clearMission()
    }
}

class MissionHandler : CommandHandler<MissionHandler>() {
    companion object {
        private var mInstance: MissionHandler? = null

        @Synchronized
        fun getInstance(): MissionHandler {
            if (mInstance == null) {
                mInstance = MissionHandler()
            }
            return mInstance!!
        }
    }

    private val logger by taggedLogger(MissionHandler::class.java.simpleName)
    private var lastActionKey: MissionActionManager.ActionCallbackKey? = null
    var flightSpeed = 5f
        private set
    var state = MissionState()
        private set
    // TODO MissionItem timeout guard

    override fun registerScope(scope: CoroutineScope) {
        MissionManager.addListeners { index ->
            scope.launch { setCurrentSequence(index) }
        }
        startCommandProcessor(scope, this@MissionHandler, logger)
    }

    override fun unload() {
        MissionManager.removeListener()
        super.unload()
    }

    @Synchronized
    fun syncState() {
        state = state.fromMissionManager()
    }

    @Synchronized
    fun processNode() {
        if (!state.unvisitedSequence) return

        logger.d { "Processing WP ${state.currentSequence}" }
        // Wait for AUTO mode on first waypoint
        if (state.currentSequence == 1) {
            dispatchCommand(PauseWaypointMission) { result ->
                if (result.hasError) logger.w { "PauseWaypoint error: $result" }
            }
        }

        state = state.markVisited()
    }

    @Synchronized
    fun setStartSequence(sequence: Int): MissionState {
        state = state.reset().setStartSequence(sequence)
        return state
    }

    @Synchronized
    fun setCurrentSequence(sequence: Int): MissionState {
        state = if (sequence == -1) { // final sequence
            state.updateItemSequence(null).setComplete().markVisited()
        } else {
            state.updateItemSequence(sequence + 1) // from 0- to 1-index
        }
        return state
    }

    fun setSpeed(speed: Float) {
        val range = -15f..15f
        flightSpeed = speed.coerceIn(range)
        if (speed !in range) logger.w { "Clipped speed $speed to [$range]" }
    }

    fun resetState() {
        state = state.reset()
    }

    fun clear() {
        resetState()
        state.clearWaypoints()
        MissionActionManager.clearScheduleAndListeners()
    }

    /**
     * MissionManager methods
     */

    fun createWaypointMission() {
        if (!state.canUploadMission) {
            // reset previous uploaded elements
            logger.i { "Removing previous incomplete upload" }
            state.clearWaypoints()
        }
        state = state.createMission(202606)
    }

    fun processItem(itemMsg: msg_mission_item_int): Boolean {
        if (!state.canUploadMission) logger.w { "Processing item without mission assembler" }
        return state.assembler?.addWaypointNode(itemMsg) ?: false
    }

    fun getWaypointNode(index: Int): MissionNode? = state.assembler?.getNode(index)

    fun hasWaypointNodes(): Boolean = state.assembler?.hasNodes() ?: false

    fun uploadWaypoints(onResult: (UnitResult) -> Unit) = // TODO: retry if no success
        state.assembler?.let { dispatchCommand(UploadMissionCommand(it, flightSpeed), onResult) }

    suspend fun waitMissionStart(timeout: Long = 300_000L): Boolean =
        AsyncUtils.waitTimeout(500L, timeout) { state.currentSequence != null }

    /**
     * MissionActionManager methods
     */

    fun teardownActions() = lastActionKey?.let { MissionActionManager.removeCallback(it) }

    fun scheduleImmediateAction(action: MissionAction): UnitResult {
        logger.d { "Scheduling $action" }
        MissionActionManager.clearScheduleAndListeners()
        return MissionActionManager.schedule(action)
            ?.let { CommandResult.error("Error in $action: ${it.description}") }
            ?: CommandResult.ok
    }

    fun onActionFinish(action: MissionAction, onFinish: () -> Unit) {
        lastActionKey = MissionActionManager.onFinish(action::class) {
            onFinish()
            stopAction("Action finished")
        }
    }

    fun performAction(onError: (String) -> Unit) {
        logger.i { "Timeline start" }
        // start listeners and action
        MissionActionManager.startListener(onError)
        MissionActionManager.start()
    }

    fun stopAction(description: String) {
        logger.i { "Timeline stop: $description" }
        if (MissionActionManager.isRunning) MissionActionManager.stop()
        teardownActions()
    }
}
