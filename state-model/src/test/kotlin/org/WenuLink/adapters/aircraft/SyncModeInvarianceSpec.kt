package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_MODE_FLAG
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

/**
 * E-06 Modeinvarianz des Abgleichs, Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Der Abgleich von Telemetrie veraendert den gemeldeten Flugmodus nicht."
 *
 * Formal: Fuer alle s in S und alle t in T gilt mit v = view(s) und v' = view(sync(s, t)):
 *   1. v'.customMode = v.customMode
 *   2. v'.baseMode ohne das Bit SAFETY_ARMED ist gleich v.baseMode ohne dieses Bit
 *   3. das Bit MAV_MODE_FLAG_CUSTOM_MODE_ENABLED ist in v'.baseMode gesetzt
 *
 * Umsetzung:
 *   - Je Regel und je t in T ein Test, aus denselben Gruenden wie bei E-01 und E-05.
 *   - Wie bei E-05 wird ein einzelner sync an die erzeugte Folge angehaengt.
 *   - Loest sync keine Transition aus, bleibt der Zustand unveraendert und die Regeln 1 und 2
 *     gelten trivial. Die Statistik weist diesen Anteil aus. Welche Transitionen im uebrigen
 *     Anteil vorkommen, zeigt die Statistik von E-05; Generator und letzter Schritt sind
 *     dieselben.
 *   - Regel 3 prueft nur v'. Fehlt das Bit schon in v, scheitert sie auch ohne Zutun von sync,
 *     etwa bei einer fehlerhaften Zuordnung von Flugmodus auf base_mode, die der Katalog in
 *     Abschnitt 1 ausdruecklich nicht erfasst. Ob das Bit vorher fehlte, zeigt die Spur.
 */
class SyncModeInvarianceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-06 Regel 1, Abgleich mit $t: customMode bleibt unveraendert" {
                checkModeInvariance(t) { before, after ->
                    after.customMode shouldBe before.customMode
                }
            }
        }

        for (t in Telemetry.all) {
            "E-06 Regel 2, Abgleich mit $t: baseMode ohne Arm-Bit bleibt unveraendert" {
                checkModeInvariance(t) { before, after ->
                    withoutArmBit(after.baseMode) shouldBe withoutArmBit(before.baseMode)
                }
            }
        }

        for (t in Telemetry.all) {
            "E-06 Regel 3, Abgleich mit $t: CUSTOM_MODE_ENABLED ist gesetzt" {
                checkModeInvariance(t) { _, after ->
                    hasCustomModeEnabled(after.baseMode) shouldBe true
                }
            }
        }
    })

/**
 * Prueft eine Regel fuer v = view(s) und v' = view(sync(s, t)), mit s aus eventSequence.
 * [rule] enthaelt genau eine Assertion.
 */
private suspend fun checkModeInvariance(
    t: Telemetry,
    rule: (before: MavlinkView, after: MavlinkView) -> Unit
) {
    checkAll(catalogConfig, eventSequence) { events ->
        val execution = Execution.reach(events)
        val before = view(execution.state)
        execution.perform(Event.Sync(t))
        val after = view(execution.state)

        val outcome = execution.trace.last().outcome
        val dispatched = outcome is Outcome.Synced && outcome.transition != null
        collect(if (dispatched) "sync loest Transition aus" else "sync loest nichts aus")

        withClue({ execution.describe() }) {
            rule(before, after)
        }
    }
}

private fun withoutArmBit(baseMode: Int): Int =
    baseMode and MAV_MODE_FLAG.MAV_MODE_FLAG_SAFETY_ARMED.inv()

private fun hasCustomModeEnabled(baseMode: Int): Boolean =
    (baseMode and MAV_MODE_FLAG.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) != 0
