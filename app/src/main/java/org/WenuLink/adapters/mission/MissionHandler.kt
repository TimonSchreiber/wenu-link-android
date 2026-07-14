package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MISSION_STATE
import dji.sdk.mission.timeline.actions.MissionAction
import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.WenuLink.adapters.AsyncUtils
import org.WenuLink.commands.CommandHandler
import org.WenuLink.commands.CommandResult
import org.WenuLink.commands.UnitResult
import org.WenuLink.sdk.MissionActionManager
import org.WenuLink.sdk.MissionManager

data class MissionState(
    val mavlink: Int = MISSION_STATE.MISSION_STATE_UNKNOWN,
    val id: Int = 100,
    val startSequence: Int = 1,
    val currentSequence: Int? = null,
    val assembler: MissionAssembler? = null,
    val unvisitedSequence: Boolean = true,
    val isComplete: Boolean = false,
    val isUploading: Boolean = false,
    val isReceivingItems: Boolean = false,
    val uploadReady: Boolean = false
) {
    // Report the next sequence as target
    val targetSequence: Int get() = currentSequence?.let { it + 1 } ?: 0

    val currentMissionSize: Int get() = assembler?.size() ?: 0

    val hasAssembler: Boolean get() = assembler != null

    val isActive: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_ACTIVE

    val isPaused: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_PAUSED

    val canUploadMission: Boolean get() = mavlink == MISSION_STATE.MISSION_STATE_NO_MISSION

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

    fun setUploading(isUploading: Boolean): MissionState = copy(isUploading = isUploading)

    fun setReceivingItems(isReceivingItems: Boolean): MissionState =
        copy(isReceivingItems = isReceivingItems)

    fun setUploadReady(uploadReady: Boolean): MissionState = copy(uploadReady = uploadReady)

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
        unvisitedSequence = true
    ).fromMissionManager()

    fun resetAssembler(): MissionState = copy(assembler = null)
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
    fun syncState(): MissionState {
        state = state.fromMissionManager()
        return state
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

    @Synchronized
    fun setUploadingMission(isUploading: Boolean) {
        logger.d { "Uploading mission: $isUploading" }
        state = state.setUploading(isUploading)
    }

    @Synchronized
    fun setReceivingItems(isReceiving: Boolean) {
        logger.d { "Processing mission: $isReceiving (${MissionManager.currentState})" }
        state = state.setReceivingItems(isReceiving)
    }

    fun setSpeed(speed: Float) {
        val range = -15f..15f
        flightSpeed = speed.coerceIn(range)
        if (speed !in range) logger.w { "Clipped speed $speed to [$range]" }
    }

    @Synchronized
    fun resetState() {
        logger.d { "Reset mission state" }
        state = state.reset()
    }

    fun clear() {
        logger.d { "Full mission clear ${MissionManager.currentState}" }
        resetWaypoints()
        resetActions()
    }

    /**
     * MissionManager methods
     */
    fun resetWaypoints() {
        logger.d { "Clearing mission elements" }
        MissionManager.clearMission()
    }

    @Synchronized
    fun createWaypointMission(missionId: Int = 100): UnitResult {
        if (state.isReceivingItems) return UnitResult.error("Other mission process is ongoing")

        if (state.hasAssembler) {
            logger.d { "Clearing previous mission assembler" }
            state = state.resetAssembler()
            state = state.setUploadReady(false)
        }
        logger.d { "Adding new mission id $missionId" }
        state = state.createMission(missionId)

        setReceivingItems(true)
        return UnitResult.ok
    }

    fun missionUploadReady(): UnitResult {
        if (!state.isReceivingItems) return UnitResult.error("No mission to upload")
        setReceivingItems(false)
        // TODO: validates mission items
        state = state.setUploadReady(true)
        return UnitResult.ok
    }

    fun processItem(itemMsg: msg_mission_item_int): ItemAssemblyResult {
        val assembler = state.assembler
        if (assembler == null) {
            logger.w { "processItem called without assembler, item ${itemMsg.command} dropped" }
            return ItemAssemblyResult.NoActiveMission
        }
        return assembler.addWaypointNode(itemMsg)
    }

    fun getWaypointNode(index: Int): MissionNode? = state.assembler?.getNode(index)

    fun hasWaypointNodes(): Boolean = state.assembler?.hasNodes() ?: false

    suspend fun waitMissionStart(timeout: Long = 10_000L): Boolean =
        AsyncUtils.waitTimeout(200L, timeout) { state.isActive }

    suspend fun waitMissionPause(timeout: Long = 10_000L): Boolean =
        AsyncUtils.waitTimeout(200L, timeout) { state.isPaused }

    suspend fun waitInitialWaypoint(timeout: Long = 300_000L): Boolean =
        AsyncUtils.waitTimeout(500L, timeout) { state.currentSequence != null }

    suspend fun waitMissionComplete(timeout: Long = 10_000L): Boolean =
        AsyncUtils.waitTimeout(200L, timeout) { state.isComplete }

    /**
     * MissionActionManager methods
     */

    fun teardownActions() = lastActionKey?.let { MissionActionManager.removeCallback(it) }

    fun resetActions() {
        logger.i { "Clearing mission actions" }
        MissionActionManager.clearScheduleAndListeners()
    }

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
