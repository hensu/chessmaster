// Chessmaster — GPL-3.0-or-later
//
// Wire types for the coaching report (mirrors the backend REPORT_SCHEMA).

import Foundation

struct CoachingReport: Codable, Hashable {
    struct PhaseAssessment: Codable, Hashable {
        let opening: String
        let middlegame: String
        let endgame: String?
    }

    struct KeyMoment: Codable, Hashable, Identifiable {
        let ply: Int
        let movePlayed: String
        let betterMove: String
        let whatWentWrong: String
        let betterPlan: String
        let theme: String

        var id: Int { ply }

        enum CodingKeys: String, CodingKey {
            case ply
            case movePlayed = "move_played"
            case betterMove = "better_move"
            case whatWentWrong = "what_went_wrong"
            case betterPlan = "better_plan"
            case theme
        }
    }

    let summary: String
    let phaseAssessment: PhaseAssessment
    let keyMoments: [KeyMoment]
    let weaknesses: [String]
    let studyTips: [String]
    let encouragement: String

    enum CodingKeys: String, CodingKey {
        case summary
        case phaseAssessment = "phase_assessment"
        case keyMoments = "key_moments"
        case weaknesses
        case studyTips = "study_tips"
        case encouragement
    }
}

/// Row shape returned by the generate-coaching-report function.
struct CoachingReportRow: Codable {
    let status: String
    let report: CoachingReport?
}

struct CoachingResponse: Codable {
    let report: CoachingReportRow
}
