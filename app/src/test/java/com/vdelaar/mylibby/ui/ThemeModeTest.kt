package com.vdelaar.mylibby.ui

import com.vdelaar.mylibby.core.datastore.ReaderSettings
import com.vdelaar.mylibby.core.datastore.ReaderTheme
import com.vdelaar.mylibby.core.datastore.ThemeMode
import com.vdelaar.mylibby.ui.reader.effectiveTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #14: the reader went dark in daylight; the dark page must follow the app's own light/dark choice. */
class ThemeModeTest {
    @Test fun appChoiceWinsOverTheSystem() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
        assertEquals(false, ThemeMode.LIGHT.isDark(true))
        assertEquals(true, ThemeMode.DARK.isDark(false))
    }

    @Test fun lightAppWithDarkPhoneKeepsALightPage() {
        val s = ReaderSettings(theme = ReaderTheme.PAPER, followSystemDark = true)
        assertEquals(ReaderTheme.PAPER, s.effectiveTheme(ThemeMode.LIGHT.isDark(systemDark = true)))
        assertEquals(s.darkTheme, s.effectiveTheme(ThemeMode.SYSTEM.isDark(systemDark = true)))
    }

    @Test fun switchOffNeverGoesDark() {
        val s = ReaderSettings(theme = ReaderTheme.PAPER, followSystemDark = false)
        assertEquals(ReaderTheme.PAPER, s.effectiveTheme(true))
    }
}
