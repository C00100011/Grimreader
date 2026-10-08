package com.vdelaar.mylibby.core.datastore

import kotlinx.serialization.Serializable

enum class ReaderTheme(
    @androidx.annotation.StringRes val label: Int,
    val bg: Long,
    val fg: Long,
    val link: Long,
    val selection: Long,
    val dark: Boolean,
) {
    PAPER(com.vdelaar.mylibby.R.string.theme_paper, 0xFFFBF6F0, 0xFF2B2420, 0xFF8A4B1F, 0x66E0A15A, false),
    SEPIA(com.vdelaar.mylibby.R.string.theme_sepia, 0xFFF1E5CC, 0xFF5B4636, 0xFF8A4B1F, 0x66C9A26B, false),
    WHITE(com.vdelaar.mylibby.R.string.theme_white, 0xFFFFFFFF, 0xFF1C1C1C, 0xFF1F5FBF, 0x553D7EDB, false),
    MINT(com.vdelaar.mylibby.R.string.theme_mint, 0xFFE6F0E8, 0xFF1F3326, 0xFF2E6B45, 0x664F9A6B, false),
    DUSK(com.vdelaar.mylibby.R.string.theme_dusk, 0xFF2A2633, 0xFFDCD6E8, 0xFFB7A3F0, 0x667C68C8, true),
    NIGHT(com.vdelaar.mylibby.R.string.theme_night, 0xFF1B1916, 0xFFE3DBD0, 0xFFE0A15A, 0x66A36A2E, true),
    BLACK(com.vdelaar.mylibby.R.string.theme_amoled, 0xFF000000, 0xFFBDB6AC, 0xFFE0A15A, 0x66805020, true),
}

enum class ReaderFont(val label: String, val css: String, @androidx.annotation.StringRes val labelRes: Int? = null) {
    PUBLISHER("Publisher", "publisher", com.vdelaar.mylibby.R.string.font_publisher),
    LITERATA("Literata", "Literata"),
    MERRIWEATHER("Merriweather", "Merriweather"),
    LORA("Lora", "Lora"),
    ATKINSON("Atkinson Hyperlegible", "Atkinson Hyperlegible"),
    OPEN_DYSLEXIC("OpenDyslexic", "OpenDyslexic"),
    SERIF("System serif", "serif", com.vdelaar.mylibby.R.string.font_system_serif),
    SANS("System sans", "sans-serif", com.vdelaar.mylibby.R.string.font_system_sans),
}

enum class PageFlow { PAGINATED, SCROLLED }

/** Something the reader can show in the header or footer of the page (see ReaderInfo.kt). */
enum class InfoItem(@androidx.annotation.StringRes val label: Int) {
    NONE(com.vdelaar.mylibby.R.string.info_none),
    BOOK_TITLE(com.vdelaar.mylibby.R.string.info_book_title),
    AUTHOR(com.vdelaar.mylibby.R.string.info_author),
    CHAPTER_TITLE(com.vdelaar.mylibby.R.string.info_chapter_title),
    PAGE_CHAPTER(com.vdelaar.mylibby.R.string.info_page_chapter),
    PAGE_BOOK(com.vdelaar.mylibby.R.string.info_page_book),
    PERCENT_BOOK(com.vdelaar.mylibby.R.string.info_percent_book),
    PERCENT_CHAPTER(com.vdelaar.mylibby.R.string.info_percent_chapter),
    TIME_LEFT_CHAPTER(com.vdelaar.mylibby.R.string.info_time_chapter),
    TIME_LEFT_BOOK(com.vdelaar.mylibby.R.string.info_time_book),
    LOCATION(com.vdelaar.mylibby.R.string.info_location),
    CLOCK(com.vdelaar.mylibby.R.string.info_clock),
    BATTERY(com.vdelaar.mylibby.R.string.info_battery),
}

/** What the slider on the tap-to-show reading bar covers. */
enum class ProgressScope { BOOK, CHAPTER }

@Serializable
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val followSystemDark: Boolean = true,
    val darkTheme: ReaderTheme = ReaderTheme.NIGHT,
    val font: ReaderFont = ReaderFont.LITERATA,
    val fontSizePx: Int = 19,
    val lineHeight: Float = 1.55f,
    val paragraphSpacing: Float = 0.6f,
    val marginPercent: Int = 6,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val flow: PageFlow = PageFlow.PAGINATED,
    val twoColumnsOnWide: Boolean = true,
    val tapToTurn: Boolean = true,
    val volumeKeysTurn: Boolean = true,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f, // -1 = system
    /** Colour gradient across every line of text to guide the eyes (see GuidePalette). */
    val guidedColors: Boolean = false,
    val guidePalette: Int = 0,
    val guideIntensity: Float = 0.6f,
    /** Header and footer of the page while reading: what goes where. Defaults match the original look. */
    val headerLeft: InfoItem = InfoItem.NONE,
    val headerCenter: InfoItem = InfoItem.CHAPTER_TITLE,
    val headerRight: InfoItem = InfoItem.NONE,
    val footerLeft: InfoItem = InfoItem.TIME_LEFT_CHAPTER,
    val footerCenter: InfoItem = InfoItem.NONE,
    val footerRight: InfoItem = InfoItem.PERCENT_BOOK,
    val progressScope: ProgressScope = ProgressScope.BOOK,
)

