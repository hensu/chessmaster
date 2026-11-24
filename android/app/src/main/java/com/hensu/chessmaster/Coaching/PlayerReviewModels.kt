// Chessmaster — GPL-3.0-or-later
//
// Wire types for the cross-game coach's overview.

package com.hensu.chessmaster

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerReview(
    val headline: String,
    @SerialName("recurring_flaws") val recurringFlaws: List<RecurringFlaw>,
    val strengths: List<String>,
    @SerialName("focus_plan") val focusPlan: List<String>,
    val encouragement: String,
) {
    @Serializable
    data class RecurringFlaw(
        val theme: String,
        val evidence: String,
        val fix: String,
    ) {
        val id: String get() = theme + evidence.take(20)
    }
}

@Serializable
data class PlayerReviewRow(
    val report: PlayerReview? = null,
    @SerialName("games_covered") val gamesCovered: Int? = null,
)

@Serializable
data class PlayerReviewResponse(
    val review: PlayerReviewRow,
)
