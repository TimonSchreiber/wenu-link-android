package org.WenuLink.adapters.mission

import org.WenuLink.adapters.aircraft.Coordinates3D

sealed class MissionNode(
    val coordinates3D: Coordinates3D,
    val actions: MutableList<MissionActionCommand> = mutableListOf()
) {
    /**
     * ArduPilot home item (seq 0). Reference data only, never a flight target.
     * Note: unlike all other nodes, [coordinates3D].alt is AMSL, not relative.
     */
    class Home(homeCoordinates3D: Coordinates3D) : MissionNode(homeCoordinates3D)
    class Takeoff(takeoffCoordinates3D: Coordinates3D) : MissionNode(takeoffCoordinates3D)
    class Land(landCoordinates3D: Coordinates3D) : MissionNode(landCoordinates3D)
    class Waypoint(wpCoordinates3D: Coordinates3D) : MissionNode(wpCoordinates3D)
}
