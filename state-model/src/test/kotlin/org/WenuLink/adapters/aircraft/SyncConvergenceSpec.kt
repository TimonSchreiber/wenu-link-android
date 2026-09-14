package org.WenuLink.adapters.aircraft

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll

/**
 * Konvergenz, Abschnitt 5, Eigenschaft 2:
 * "Nach jeder Ereignisfolge, die in einem stabilen DJI-Zustand endet, erreicht die Bridge den
 * korrenspondierenden MAVLink-Zustand in beschraenkt vielen Schritten."
 *
 * Der laengste Pfad im betrachteten Ausschnitt ist Standby -> Arm -> Takeoff -> Flying
 * also drei Uebergaenge.
 */
class SyncConvergenceSpec :
    StringSpec({

        "wiederholter Abgleich konvergiert im Armed-Zustand" {
            checkAll(convergenceSetup) { setup ->
                convergedMachine(setup).state.isArmed() shouldBe setup.target.isArmed
            }
        }

        "wiederholter Abgleich konvergiert im Flug-Zustand" {
            checkAll(convergenceSetup) { setup ->
                convergedMachine(setup).state.isFlying() shouldBe setup.target.isFlying
            }
        }
    })
