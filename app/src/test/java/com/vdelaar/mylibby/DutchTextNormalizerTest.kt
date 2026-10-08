package com.vdelaar.mylibby

import com.vdelaar.mylibby.tts.neural.DutchTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class DutchTextNormalizerTest {
    private fun n(s: String) = DutchTextNormalizer.normalize(s)

    @Test fun cardinals() {
        assertEquals("drieëntwintig", DutchTextNormalizer.cardinal(23))
        assertEquals("eenentwintig", DutchTextNormalizer.cardinal(21))
        assertEquals("honderd", DutchTextNormalizer.cardinal(100))
        assertEquals("tweehonderd vijfenveertig", DutchTextNormalizer.cardinal(245))
        assertEquals("duizend", DutchTextNormalizer.cardinal(1000))
        assertEquals("tweeduizend vijf", DutchTextNormalizer.cardinal(2005))
        assertEquals("twee miljoen", DutchTextNormalizer.cardinal(2_000_000))
    }

    @Test fun yearsOnlyAfterAYearWord() {
        assertEquals("In negentienhonderd negenennegentig werd het gebouwd", n("In 1999 werd het gebouwd"))
        assertEquals("Het kostte duizend negenhonderd negenennegentig euro", n("Het kostte 1999 euro"))
    }

    @Test fun decimalsMoneyAndOrdinals() {
        assertEquals("twaalf komma vijf nul euro", n("€12,50"))
        assertEquals("de derde keer", n("de 3e keer"))
        assertEquals("duizend tweehonderd", n("1.200"))
    }

    @Test fun abbreviations() {
        assertEquals("de heer Jansen", n("dhr. Jansen"))
        assertEquals("bijvoorbeeld dit", n("bijv. dit"))
    }
}
