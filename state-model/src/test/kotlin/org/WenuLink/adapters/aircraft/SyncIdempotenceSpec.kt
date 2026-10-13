package org.WenuLink.adapters.aircraft

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.assume
import io.kotest.property.checkAll

/**
 * E-04a Idempotenz der beobachtbaren Sicht und E-04b Idempotenz auf Zustandsebene,
 * Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Wiederholt gesendete identische Statusmeldungen veraendern die veroeffentlichte MAVLink-Sicht
 * eines stabilen Zustands nicht" (E-04a), "einen stabilen Zustand auch intern nicht" (E-04b).
 *
 * Formal: Sei s in S stabil bezueglich t in T_stable, also π(view(s)) = soll(t). Dann gilt fuer
 * alle n >= 1:
 *   E-04a: view(sync^n(s, t)) = view(s)
 *   E-04b: sync^n(s, t) = s
 *
 * Umsetzung:
 *   - Die Vorbedingung wird nicht konstruktiv hergestellt, sondern per assume gegen das Orakel
 *     geprueft. Jede konstruktive Herstellung stabiler Zustaende setzt Annahmen ueber die
 *     Implementierung voraus, die die Arbeit gerade untersucht.
 *   - Die Kandidaten kommen aus stableCandidates(t): zur Haelfte zufaellige Folgen, zur Haelfte
 *     mit einem Kommando-Suffix, der auf soll(t) zusteuert. Das Suffix beeinflusst nur die
 *     Verteilung, nie das Urteil.
 *   - Statt einer Verwerfungsschranke gilt eine feste Zahl gepruefter Faelle mit
 *     Erzeugungsbudget (conditionalConfig, wie bei E-03). Bleibt ein Test darunter, ist er gruen
 *     und die Ausgabe weist die Leerstelle aus. Damit toeten in der Mutationsanalyse nur echte
 *     Gegenbeispiele. Ist fuer ein t gar kein stabiler Zustand erreichbar, scheitert E-02 fuer
 *     dieses t.
 *   - "Fuer alle n >= 1" wird ueber HORIZON Abgleichschritte geprueft, wie bei E-02.
 *   - Je Telemetrie t und je Fassung ein Test. Gegenbeispiele gegen E-04b sind nach Abschnitt 4
 *     nur zusammen mit dem Befund zu E-03 zu bewerten.
 *   - Die Statistik zeigt, wodurch der gepruefte Zustand zuletzt veraendert wurde. Verstoesse,
 *     die nur beim ersten Abgleich auftreten, zeigen sich an Zustaenden aus Kommandos.
 */
class SyncIdempotenceSpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-04a Abgleich mit $t laesst die Sicht eines stabilen Zustands unveraendert" {
                val context = checkAll(conditionalConfig, stableCandidates(t)) { events ->
                    val execution = Execution.reach(events)
                    assume(project(view(execution.state)) == soll(t))
                    collect(lastChange(execution))

                    // views[n] = view(sync^n(s, t))
                    val views = mutableListOf(view(execution.state))
                    for (step in 1..HORIZON) {
                        execution.monitorTick(t)
                        views.add(view(execution.state))
                    }

                    withClue({ execution.describe() }) {
                        deviationRanges(views, views[0], from = 1, index = "n").shouldBeEmpty()
                    }
                }
                reportCoverage("E-04a", t, context)
            }
        }

        for (t in Telemetry.all) {
            "E-04b Abgleich mit $t laesst einen stabilen Zustand unveraendert" {
                val context = checkAll(conditionalConfig, stableCandidates(t)) { events ->
                    val execution = Execution.reach(events)
                    assume(project(view(execution.state)) == soll(t))
                    collect(lastChange(execution))

                    // states[n] = sync^n(s, t)
                    val states = mutableListOf(execution.state)
                    for (step in 1..HORIZON) {
                        execution.monitorTick(t)
                        states.add(execution.state)
                    }

                    val deviations = deviationRanges(
                        values = states,
                        target = states[0],
                        from = 1,
                        index = "n",
                        show = ::renderState
                    )
                    withClue({ execution.describe() }) {
                        deviations.shouldBeEmpty()
                    }
                }
                reportCoverage("E-04b", t, context)
            }
        }
    })

/** Wodurch der gepruefte Zustand zuletzt veraendert wurde, fuer die Statistik. */
private fun lastChange(execution: Execution): String {
    for (step in execution.trace.asReversed()) {
        val outcome = step.outcome
        val changed = outcome == Outcome.Dispatched ||
            (outcome is Outcome.Synced && outcome.transition != null)
        if (changed) {
            return when (step.event) {
                is Event.Dispatch -> "zuletzt durch Kommando"
                is Event.ModeChange -> "zuletzt durch Moduswechsel"
                is Event.Sync -> "zuletzt durch sync"
                is Event.Elapse -> "zuletzt durch Zeit"
            }
        }
    }
    return "Startzustand"
}
