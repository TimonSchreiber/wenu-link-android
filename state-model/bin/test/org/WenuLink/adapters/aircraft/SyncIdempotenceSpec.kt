package org.WenuLink.adapters.aircraft

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

/**
 * Idempotenz, Abschnitt 5, Eigenschaft 4:
 * "Wiederholt gesendete identische Statusmeldungen veraendern einen stabilen Zustand nicht."
 *
 * Formal: ∀s ∈ S{stable}, e ∈ E{stable}(s): sync(s, e) = s
 */
class SyncIdempotenceSpec :
    StringSpec({

        "Abgleich mit der eigenen Telemetrie laesst einen stabilen Zustand unveraendert" {
            checkAll(stableSetup) { (situation, flightMode, clockStart, repeats) ->
                val fsm = machineIn(situation, flightMode, TickingClock(startAt = clockStart))
                val before = fsm.state

                repeat(repeats) { fsm.sync(situation.isArmed, situation.isFlying) }

                fsm.state shouldBe before
            }
        }
    })
