package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_STATE
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.checkAll

/**
 * E-01 Widerspruchsfreiheit der veroeffentlichten Felder, Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Die telemetriebestimmten MAVLink-Felder stehen in keinem erreichbaren Zustand untereinander
 * im Widerspruch."
 *
 * Formal: Fuer alle s in S gilt mit (status, armed, landed) = π(view(s)):
 *   1. status = MAV_STATE_ACTIVE impliziert landed = MAV_LANDED_STATE_IN_AIR
 *   2. landed = MAV_LANDED_STATE_IN_AIR impliziert armed
 *
 * Umsetzung:
 *   - Geprueft wird jeder Zustand einer Ausfuehrung, nicht nur der Letzte. Jedes Praefix einer
 *     zulaessigen Ereignisfolge ist selbst zulaessig, also liegt jeder Zwischenzustand in S.
 *   - Je Regel ein Test, damit das Shrinking Gegenbeispiele verschiedener Ursachen nicht
 *     vermischt.
 *   - Eine Implikation mit falscher Praemisse gilt trivial. Die Statistik weist deshalb je
 *     Regel aus, in welchem Anteil der Ausfuehrungen die Praemisse ueberhaupt eintritt.
 *   - Ein Gegenbeispiel kann mit einem Kommando enden. Kommandos sind nicht Pruefgegenstand,
 *     die Spur zeigt aber, welche Transition den Zustand erzeugt hat. Loest auch sync diese
 *     Transition aus, liegt die Ursache im Pruefgegenstand (Abschnitt 1).
 */
class FieldConsistencySpec :
    StringSpec({

        "E-01 Regel 1: MAV_STATE_ACTIVE nur in der Luft" {
            checkAll(catalogConfig, eventSequence) { events ->
                val execution = Execution.reach(events)
                val views = execution.projectedViews()

                val premiseReached = views.any { reportsActive(it) }
                collect(if (premiseReached) "ACTIVE erreicht" else "ACTIVE nie erreicht")

                withClue({ execution.describe() }) {
                    violations(views) { reportsActive(it) implies reportsInAir(it) }.shouldBeEmpty()
                }
            }
        }

        "E-01 Regel 2: in der Luft nur mit gesetztem Arm-Bit" {
            checkAll(catalogConfig, eventSequence) { events ->
                val execution = Execution.reach(events)
                val views = execution.projectedViews()

                val premiseReached = views.any { reportsInAir(it) }
                collect(if (premiseReached) "IN_AIR erreicht" else "IN_AIR nie erreicht")

                withClue({ execution.describe() }) {
                    violations(views) { reportsInAir(it) implies it.safetyArmed }.shouldBeEmpty()
                }
            }
        }
    })

/**
 * π(view(s)) fuer jeden Zustand der Ausfuehrung. Index 0 ist der Startzustand, Index i der
 * Zustand nach Schritt i der Spur aus [Execution.describe].
 */
private fun Execution.projectedViews(): List<ProjectedView> {
    val states = listOf(initial) + trace.map { it.after }
    return states.map { project(view(it)) }
}

/** Die Zustaende, die eine Regel verletzen, mit ihrer Position in der Spur. */
private fun violations(views: List<ProjectedView>, rule: (ProjectedView) -> Boolean): List<String> =
    views.withIndex().filterNot { rule(it.value) }.map { "Zustand ${it.index}: ${it.value}" }

private fun reportsActive(view: ProjectedView): Boolean =
    view.systemStatus == MAV_STATE.MAV_STATE_ACTIVE

private fun reportsInAir(view: ProjectedView): Boolean =
    view.landedState == MAV_LANDED_STATE.MAV_LANDED_STATE_IN_AIR

private infix fun Boolean.implies(other: Boolean): Boolean = !this || other
