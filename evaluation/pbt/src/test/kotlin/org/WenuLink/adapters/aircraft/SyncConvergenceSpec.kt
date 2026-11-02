package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.checkAll

/** Schranke k aus E-02: Standby → Arm → Takeoff → Flying umfasst drei Übergänge. */
private const val K = 3

/**
 * E-02 Konvergenz (Selbststabilisierung), Eigenschaftskatalog v1.3, Abschnitt 4:
 * "Aus jedem erreichbaren Zustand erreicht die Bridge unter konstant anliegender Telemetrie
 * innerhalb einer endlichen Anzahl von Abgleichschritten eine für diese Telemetrie und den
 * gemeldeten Flugmodus spezifizierte MAVLink-Sicht und verbleibt anschliessend bei solchen
 * Sichten."
 *
 * Formal: Für alle s in S, alle t in T_stable existiert ein n mit 0 ≤ n ≤ k, sodass für
 * alle m >= n mit v_m = view(sync^m(s, t)) gilt: π(v_m) ∈ soll(t, μ(v_m)), mit k = 3.
 *
 * Umsetzung:
 *   - "Es gibt ein n ≤ k, ab dem für alle m >= n gilt" ist gleichwertig zu "für alle
 *     m >= k gilt": Jedes solche n lässt sich durch k ersetzen. Der Test führt deshalb
 *     k + HORIZON Abgleichschritte aus und prüft die Schritte k bis k + HORIZON. Die
 *     Herleitung von HORIZON steht in Generators.kt.
 *   - Ein Abgleichschritt ist `Event.Sync` nach SYNC_INTERVAL_MS, wie in der Monitor-Schleife
 *     (Execution.monitorTick). Das ist die Lesart von Modellannahme M-1 für die Zeit.
 *   - Je t in T ein Test (T_stable = T nach A-01), damit Gegenbeispiele verschiedener
 *     Tabellenzeilen beim Shrinking nicht vermischt werden.
 *   - Jede Sicht wird in dem Flugmodus beurteilt, den sie selbst meldet (isInTarget), nicht im
 *     Modus von s. Das Urteil setzt damit nicht voraus, dass E-06 gilt.
 *   - Die Statistik ordnet jede Ausführung nach dem kleinsten n ein, ab dem die Sicht im
 *     Horizont im Ziel bleibt. "n = 0" heisst, s war bereits stabil.
 */
class SyncConvergenceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-02 Konvergenz unter $t innerhalb von k = $K Abgleichschritten" {
                checkAll(catalogConfig, eventSequence) { events ->
                    val execution = Execution.reach(events)

                    // views[m] = view(sync^m(s, t))
                    val views = mutableListOf(view(execution.state))
                    for (step in 1..K + HORIZON) {
                        execution.monitorTick(t)
                        views.add(view(execution.state))
                    }

                    collect(convergenceClass(views, t))

                    val deviations = deviationRangesWhere(
                        values = views,
                        from = K,
                        index = "m",
                        show = ::renderJudged
                    ) { isInTarget(t, it) }
                    withClue({ execution.describe() }) {
                        deviations.shouldBeEmpty()
                    }
                }
            }
        }
    })

/**
 * Einordnung für die Statistik nach dem kleinsten n, ab dem die Sicht für [t] im Ziel bleibt.
 */
private fun convergenceClass(views: List<MavlinkView>, t: Telemetry): String {
    val n = views.indexOfLast { !isInTarget(t, it) } + 1
    return when {
        n <= K -> "n = $n"
        n < views.size -> "n > k, Soll-Sicht im Horizont erreicht"
        else -> "Soll-Sicht am Ende des Horizonts nicht erreicht"
    }
}
