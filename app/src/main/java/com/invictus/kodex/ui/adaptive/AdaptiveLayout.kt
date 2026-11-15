package com.invictus.kodex.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window width breakpoint categories based on Material 3 design guidelines. */
enum class WindowWidthClass {
    /** Phones in portrait (< 600 dp). */
    Compact,

    /** Foldables unfolded, small tablets, phones in landscape (600 dp .. 839 dp). */
    Medium,

    /** Tablets, desktop displays (>= 840 dp). */
    Expanded,
}

/** Returns the current [WindowWidthClass] based on screen width in dp. */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    return when {
        width < 600 -> WindowWidthClass.Compact
        width < 840 -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }
}

/** True when the display width is 600 dp or wider (tablet, foldable unfolded, landscape). */
@Composable
fun isTabletLayout(): Boolean = rememberWindowWidthClass() != WindowWidthClass.Compact

/** True when the display width is 840 dp or wider (large tablets, desktop). */
@Composable
fun isExpandedLayout(): Boolean = rememberWindowWidthClass() == WindowWidthClass.Expanded

/**
 * Standard two-pane layout for tablets and wide screens, mirroring mpvRx's responsive dual-pane architecture:
 * - When in dual-pane mode (e.g. tablet width >= 600dp), renders [firstPane] and [secondPane]
 *   side-by-side separated by a subtle [VerticalDivider].
 * - Weight-based responsive scaling: on screens < 840dp, the first pane takes 0.45f; on >= 840dp it takes 0.40f (or custom [firstPaneRatio]).
 * - Optional fixed [firstPaneWidth] can be specified when a rigid sidebar width is desired.
 * - When not in dual-pane mode, renders either the first or second pane depending on [showSecondPaneOnCompact].
 */
@Composable
fun AdaptiveTwoPaneLayout(
    modifier: Modifier = Modifier,
    isDualPane: Boolean = isTabletLayout(),
    firstPaneWidth: Dp? = null,
    firstPaneRatio: Float? = null,
    showSecondPaneOnCompact: Boolean = false,
    divider: @Composable () -> Unit = {
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    },
    firstPane: @Composable () -> Unit,
    secondPane: @Composable () -> Unit,
) {
    if (isDualPane) {
        val configuration = LocalConfiguration.current
        val effectiveRatio = firstPaneRatio ?: if (configuration.screenWidthDp < 840) 0.45f else 0.40f
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = if (firstPaneWidth != null) {
                    Modifier
                        .width(firstPaneWidth)
                        .fillMaxHeight()
                } else {
                    Modifier
                        .weight(effectiveRatio)
                        .fillMaxHeight()
                },
            ) {
                firstPane()
            }
            divider()
            Box(
                modifier = Modifier
                    .weight(if (firstPaneWidth != null) 1f else (1f - effectiveRatio))
                    .fillMaxHeight(),
            ) {
                secondPane()
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            if (showSecondPaneOnCompact) {
                secondPane()
            } else {
                firstPane()
            }
        }
    }
}

/** Modifier that constrains max width and centers content nicely on tablet screens. */
fun Modifier.tabletContentWidth(maxWidth: Dp = 720.dp): Modifier =
    this.widthIn(max = maxWidth)
