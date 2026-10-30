package com.vdelaar.mylibby.data.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The entry shapes below are real FreeDict entries (eng-nld, eng-spa, nld-eng, deu-eng). */
class FreedictHtmlTest {
    @Test fun aSimpleEntryHasOnePronunciationAndOneTranslation() {
        val e = parseFreedictHtml("""<div>/<font color="gray">ə</font>/<br>
<div>een</div></div>""")
        assertEquals("/ə/", e.phonetic)
        assertEquals(listOf(listOf("een")), e.senses.map { it.translations })
    }

    @Test fun nestedListsAreGroupedPerTopLevelSense() {
        val e = parseFreedictHtml("""<div>/<font color="gray">peil</font>/<br>
<ol><li><div>verbleekt</div></li><li><ol><li><div>bleek</div></li><li><div>flets</div></li><li><div>pips</div></li><li><div>vaal</div></li></ol></li></ol></div>""")
        assertEquals(listOf(listOf("verbleekt"), listOf("bleek", "flets", "pips", "vaal")), e.senses.map { it.translations })
    }

    @Test fun grammarGlossAndTranslationAreToldApart() {
        val e = parseFreedictHtml("""<div>/<font color="gray">peɪl</font>/<br>
<div><font class="grammar" color="green">noun</font></div>(heraldiccharge) A vertical band down the middle of a shield.<div>palo</div></div>""")
        assertEquals("noun", e.partOfSpeech)
        val s = e.senses.single()
        assertEquals(listOf("palo"), s.translations)
        assertEquals("(heraldiccharge) A vertical band down the middle of a shield.", s.gloss)
    }

    @Test fun senseWithAGlossAndItsTranslations() {
        val e = parseFreedictHtml("""<div>/<font color="gray">peɪl</font>/<br>
<div><font class="grammar" color="green">adjective</font></div><ol><li>Light in color.<div>pálido</div></li><li>(of human skin) Having a pallor.<ol><li><div>pálido</div></li><li><div>nixte</div></li></ol></li></ol></div>""")
        assertEquals(listOf("pálido"), e.senses[0].translations)
        assertEquals("Light in color.", e.senses[0].gloss)
        assertEquals(listOf("pálido", "nixte"), e.senses[1].translations)
    }

    @Test fun crossReferencesAreLeftOutAndEntitiesDecoded() {
        val e = parseFreedictHtml("""<div><font color="green">male, noun, sg</font><br><ol><li><div lang="en">dog</div></li><li><div lang="en">dawg</div></li></ol><div>
            <a href="#Hunde.2">Hunde</a>
            <a href="#knurriger__Hund.1">knurriger Hund &#xE4;</a>
          </div></div>""")
        assertEquals("male, noun, sg", e.partOfSpeech)
        assertEquals(listOf(listOf("dog"), listOf("dawg")), e.senses.map { it.translations })
        assertEquals("a&b <c> ä", decodeEntities("a&amp;b &lt;c&gt; &#xE4;"))
    }

    @Test fun severalPronunciationsAreJoined() {
        val e = parseFreedictHtml("""<div>/<font color="gray">ˈbɑ.di</font>/, /<font color="gray">ˈbɒd.i</font>/, /<font color="gray">[ˈbɑ.ɾi]</font>/<br>
<div>lichaam</div></div>""")
        assertEquals("/ˈbɑ.di/, /ˈbɒd.i/, [ˈbɑ.ɾi]", e.phonetic)
    }

    @Test fun anEntryWithNothingButAPronunciationHasNoSenses() {
        val e = parseFreedictHtml("""<div>/<font color="gray">boudiː</font>/<br>
</div>""")
        assertEquals("/boudiː/", e.phonetic)
        assertTrue(e.senses.isEmpty())
    }

    @Test fun plainTextIsKeptAsAGloss() {
        val e = parseFreedictHtml("just words")
        assertEquals("just words", e.senses.single().gloss)
        assertNull(e.phonetic)
    }
}
