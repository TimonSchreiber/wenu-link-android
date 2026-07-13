package org.WenuLink.adapters.mission

import com.MAVLink.common.msg_mission_item_int
import com.MAVLink.enums.MAV_CMD
import org.WenuLink.adapters.aircraft.Coordinates3D
import org.WenuLink.mavlink.messages.ConditionYawMessage
import org.WenuLink.mavlink.messages.ImageStartCaptureMessage
import org.WenuLink.mavlink.messages.NavDelayMessage
import org.WenuLink.mavlink.messages.NavTakeoffMissionItem
import org.WenuLink.mavlink.messages.NavWaypointMissionItem

class MissionAssembler(private val id: Int) {
    private val nodes = mutableListOf<MissionNode>()
    private var rtlWhenFinish = false
    var timestamp = System.currentTimeMillis()
        private set
    var nWaypoints = 0
        private set

    fun getNode(nId: Int): MissionNode = nodes[nId]

    fun hasNodes() = !nodes.isEmpty()

    fun size(): Int = nodes.size

    fun reset() {
        nodes.clear()
        rtlWhenFinish = false
        nWaypoints = 0
    }

    fun addTakeoff(coordinates: Coordinates3D): Boolean =
        nodes.add(MissionNode.Takeoff(coordinates))

    fun addWaypoint(coordinates: Coordinates3D): Boolean =
        nodes.add(MissionNode.Waypoint(coordinates)).also {
            nWaypoints += 1
        }

    fun addActionToLast(missionAction: MissionActionCommand) {
        (nodes.lastOrNull() as? MissionNode.Waypoint)
            ?.actions
            ?.add(missionAction)
    }

    fun setRTLWhenFinish() {
        rtlWhenFinish = true
    }

    fun build(): AssembledMission = AssembledMission(nodes.toList(), nWaypoints, rtlWhenFinish)

    fun addWaypointNode(itemMsg: msg_mission_item_int): Boolean {
//        logger.d { "Append mission item." }
        when (itemMsg.command) {
            MAV_CMD.MAV_CMD_NAV_TAKEOFF -> assembleTakeoffNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_WAYPOINT -> assembleWaypointNode(itemMsg)

            MAV_CMD.MAV_CMD_NAV_DELAY,
            MAV_CMD.MAV_CMD_CONDITION_DELAY -> addActionToLast(
                DelayAction.fromParameters(NavDelayMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_CONDITION_YAW -> addActionToLast(
                RotateAction.fromParameters(ConditionYawMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_IMAGE_START_CAPTURE -> addActionToLast(
                PhotoAction.fromParameters(ImageStartCaptureMessage(itemMsg))
            )

            MAV_CMD.MAV_CMD_IMAGE_STOP_CAPTURE ->
                addActionToLast(StopPhotoAction)

            MAV_CMD.MAV_CMD_VIDEO_START_CAPTURE ->
                addActionToLast(VideoAction())

            MAV_CMD.MAV_CMD_VIDEO_STOP_CAPTURE ->
                addActionToLast(StopVideoAction)

            MAV_CMD.MAV_CMD_NAV_RETURN_TO_LAUNCH ->
                setRTLWhenFinish()

            else -> return false
        }
        return true
    }

    private fun assembleTakeoffNode(itemMsg: msg_mission_item_int) {
        val params = NavTakeoffMissionItem(itemMsg)
        addTakeoff(
            Coordinates3D(params.latitude, params.longitude, params.altitude)
        )
    }

    private fun assembleWaypointNode(itemMsg: msg_mission_item_int) {
        val params = NavWaypointMissionItem(itemMsg)
        // Assumes Global only
        val coordinates = Coordinates3D(params.latitude, params.longitude, params.altitude)

        // TODO: airframe check
//         val frameReference = itemMsg.frame.toInt()
//         // We only support the following frame models:
//         // 0 = Global (WGS84) coordinate frame + altitude relative to mean sea level (MSL).
//         // 3 = Global (WGS84) coordinate frame + altitude relative to the home position.
//         if (frameReference != 0 && frameReference != 3) {
//             logger.w { "frameReference: $frameReference is not available" }
//             sendAckAnswer(MAV_MISSION_RESULT.MAV_MISSION_UNSUPPORTED_FRAME)
//             return
//         }

        addWaypoint(coordinates)

        // Delay (seconds)
        if (params.holdTimeSec > 0f) {
            addActionToLast(DelayAction((params.holdTimeSec * 1000).toLong()))
        }

        // Yaw
        if (!params.yaw.isNaN()) {
            addActionToLast(RotateAction(params.yaw))
        }

//        logger.d { "Waypoint: ($coordinates) (Yaw=${params.yaw}°) (Delay=${params.holdTimeSec}s)" }
    }
}
