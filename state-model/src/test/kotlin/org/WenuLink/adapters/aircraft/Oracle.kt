package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_MODE_FLAG
import com.MAVLink.enums.MAV_STATE

/*
 * Oracle for the property catalog v1.0 (state-model/docs/eigenschaftskatalog.md),
 * sections 2 and 3: the observable MAVLink view, its projection onto the
 * telemetry-determined fields, and the target mapping.
 *
 * Oracle rule: this file reads only the four published fields of AircraftState.
 * Internal predicates such as isArmed(), isFlying() or armRequested belong to the system
 * under test and must not appear here; otherwise they would be test-object and yardstick
 * at once. The target mapping is written out from the catalog, not derived from the model.
 */

// ---------------------------------------------------------------------------
// Section 2: view
// ---------------------------------------------------------------------------

/**
 * An element of V: the four fields the bridge publishes. ConnectionController.msgHeartbeat
 * fills HEARTBEAT (system_status, base_mode, custom_mode) and msgExtendedSys fills
 * EXTENDED_SYS_STATE (landed_state) from exactly the fields read by [view]. Narrowing to the
 * wire types happens there, outside the state model; the view keeps the values as the state
 * model holds them.
 */
data class MavlinkView(
    val systemStatus: Int,
    val baseMode: Int,
    val customMode: Long,
    val landedState: Int
) {
    /** The bit MAV_MODE_FLAG_SAFETY_ARMED of [baseMode]: a projection of the view, not a field. */
    val safetyArmed: Boolean
        get() = (baseMode and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED) != 0

    override fun toString(): String {
        val mode = ArduCopterFlightMode.entries.firstOrNull { it.mode == customMode }
        return "(systemStatus=${mavStateName(systemStatus)}, " +
            "baseMode=${modeFlagName(baseMode)}, " +
            "customMode=$customMode${mode?.let { " $it" } ?: ""}, " +
            "landedState=${landedStateName(landedState)})"
    }
}

/** view: S -> V, according to the table in section 2. */
fun view(state: AircraftState): MavlinkView = MavlinkView(
    systemStatus = state.mavlink,
    baseMode = state.modeFlag,
    customMode = state.flightMode.mode,
    landedState = state.landed
)

// ---------------------------------------------------------------------------
// Section 2: projection
// ---------------------------------------------------------------------------

/** An element of π(V): the telemetry-determined part of a view. */
data class ProjectedView(val systemStatus: Int, val safetyArmed: Boolean, val landedState: Int) {
    override fun toString(): String {
        val armed = if (safetyArmed) "armed" else "disarmed"
        return "(${mavStateName(systemStatus)}, $armed, ${landedStateName(landedState)})"
    }
}

/** π(v) = (v.systemStatus, v.safetyArmed, v.landedState). */
fun project(view: MavlinkView): ProjectedView = ProjectedView(
    systemStatus = view.systemStatus,
    safetyArmed = view.safetyArmed,
    landedState = view.landedState
)

// ---------------------------------------------------------------------------
// Section 3: target mapping
// ---------------------------------------------------------------------------

/**
 * The table of section 3, row by row. T_stable = T (A-01), so every element of T has a row.
 * Rows 1 and 4 map to the same view: the mapping is not injective by design.
 */
private val targetMapping: Map<Telemetry, ProjectedView> = mapOf(
    // Row 1: disarmed on the ground
    Telemetry(isArmed = false, isFlying = false) to ProjectedView(
        systemStatus = MAV_STATE.MAV_STATE_STANDBY,
        safetyArmed = false,
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    ),
    // Row 2: armed on the ground; STANDBY with the arm bit set is valid for ArduCopter
    Telemetry(isArmed = true, isFlying = false) to ProjectedView(
        systemStatus = MAV_STATE.MAV_STATE_STANDBY,
        safetyArmed = true,
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    ),
    // Row 3: armed in the air
    Telemetry(isArmed = true, isFlying = true) to ProjectedView(
        systemStatus = MAV_STATE.MAV_STATE_ACTIVE,
        safetyArmed = true,
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR
    ),
    // Row 4: disarmed in the air; ArduCopter sets land_complete on disarm, same view as row 1
    Telemetry(isArmed = false, isFlying = true) to ProjectedView(
        systemStatus = MAV_STATE.MAV_STATE_STANDBY,
        safetyArmed = false,
        landedState = MAV_LANDED_STATE.MAV_LANDED_STATE_ON_GROUND
    )
).also { mapping ->
    require(mapping.keys == Telemetry.all.toSet()) { "Target mapping must cover all of T" }
}

/** soll: T_stable -> π(V), according to the table in section 3. */
fun soll(t: Telemetry): ProjectedView = targetMapping.getValue(t)

// ---------------------------------------------------------------------------
// Names for readable counterexamples
// ---------------------------------------------------------------------------

/** Int constants of a generated MAVLink enum class by value, names without the class prefix. */
private fun mavlinkConstants(enumClass: Class<*>): Map<Int, String> {
    val prefix = enumClass.simpleName + "_"
    val constants = mutableMapOf<Int, String>()
    for (field in enumClass.fields) {
        val isStatic = java.lang.reflect.Modifier.isStatic(field.modifiers)
        val isInt = field.type == Int::class.javaPrimitiveType
        if (isStatic && isInt && !field.name.endsWith("_ENUM_END")) {
            constants[field.getInt(null)] = field.name.removePrefix(prefix)
        }
    }
    return constants
}

private val mavStateNames = mavlinkConstants(MAV_STATE::class.java)
private val landedStateNames = mavlinkConstants(MAV_LANDED_STATE::class.java)
private val modeFlagNames = mavlinkConstants(MAV_MODE_FLAG::class.java).toSortedMap()

internal fun mavStateName(value: Int): String = mavStateNames[value] ?: value.toString()

internal fun landedStateName(value: Int): String = landedStateNames[value] ?: value.toString()

internal fun modeFlagName(mask: Int): String {
    val set = mutableListOf<String>()
    for ((bit, name) in modeFlagNames) {
        if ((mask and bit) != 0) {
            set.add(name)
        }
    }
    return if (set.isEmpty()) "none" else set.joinToString("|")
}
