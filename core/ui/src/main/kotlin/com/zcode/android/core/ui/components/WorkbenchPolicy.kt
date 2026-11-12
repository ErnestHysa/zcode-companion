package com.zcode.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.R

/**
 * G61 (vc52 Phase 13): the list-detail workbench. The audit: at 1280 dp the task
 * list centred a column while everything else stretched edge-to-edge and NO
 * list-detail pane existed for tablets / foldables / DeX. The phone layout is
 * UNCHANGED below the breakpoint ([useListDetail] is false at 411 dp and every
 * other phone width).
 */
object WorkbenchPolicy {

    /**
     * The roadmap breakpoint (≥600 dp — Material's Medium width class start).
     * [widthDp] is the window's screenWidthDp (the same source readerWidth uses).
     */
    fun useListDetail(widthDp: Int): Boolean = widthDp >= 600

    /** The list pane's share of the workbench row (capped so prose stays readable). */
    val ListPaneWeight = 0.42f
    val DetailPaneWeight = 0.58f

    /**
     * The two-pane scaffold: list | divider | detail. [detail] null = no task
     * opened yet — the honest empty pane names the affordance (a tap opens the
     * selected task; it is never a blank hole).
     */
    @Composable
    fun WorkbenchScaffold(
        list: @Composable () -> Unit,
        detail: (@Composable () -> Unit)?,
        onOpenFromEmpty: () -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        Row(modifier.fillMaxSize().testTag("workbench_root")) {
            Box(
                Modifier
                    .weight(ListPaneWeight)
                    .fillMaxHeight()
                    .testTag("workbench_list_pane"),
            ) { list() }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .testTag("workbench_divider")
                    .padding(vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Box(
                Modifier
                    .weight(DetailPaneWeight)
                    .fillMaxHeight()
                    .testTag("workbench_detail_pane"),
            ) {
                if (detail != null) {
                    detail()
                } else {
                    EmptyDetailPane(onOpen = onOpenFromEmpty)
                }
            }
        }
    }

    @Composable
    private fun EmptyDetailPane(onOpen: () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("workbench_detail_empty")
                .clickableNoRipple(onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.workbench_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }

    private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
        this.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        )
}
