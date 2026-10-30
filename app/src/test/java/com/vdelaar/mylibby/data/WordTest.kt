package com.vdelaar.mylibby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTest {
    @Test fun wordsAreCleanedAndOnlyOneWordCountsAsAWord() {
        assertEquals("don't", cleanWord("“Don’t,”"))
        assertEquals("gloom", cleanWord(" Gloom. "))
        assertEquals("", cleanWord("..."))
        assertTrue(isSingleWord("“gloom,”"))
        assertFalse(isSingleWord("dark gloom"))
        assertFalse(isSingleWord("—"))
        assertFalse(isSingleWord("x".repeat(60)))
    }

    @Test fun bookLanguagesBecomeDictionaryLanguages() {
        assertEquals("en", dictionaryLanguage(null))
        assertEquals("nl", dictionaryLanguage("nl-NL"))
        assertEquals("nl", dictionaryLanguage("dut"))
        assertEquals("es", dictionaryLanguage("Spanish"))
        assertEquals("de", dictionaryLanguage("ger"))
        assertEquals("sv", dictionaryLanguage("sv"))
        assertEquals("en", dictionaryLanguage("klingon"))
    }
}