enum class SpeedMode { WORD, PHRASE }

enum class SpeedStyle(@androidx.annotation.StringRes val label: Int) {
    ORP(com.vdelaar.mylibby.R.string.speed_style_orp),
    RETICLE(com.vdelaar.mylibby.R.string.speed_style_reticle),
    FOCUS_BOLD(com.vdelaar.mylibby.R.string.speed_style_bold),
    MONOSPACE(com.vdelaar.mylibby.R.string.speed_style_mono),
    DYSLEXIC(com.vdelaar.mylibby.R.string.speed_style_dyslexic),
}

@Serializable
data class SpeedReadSettings(
    val wpm: Int = 300,
    val mode: SpeedMode = SpeedMode.WORD,
    val phraseWords: Int = 3,
    val style: SpeedStyle = SpeedStyle.ORP,
    val fontSizeSp: Int = 40,
    /** Last speed per book, so every book picks up where you left off. */
    val bookWpm: Map<Long, Int> = emptyMap(),
)

@Serializable
data class TtsSettings(
    val dutchVoice: String? = null,
    val englishVoice: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val sleepTimerMinutes: Int = 0,
    /** Read-aloud language chosen per book ("nl" / "en"); missing = automatic. */
    val bookLanguages: Map<Long, String> = emptyMap(),
)

enum class GoalType { MINUTES, PAGES }

@Serializable
data class GoalSettings(
    val type: GoalType = GoalType.MINUTES,
    val dailyTarget: Int = 20,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 30,
    val freezesPerWeek: Int = 1,
)

@Serializable
data class ServerSettings(
    val grimmoryUrl: String = "",
    val username: String = "",
    val shelfmarkUrl: String = "",
    /** How we sign in to Shelfmark: "password", "oidc", "apikey" or "none". */
    val shelfmarkAuth: String = "",
    /** No longer used: replaced by [trustedCerts] (kept so old settings still load). */
    val allowInsecure: Boolean = false,
    /** SHA-256 fingerprints of self-signed server certificates the user confirmed. */
    val trustedCerts: Set<String> = emptySet(),
)

