// Chessmaster — GPL-3.0-or-later
//
// Wire types for the cross-game coach's overview.

import Foundation

struct PlayerReview: Codable, Hashable {
    struct RecurringFlaw: Codable, Hashable, Identifiable {
        let theme: String
        let evidence: String
        let fix: String
        var id: String { theme + evidence.prefix(20) }
    }

    let headline: String
    let recurringFlaws: [RecurringFlaw]
    let strengths: [String]
    let focusPlan: [String]
    let encouragement: String

    enum CodingKeys: String, CodingKey {
        case headline
        case recurringFlaws = "recurring_flaws"
        case strengths
        case focusPlan = "focus_plan"
        case encouragement
    }
}

struct PlayerReviewRow: Codable {
    let report: PlayerReview?
    let gamesCovered: Int?

    enum CodingKeys: String, CodingKey {
        case report
        case gamesCovered = "games_covered"
    }
}

struct PlayerReviewResponse: Codable {
    let review: PlayerReviewRow
}
