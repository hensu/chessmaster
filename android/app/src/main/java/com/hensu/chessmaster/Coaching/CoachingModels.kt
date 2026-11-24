// Chessmaster — GPL-3.0-or-later
//
// Wire types for the coaching report (mirrors the backend REPORT_SCHEMA).

package com.hensu.chessmaster

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CoachingReport(
    val summary: String,
    @SerialName("phase_assessment") val phaseAssessment: PhaseAssessment,
    @SerialName("key_moments") val keyMoments: List<KeyMoment>,
    val weaknesses: List<String>,
    @SerialName("study_tips") val studyTips: List<String>,
    val encouragement: String,
) {
    @Serializable
    data class PhaseAssessment(
        val opening: String,
        val middlegame: String,
        val endgame: String? = null,
    )

    @Serializable
    data class KeyMoment(
        val ply: Int,
        @SerialName("move_played") val movePlayed: String,
        @SerialName("better_move") val betterMove: String,
        @SerialName("what_went_wrong") val whatWentWrong: String,
        @SerialName("better_plan") val betterPlan: String,
        val theme: String,
    ) {
        val id: Int get() = ply
    }
}

/** Row shape returned by the generate-coaching-report function. */
@Serializable
data class CoachingReportRow(
    val status: String,
    val report: CoachingReport? = null,
)

@Serializable
data class CoachingResponse(
    val report: CoachingReportRow,
)
