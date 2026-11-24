// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Renders the whole-game coaching narrative: summary, phases, weaknesses,
 * study tips. Per-move detail lives on the review stepper above.
 */
@Composable
fun CoachingReportView(report: CoachingReport, onJump: (Int) -> Unit = {}, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Sys.secondary.opacity(0.05), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoachingReportLabel("Coach's report", Icons.Rounded.Psychology, Sys.headline, Sys.primary)

        Text(report.summary, style = Sys.subheadline, color = Sys.primary)

        // The game, phase by phase — the structure players think in.
        Text("Game phases", style = Sys.subheadline.bold(), color = Sys.primary)
        CoachingReportPhaseRow("Opening", report.phaseAssessment.opening)
        CoachingReportPhaseRow("Middlegame", report.phaseAssessment.middlegame)
        val endgame = report.phaseAssessment.endgame
        if (endgame != null && endgame.isNotEmpty()) {
            CoachingReportPhaseRow("Endgame", endgame)
        }

        if (report.weaknesses.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (weakness in report.weaknesses) {
                    Text(
                        weakness.replace("_", " "),
                        style = Sys.caption2,
                        color = Sys.red,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .background(Sys.red.opacity(0.12), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (report.studyTips.isNotEmpty()) {
            Text("What to practice", style = Sys.subheadline.bold(), color = Sys.primary)
            for (tip in report.studyTips) {
                CoachingReportLabel(tip, Icons.Rounded.CheckCircleOutline, Sys.footnote, Sys.primary)
            }
        }

        CoachingReportLabel(report.encouragement, Icons.Rounded.ThumbUpOffAlt, Sys.footnote, Sys.green)
    }
}

@Composable
private fun CoachingReportPhaseRow(phase: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(phase, style = Sys.caption.bold(), color = Sys.secondary)
        Text(text, style = Sys.footnote, color = Sys.primary)
    }
}

/** `Label(text, systemImage:)`: the icon, then the text. */
@Composable
private fun CoachingReportLabel(text: String, icon: ImageVector, style: TextStyle, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((style.fontSize.value + 3).dp))
        Text(text, style = style, color = color)
    }
}
