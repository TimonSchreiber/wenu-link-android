package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.checkAll

/** Schranke k aus E-02: Standby -> Arm -> Takeoff -> Flying umfasst drei Transitionen. */
private const val K = 3

/**
 * Horizont H, ueber den "fuer alle m >= n" geprueft wird. Ein Test kann nur endlich viele m
 * pruefen. Der einzige zeitabhaengige Zweig von sync ist der Arm-Timeout. Wurde armTimestamp
 * spaetestens im Schritt k gesetzt, ist der Timeout nach H weiteren Schritten zu je
 * SYNC_INTERVAL_MS sicher abgelaufen; ein Verlassen der Sollsicht durch diesen Zweig liegt
 * damit im Horizont.
 */
private const val H = (ARM_TIMEOUT_MS / SYNC_INTERVAL_MS).toInt() + 1

/**
 * E-02 Konvergenz (Selbststabilisierung), Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Aus jedem erreichbaren Zustand erreicht die Bridge unter konstant anliegender Telemetrie
 * innerhalb einer endlichen Anzahl von Abgleichschritten die fuer diese Telemetrie
 * spezifizierte MAVLink-Sicht und verbleibt anschliessend in dieser Sicht."
 *
 * Formal: Fuer alle s in S, alle t in T_stable existiert ein n mit 0 <= n <= k, sodass fuer
 * alle m >= n gilt: π(view(sync^m(s, t))) = soll(t), mit k = 3.
 *
 * Umsetzung:
 *   - "Es gibt ein n <= k, ab dem fuer alle m >= n gilt" ist gleichwertig zu "fuer alle
 *     m >= k gilt": Jedes solche n laesst sich durch k ersetzen. Der Test fuehrt deshalb
 *     k + H Abgleichschritte aus und prueft die Schritte k bis k + H.
 *   - Ein Abgleichschritt ist Event.Sync nach SYNC_INTERVAL_MS, wie in der Monitor-Schleife
 *     (Execution.monitorTick). Das ist die Lesart von Modellannahme M-1 fuer die Zeit.
 *   - Je t in T ein Test (T_stable = T nach A-01), damit Gegenbeispiele verschiedener
 *     Tabellenzeilen beim Shrinking nicht vermischt werden.
 *   - Die Statistik ordnet jede Ausfuehrung nach dem kleinsten n ein, ab dem die Sicht im
 *     Horizont bei soll(t) bleibt. "n = 0" heisst, s war bereits stabil.
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
                    for (step in 1..K + H) {
                        execution.monitorTick(t)
                        views.add(project(view(execution.state)))
                    }

                    collect(convergenceClass(views, target))

                    withClue({ execution.describe() }) {
                        deviations(views, target, from = K).shouldBeEmpty()
                    }
                }
            }
        }
    })

/**
 * Die Schritte ab [from], in denen die Sicht von [target] abweicht, zusammengefasst zu
 * Bereichen gleicher Sicht, etwa "m = 3..54: (ACTIVE, armed, ON_GROUND)".
 */
private fun deviations(views: List<ProjectedView>, target: ProjectedView, from: Int): List<String> {
    val result = mutableListOf<String>()
    var m = from
    while (m < views.size) {
        if (views[m] == target) {
            m++
            continue
        }
        val start = m
        while (m + 1 < views.size && views[m + 1] == views[start]) {
            m++
        }
        val range = if (start == m) "$start" else "$start..$m"
        result.add("m = $range: ${views[start]}")
        m++
    }
    return result
}

/** Einordnung fuer die Statistik nach dem kleinsten n, ab dem die Sicht bei [target] bleibt. */
private fun convergenceClass(views: List<ProjectedView>, target: ProjectedView): String {
    val n = views.indexOfLast { it != target } + 1
    return when {
        n <= K -> "n = $n"
        n < views.size -> "n > k, Sollsicht im Horizont erreicht"
        else -> "Sollsicht am Ende des Horizonts nicht erreicht"
    }
}
