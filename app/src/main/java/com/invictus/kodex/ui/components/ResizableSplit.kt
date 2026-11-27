package com.invictus.kodex.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A draggable vertical splitter handle used between side-by-side panes (e.g. file explorer
 * sidebar and code editor, or vertical terminal split on tablets).
 */
@Composable
fun VerticalSplitHandle(
    onDragDeltaPx: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (dragging) 2.5.dp else 1.dp, label = "vSplitThickness")
    val color by animateColorAsState(
        if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        label = "vSplitColor",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .zIndex(1f)
            .layout { measurable, constraints ->
                val hitPx = 24.dp.roundToPx()
                val visualPx = 1.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = hitPx, maxWidth = hitPx),
                )
                layout(visualPx, placeable.height) {
                    placeable.placeRelative(-((hitPx - visualPx) / 2), 0)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount.x)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = thickness,
            color = color,
        )
    }
}

/**
 * A draggable horizontal splitter handle used between stacked panes (e.g. code editor on top
 * and horizontal terminal split on bottom on tablets).
 */
@Composable
fun HorizontalSplitHandle(
    onDragDeltaPx: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(if (dragging) 2.5.dp else 1.dp, label = "hSplitThickness")
    val color by animateColorAsState(
        if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        label = "hSplitColor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1f)
            .layout { measurable, constraints ->
                val hitPx = 24.dp.roundToPx()
                val visualPx = 1.dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = hitPx, maxHeight = hitPx),
                )
                layout(placeable.width, visualPx) {
                    placeable.placeRelative(0, -((hitPx - visualPx) / 2))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    onDragDeltaPx(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = thickness,
            color = color,
        )
    }
}
