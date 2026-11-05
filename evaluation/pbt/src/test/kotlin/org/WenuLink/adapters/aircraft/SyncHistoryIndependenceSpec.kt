package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * E-03 Verlaufsunabhängigkeit, Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Zustände, die in ihrer veröffentlichten MAVLink-Sicht übereinstimmen, verhalten sich unter
 * derselben Telemetrie auch in der Folgesicht gleich. Interne Buchführung wirkt sich nicht auf
 * das beobachtbare Verhalten aus."
 *
 * Formal: Für alle s1, s2 in S und alle t in T:
 *   view(s1) = view(s2) → view(sync(s1, t)) = view(sync(s2, t))
 *
 * Umsetzung:
 *   - Ein einzelner Abgleichschritt genügt. Erfüllt die Sichtgleichheit diese
 *     Einschrittbedingung, ist sie eine Bisimulation, und die Gleichheit bleibt unter jeder
 *     Folge erhalten (Abschnitt 2.1 der Arbeit).
 *   - Die Paare kommen aus viewPairs: unabhängige Präfixe mit gemeinsamem Suffix, dazu als
 *     Randfälle Paare, die denselben Zustand einmal über Kommandos und einmal über sync
 *     erreichen. Die früheren Twins (dieselbe Folge, zeitlich versetzt) sind entfallen: Seit
 *     das Modell die Zeit nicht mehr liest, laufen beide Ausführungen identisch und können
 *     E-03 nicht verletzen.
 *   - Vor dem Vergleich enden beide Spuren zur selben Zeit (ViewPair.reach). Das Modell liest
 *     die Zeit nicht; die Angleichung hält nur die Spuren vergleichbar.
 *   - Die Vorbedingung view(s1) = view(s2) wird per assume gegen das Orakel geprüft, mit
 *     derselben Laufregel wie E-04 (conditionalConfig).
 *   - Je t in T ein Test.
 *   - Die Statistik zeigt, ob sichtgleiche Zustände auch intern gleich sind. Intern gleiche
 *     Zustände erfüllen E-03 trivial, da sync deterministisch ist.
 */
class SyncHistoryIndependenceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-03 Abgleich mit $t führt sichtgleiche Zustände in dieselbe Folgesicht" {
                val context = checkAll(conditionalConfig, viewPairs) { pair ->
                    val (first, second) = pair.reach()
                    assume(view(first.state) == view(second.state))
                    collect(internalClass(first.state, second.state))

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

/** Einordnung für die Statistik: ob sichtgleiche Zustände auch intern gleich sind. */
private fun internalClass(first: AircraftState, second: AircraftState): String =
    if (first == second) "intern gleich" else "intern verschieden"

private fun describePair(first: Execution, second: Execution): String =
    "Ausführung 1:\n${first.describe()}\n\nAusführung 2:\n${second.describe()}"
