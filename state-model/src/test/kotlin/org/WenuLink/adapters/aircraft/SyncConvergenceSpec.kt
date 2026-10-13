package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.checkAll

/** Schranke k aus E-02: Standby → Arm → Takeoff → Flying umfasst drei Übergänge. */
private const val K = 3

/**
 * E-02 Konvergenz (Selbststabilisierung), Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Aus jedem erreichbaren Zustand erreicht die Bridge unter konstant anliegender Telemetrie
 * innerhalb einer endlichen Anzahl von Abgleichschritten die für diese Telemetrie
 * spezifizierte MAVLink-Sicht und verbleibt anschliessend in dieser Sicht."
 *
 * Formal: Für alle s in S, alle t in T_stable existiert ein n mit 0 ≤ n ≤ k, sodass für
 * alle m >= n gilt: π(view(sync^m(s, t))) = soll(t), mit k = 3.
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
 *   - Die Statistik ordnet jede Ausführung nach dem kleinsten n ein, ab dem die Sicht im
 *     Horizont bei `soll(t)` bleibt. "n = 0" heisst, s war bereits stabil.
 */
class SyncConvergenceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-02 Konvergenz unter $t innerhalb von k = $K Abgleichschritten" {
                checkAll(catalogConfig, eventSequence) { events ->
                    val execution = Execution.reach(events)
                    val target = soll(t)

                    // views[m] = π(view(sync^m(s, t)))
                    val views = mutableListOf(project(view(execution.state)))
                    for (step in 1..K + HORIZON) {
                        execution.monitorTick(t)
                        views.add(project(view(execution.state)))
                    }

                    collect(convergenceClass(views, target))

                    withClue({ execution.describe() }) {
                        deviationRanges(views, target, from = K, index = "m").shouldBeEmpty()
                    }
                }
            }
        }
    })

/** Einordnung für die Statistik nach dem kleinsten n, ab dem die Sicht bei [target] bleibt. */
private fun convergenceClass(views: List<ProjectedView>, target: ProjectedView): String {
    val n = views.indexOfLast { it != target } + 1
    return when {
        n <= K -> "n = $n"
        n < views.size -> "n > k, Soll-Sicht im Horizont erreicht"
        else -> "Soll-Sicht am Ende des Horizonts nicht erreicht"
    }
}