/** What the last Grimmory synchronisation did, shown in the sync popup. */
@Serializable
data class SyncSettings(
    val lastAt: Long = 0,
    val lastOk: Boolean = true,
    val lastError: String? = null,
    /** Books and covers kept on this device by the last full run. */
    val books: Int = 0,
    val covers: Int = 0,
    val forced: Boolean = false,
    /** Book id -> when this app last sent its (further) progress again so a Kobo or other reader gets it. */
    val reasserted: Map<Long, Long> = emptyMap(),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Profile(
    val name: String = "",
    val avatar: String = "📚",
    /** Version stamp of the picture the user chose (file lives in app storage); 0 = none, show the emoji. */
    val photoVersion: Long = 0,
    val favouriteGenres: List<String> = emptyList(),
    val yearlyBookGoal: Int = 12,
    val memberSince: Long = System.currentTimeMillis(),
)

/** Where the user's books come from. */
enum class LibrarySource { GRIMMORY, OPDS, LOCAL }

/** What finds new books to get (Shelfmark-style: look a book up, then download or request it). */
enum class BookSearchSource { SHELFMARK, NONE }

/** Where "books you might like" come from. */
/** LOCAL = from your own library, HARDCOVER = trending and suggestions with your own key, OPEN_LIBRARY = new books in your genres and languages (no account). */
enum class RecommendationSource { LOCAL, HARDCOVER, OPEN_LIBRARY }

/**
 * The reading speed your pace is compared with on the book page. Words per minute for silent reading by adults:
 * 238 non-fiction and 260 fiction (Brysbaert 2019). [CUSTOM] uses [AppState.paceCustomWpm].
 */
enum class PaceReference(
    val wpm: Int?,
    @androidx.annotation.StringRes val label: Int,
    /** Reads after "than": "faster than <phrase>". */
    @androidx.annotation.StringRes val phrase: Int,
) {
    ADULT(238, com.vdelaar.mylibby.R.string.pace_ref_adult, com.vdelaar.mylibby.R.string.pace_phrase_adult),
    FICTION(260, com.vdelaar.mylibby.R.string.pace_ref_fiction, com.vdelaar.mylibby.R.string.pace_phrase_fiction),
    RELAXED(200, com.vdelaar.mylibby.R.string.pace_ref_relaxed, com.vdelaar.mylibby.R.string.pace_phrase_relaxed),
    BRISK(320, com.vdelaar.mylibby.R.string.pace_ref_brisk, com.vdelaar.mylibby.R.string.pace_phrase_brisk),
    CUSTOM(null, com.vdelaar.mylibby.R.string.pace_ref_custom, com.vdelaar.mylibby.R.string.pace_phrase_custom);

    companion object {
        const val MIN_CUSTOM = 100
        const val MAX_CUSTOM = 700
    }
}

@Serializable
data class AppState(
    val onboardingDone: Boolean = false,
    val profile: Profile = Profile(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** "system", "en" or "nl" (see AppLanguage). */
    val language: String = "system",
    /** Keep every book of the library on this device, including newly added ones. */
    val autoDownloadAll: Boolean = false,
    val autoDownloadWifiOnly: Boolean = true,
    /** Books the user removed by hand; auto-download leaves them alone. */
    val autoDownloadExcluded: Set<Long> = emptySet(),
    /** Demo mode: no server, the bundled free books on this device. */
    val demo: Boolean = false,
    /** Local-only mode: no Grimmory or Shelfmark, everything stays on this device. */
    val localOnly: Boolean = false,
    /** Secret menu: achievements forced on (true) or off (false); missing = decided by the real stats. */
    val badgeOverrides: Map<String, Boolean> = emptyMap(),
    val demoBookIds: Set<Long> = emptySet(),
    val favouritesShelfId: Long? = null,
    val libraryGrid: Boolean = true,
    val librarySort: String = "addedOn",
    val librarySortDir: String = "desc",
    /** An OPDS catalog is the library source: browse it and download books to this device. */
    val opdsActive: Boolean = false,
    val opdsUrl: String = "",
    val opdsUsername: String = "",
    /** Book search; null = automatic (Shelfmark, except in local-only mode, as before). */
    val bookSearch: BookSearchSource? = null,
    val recommendations: RecommendationSource = RecommendationSource.LOCAL,
    /** What the reading pace on the book page is compared with (default: the average adult). */
    val paceReference: PaceReference = PaceReference.ADULT,
    val paceCustomWpm: Int = 238,
    /** Books imported on this device show in Library next to the library server's books. */
    val deviceBooks: Boolean = true,
    /** Which generation of the library settings these were saved with (see SettingsRepository.migrate). */
    val libraryModel: Int = 0,
) {
    /** Words per minute to compare with. */
    val paceWpm: Int
        get() = if (paceReference == PaceReference.CUSTOM) paceCustomWpm.coerceIn(PaceReference.MIN_CUSTOM, PaceReference.MAX_CUSTOM) else paceReference.wpm!!

    /** True in demo mode and when the Grimmory library is switched off: nothing talks to a Grimmory server. */
    val noServer: Boolean get() = demo || localOnly

    /** The Grimmory library is switched on (an OPDS catalog or the device can be on as well). */
    val grimmoryOn: Boolean get() = !localOnly && !demo

    /** Imported books show in Library. Always so when Grimmory is off, in the demo, or when an OPDS catalog is on (its downloads land here). */
    val deviceOn: Boolean get() = deviceBooks || localOnly || opdsActive || demo

    /** Every library that is switched on; the three can be combined. */
    val librarySources: Set<LibrarySource>
        get() = buildSet {
            if (grimmoryOn) add(LibrarySource.GRIMMORY)
            if (opdsActive) add(LibrarySource.OPDS)
            if (deviceOn) add(LibrarySource.LOCAL)
        }

    val effectiveBookSearch: BookSearchSource get() = bookSearch ?: if (localOnly) BookSearchSource.NONE else BookSearchSource.SHELFMARK

    /** The Discover tab shows book search and/or Hardcover recommendations. */
    val showDiscover: Boolean get() = effectiveBookSearch == BookSearchSource.SHELFMARK || recommendations != RecommendationSource.LOCAL
}

// ---- the library checkboxes (Settings > Integrations): the rules, kept pure so they can be tested ----

/** Switch the Grimmory library on or off. Off keeps the sign-in; the device then has to hold the books. */
fun AppState.withGrimmory(on: Boolean): AppState = if (on) copy(localOnly = false) else copy(localOnly = true, deviceBooks = true)

/** Switch the OPDS catalog on or off. Its downloads become books on this device, so that switches on too. */
fun AppState.withOpds(on: Boolean): AppState = if (on) copy(opdsActive = true, deviceBooks = true) else copy(opdsActive = false)

/** This device can only be switched off while Grimmory is on and no OPDS catalog needs it. */
fun AppState.canSwitchOffDevice(): Boolean = grimmoryOn && !opdsActive

fun AppState.withDevice(on: Boolean): AppState = if (on) copy(deviceBooks = true) else if (canSwitchOffDevice()) copy(deviceBooks = false) else this

/**
 * Library settings before generation 2: an OPDS catalog implied "no Grimmory". Keep that meaning now that the
 * three can be combined, so nobody's library changes by updating.
 */
fun AppState.migratedLibraryModel(): AppState = when {
    libraryModel >= 2 -> this
    opdsActive && !localOnly -> copy(localOnly = true, deviceBooks = true, libraryModel = 2)
    else -> copy(libraryModel = 2)
}
