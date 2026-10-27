package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.zcode.android.features.conversation.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.PlanState

/**
 * D2 (gaps-and-polish Phase 4C): the goal + todo data surfaces the wire always
 * carried and the phone never rendered.
 *  - [GoalPanel]: the /goal plane — objective, status, iteration AND the desktop's
 *    pass/fail verification timeline (chat goal panel parity);
 *  - [PlanPanel]: the todo projection (TodoWrite restore display).
 * Both are pinned headers under the status strip; absent keys render nothing.
 */
@Composable
fun GoalPanel(
    goal: GoalState,
    modifier: Modifier = Modifier,
    /**
     * F-UX-PANELS (field 2026-10-26): the conversation renders goal + todo panels
     * COLLAPSED by default — one tappable summary line; expanded shows the full
     * surface. The chat timeline is the primary content (the field report: the
     * todos panel took half the screen and could not be minimized).
     */
    collapsedByDefault: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(!collapsedByDefault) }
    val chevronRotation by animateFloatAsState(if (expanded) 0f else -90f, label = "goal_chevron")
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("goal_panel"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // F-CHAT-FOCUS (field 2026-10-27): the header is a TRUE toggle — expanded
                // AND collapsed states both respond (the one-way expand stranded the user
                // with a permanently open panel above the chat).
                .clickable { expanded = !expanded }
                .testTag("goal_header"),
        ) {
            Text(
                goal.summaryTitle?.takeIf { it.isNotBlank() } ?: goal.objective,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("goal_objective"),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                goal.status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("goal_status"),
            )
            if (goal.iteration > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(com.zcode.android.features.conversation.R.string.goal_iteration, goal.iteration.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).testTag("goal_expand").graphicsRotation(chevronRotation),
            )
        }
        if (expanded && goal.verifications.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            goal.verifications.takeLast(5).forEach { v ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .testTag("goal_verification_row"),
                ) {
                    val (icon, tint) = when (v.outcome) {
                        "pass" -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
                        "failed" -> Icons.Filled.Close to MaterialTheme.colorScheme.error
                        else -> Icons.Filled.Remove to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = v.outcome, tint = tint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(
                            stringResource(com.zcode.android.features.conversation.R.string.goal_verification_iteration, v.iteration.toInt()),
                            v.reason?.takeIf { it.isNotBlank() },
                        ).joinToString(" — "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun PlanPanel(plan: PlanState, modifier: Modifier = Modifier, collapsedByDefault: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(!collapsedByDefault) }
    val chevronRotation by animateFloatAsState(if (expanded) 0f else -90f, label = "plan_chevron")
    val doneCount = plan.items.count { it.status == "completed" }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("plan_panel"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("plan_header"),
        ) {
            Text(
                stringResource(com.zcode.android.features.conversation.R.string.plan_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$doneCount/${plan.items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("plan_progress"),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).graphicsRotation(chevronRotation),
            )
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(4.dp))
        plan.items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .testTag("plan_row"),
            ) {
                val done = item.status == "completed"
                val dot = when (item.status) {
                    "completed" -> MaterialTheme.colorScheme.primary
                    "inProgress" -> LocalZCodeColors.current.accent
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (done) {
                    Icon(Icons.Filled.Check, contentDescription = item.status, tint = dot, modifier = Modifier.size(14.dp))
                } else {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(10.dp)
                            .background(dot, CircleShape),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    ),
                    color = if (item.status == "pending") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


/**
 * A3.12 (Phase 5B): the plans viewer — `conversationPlansV4`'s list rendered as titled
 * step checklists (the desktop's plan-step normalization; D2's todo projection stays the
 * [PlanPanel] above it — one panel surface, two sections). Absent/empty → nothing.
 *
 * F-CHAT-FOCUS (field 2026-10-27): collapsed by default like [GoalPanel]/[PlanPanel],
 * and the header is a TRUE two-way toggle. The collapsed line carries the AGGREGATE
 * progress across plans (`Plans  4/9 ⌄`) — expanded, each plan's own title + count +
 * checklist render below (never duplicating the header's summary).
 */
@Composable
fun PlansViewer(
    plans: List<com.zcode.android.domain.model.ConversationPlan>,
    modifier: Modifier = Modifier,
    collapsedByDefault: Boolean = false,
) {
    if (plans.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(!collapsedByDefault) }
    val chevronRotation by animateFloatAsState(if (expanded) 0f else -90f, label = "plans_chevron")
    val totalDone = plans.sumOf { it.completedCount }
    val totalSteps = plans.sumOf { it.steps.size }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .testTag("plans_viewer"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("plans_header"),
        ) {
            Text(
                stringResource(R.string.plans_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Collapsed only: the single-plan title rides the header (expanded, the
            // per-plan rows below carry their titles — never duplicated).
            if (!expanded && plans.size == 1) {
                Spacer(Modifier.width(8.dp))
                Text(
                    plans.first().title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            // The aggregate rides the COLLAPSED header only — expanded, each plan's own
            // count renders in its row (never two nodes for the same "1/3").
            if (!expanded) {
                Text(
                    "$totalDone/$totalSteps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("plans_progress"),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).graphicsRotation(chevronRotation),
            )
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(6.dp))
        plans.forEachIndexed { index, plan ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("plan_title_expanded_$index"),
                )
                Text(
                    "${plan.completedCount}/${plan.steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plan.steps.forEachIndexed { stepIndex, step ->
                Row(
                    Modifier.padding(start = 12.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (step.status) {
                            com.zcode.android.domain.model.PlanStepStatus.COMPLETED -> "✓"
                            com.zcode.android.domain.model.PlanStepStatus.IN_PROGRESS -> "▸"
                            com.zcode.android.domain.model.PlanStepStatus.PENDING -> "○"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (step.status) {
                            com.zcode.android.domain.model.PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            com.zcode.android.domain.model.PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
                            com.zcode.android.domain.model.PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("plan_step_${index}_$stepIndex"),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        step.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (index != plans.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/** F-UX-PANELS: the collapsed-header chevron — rotates to point down when expanded. */
private fun androidx.compose.ui.Modifier.graphicsRotation(degrees: Float): androidx.compose.ui.Modifier =
    this.rotate(degrees)
