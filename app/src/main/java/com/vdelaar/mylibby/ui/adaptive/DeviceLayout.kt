package com.vdelaar.mylibby.ui.adaptive

import android.content.res.Configuration
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass

enum class FormFactor { PHONE, FOLDABLE, TABLET }

/** Window width from which two-column screens, list-detail and the navigation rail are used. */
const val WIDE_MIN_WIDTH_DP = 720

/**
 * What kind of screen we're on, following Android's adaptive guidance:
 * phones get a single pane, unfolded foldables and tablets get list-detail layouts.
 */
data class DeviceLayout(
    val formFactor: FormFactor,
    val isWide: Boolean,
    val isExpanded: Boolean,
    val isLandscape: Boolean,
    val isTabletop: Boolean,
    val hasVerticalHinge: Boolean,
) {
    /** Library + detail side by side. */
    val useListDetail: Boolean get() = isWide

    /** Columns the reader may use in landscape / portrait orientation. */
    val readerColumns: Int get() = if (formFactor == FormFactor.PHONE && !isExpanded) 1 else 2

    /** Unfolded foldables show a two-page spread even when held upright, like an open book. */
    val readerPortraitColumns: Int get() = if (formFactor == FormFactor.FOLDABLE && isWide) 2 else 1
}

@Composable
fun rememberDeviceLayout(): DeviceLayout {
    val info = currentWindowAdaptiveInfo()
    val config = LocalConfiguration.current
    val size = info.windowSizeClass
    // Not the 600 dp "medium" breakpoint: at 600-720 dp the navigation rail would leave the content only ~500 dp,
    // too narrow for two columns or list-detail (a Galaxy Tab A11 in portrait is ~600 dp wide). Those get phone layouts.
    val wide = config.screenWidthDp >= WIDE_MIN_WIDTH_DP
    val expanded = size.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val hinges = info.windowPosture.hingeList
    val foldable = hinges.isNotEmpty()
    val smallestWidth = config.smallestScreenWidthDp
    val formFactor = when {
        foldable -> FormFactor.FOLDABLE
        smallestWidth >= 600 -> FormFactor.TABLET
        else -> FormFactor.PHONE
    }
    return DeviceLayout(
        formFactor = formFactor,
        isWide = wide,
        isExpanded = expanded,
        isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        isTabletop = info.windowPosture.isTabletop,
        hasVerticalHinge = hinges.any { it.isVertical },
    )
}
