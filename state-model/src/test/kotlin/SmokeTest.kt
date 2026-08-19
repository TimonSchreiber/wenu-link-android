package org.WenuLink.statemodel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

class SmokeTest :
    StringSpec({

        "Kotest runs" {
            2 + 2 shouldBe 4
        }

        "checkAll produces series" {
            checkAll(Arb.list(Arb.int(0..5), 0..20)) { xs ->
                xs.size shouldBe xs.reversed().size
            }
        }
    })
