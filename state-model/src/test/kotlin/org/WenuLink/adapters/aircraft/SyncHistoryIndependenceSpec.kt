package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * E-03 Verlaufsunabhaengigkeit, Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Zustaende, die in ihrer veroeffentlichten MAVLink-Sicht uebereinstimmen, verhalten sich unter
 * derselben Telemetrie auch in der Folgesicht gleich. Interne Buchfuehrung wirkt sich nicht auf
 * das beobachtbare Verhalten aus."
 *
 * Formal: Fuer alle s1, s2 in S und alle t in T:
 *   view(s1) = view(s2)  =>  view(sync(s1, t)) = view(sync(s2, t))
 *
 * Umsetzung:
 *   - Ein einzelner Abgleichschritt genuegt. Erfuellt die Sichtgleichheit diese
 *     Einschrittbedingung, ist sie eine Bisimulation, und die Gleichheit bleibt unter jeder
 *     Folge erhalten (Abschnitt 2.1 der Arbeit).
 *   - Die Paare kommen aus viewPairs: zur Haelfte Twins (dieselbe Folge, zeitlich versetzt),
 *     zur Haelfte Paare verschiedener Verlaeufe (unabhaengige Praefixe mit gemeinsamem Suffix).
 *     Twins variieren gezielt armTimestamp, die Verlaufspaare erfassen Zustaende, die nach
 *     verschiedenen Verlaeufen dieselbe Sicht melden.
 *   - Vor dem Vergleich stehen beide Uhren auf derselben Zeit (ViewPair.reach). Die Uhr ist
 *     Umgebung, nicht Zustand.
 *   - Die Vorbedingung view(s1) = view(s2) wird per assume gegen das Orakel geprueft, mit
 *     derselben Laufregel wie E-04 (conditionalConfig).
 *   - Je t in T ein Test.
 *   - Die Statistik unterscheidet die Quelle und ob die Zustaende auch intern gleich sind.
 *     Intern gleiche Zustaende erfuellen E-03 trivial, da sync deterministisch ist.
 */
class SyncHistoryIndependenceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-03 Abgleich mit $t fuehrt sichtgleiche Zustaende in dieselbe Folgesicht" {
                val context = checkAll(conditionalConfig, viewPairs) { pair ->
                    val (first, second) = pair.reach()
                    assume(view(first.state) == view(second.state))
                    collect(pairClass(pair, first.state, second.state))

                    first.perform(Event.Sync(t))
                    second.perform(Event.Sync(t))

                    withClue({ describePair(first, second) }) {
                        view(first.state) shouldBe view(second.state)
                    }
                }
                reportCoverage("E-03", t, context)
            }
        }
    })

/** Einordnung fuer die Statistik nach Quelle und interner Gleichheit. */
private fun pairClass(pair: ViewPair, first: AircraftState, second: AircraftState): String {
    val source = when (pair) {
        is Twins -> "Twins"
        is HistoryPair -> "verschiedene Verlaeufe"
    }
    val internal = if (first == second) "intern gleich" else "intern verschieden"
    return "$source, $internal"
}

private fun describePair(first: Execution, second: Execution): String =
    "Ausfuehrung 1:\n${first.describe()}\n\nAusfuehrung 2:\n${second.describe()}"
