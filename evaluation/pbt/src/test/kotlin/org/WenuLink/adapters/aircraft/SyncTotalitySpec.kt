package org.WenuLink.adapters.aircraft

import com.MAVLink.enums.MAV_LANDED_STATE
import com.MAVLink.enums.MAV_STATE
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.property.checkAll

/**
 * E-05 Totalität, Eigenschaftskatalog v1.0, Abschnitt 4:
 * "Keine Ereignisfolge führt zu einem undefinierten Zustand oder einer Ausnahme."
 *
 * Formal: Für alle s in S und alle t in T terminiert sync(s, t) ohne Ausnahme,
 * view(sync(s, t)) enthält weder MAV_STATE_UNINIT noch MAV_LANDED_STATE_UNDEFINED.
 *
 * Umsetzung:
 *   - Je t in T ein Test, s kommt aus eventSequence. Die Folge kann mit einem Elapse enden,
 *     damit ist auch der zeitliche Abstand vor dem Abgleich frei gewählt.
 *   - Terminierung wird nicht eigens geprüft. sync und die Übergänge enthalten weder
 *     Schleifen noch Rekursion; ein Hängen fiele über das Timeout von Kotest auf.
 *   - Eine Ausnahme aus dem abschliessenden sync wird abgefangen und als Verstoss gemeldet.
 *     Eine Ausnahme beim Herstellen von s lässt den Test direkt scheitern; das Shrinking
 *     kürzt die Folge dann bis zu dem Ereignis, das sie auslöst.
 *   - Die Statistik zeigt, welche Transition der abschliessende Abgleich auslöst. Sie dient
 *     nur der Abdeckung, das Urteil fällt allein über die Sicht.
 */
class SyncTotalitySpec :
    StringSpec({

        for (t in Telemetry.all) {
            "E-05 Abgleich mit $t endet ohne Ausnahme in einer definierten Sicht" {
                checkAll(catalogConfig, eventSequence) { events ->
                    val execution = Execution.reach(events)
                    val violations = mutableListOf<String>()

                    try {
                        execution.perform(Event.Sync(t))
                        violations.addAll(undefinedFields(view(execution.state)))
                        collect(dispatchedTransition(execution.trace.last()))
                    } catch (e: Exception) {
                        violations.add("sync$t wirft ${e::class.simpleName}: ${e.message}")
                        collect("Ausnahme")
                    }

                    withClue({ execution.describe() }) {
                        violations.shouldBeEmpty()
                    }
                }
            }
        }
    })

/** Die Felder der Sicht, die einen undefinierten Wert melden. */
private fun undefinedFields(view: MavlinkView): List<String> {
    val fields = mutableListOf<String>()
    if (view.systemStatus == MAV_STATE.MAV_STATE_UNINIT) {
        fields.add("systemStatus = UNINIT")
    }
    if (view.landedState == MAV_LANDED_STATE.MAV_LANDED_STATE_UNDEFINED) {
        fields.add("landedState = UNDEFINED")
    }
    return fields
}

/** Name der Transition, die ein Abgleichschritt ausgelöst hat, für die Statistik. */
private fun dispatchedTransition(step: Step): String {
    val outcome = step.outcome
    if (outcome !is Outcome.Synced) {
        return "kein Abgleich"
    }
    val transition = outcome.transition ?: return "keine Transition"
    return transition::class.simpleName ?: "unbenannte Transition"
}
