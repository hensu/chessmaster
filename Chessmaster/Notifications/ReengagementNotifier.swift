// Chessmaster — GPL-3.0-or-later
import Foundation
import UserNotifications
import PersistenceKit
import EngineKit
import PaywallKit

/// Local "come back and play" notifications for lapsed users. Each time the
/// app is used we (re)schedule a cascade of future reminders measured from
/// *now*; returning to the app reschedules them, so an active player never
/// sees one. Content is personalized from on-device history.
///
/// Cadence: day 3 / 7 / 14 / 30 for everyone; after day 30, a ~quarterly
/// win-back for paid users only. Delivery is provisional (quiet) unless the
/// player has already granted full notification permission — no hard prompt.
@MainActor
enum ReengagementNotifier {
    private static let idPrefix = "reengage-"
    private static let everyoneTiers = [3, 7, 14, 30]
    /// After day 30, ~every 90 days for a year — paid users only.
    private static let paidQuarterlyTiers = [120, 210, 300, 390]

    // MARK: - Entry point

    static func refresh(container: DependencyContainer) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        // Once the backend can push this signed-in user (token registered),
        // the server owns re-engagement — don't also fire local ones.
        if container.sync.isSignedIn, container.sync.hasRemoteToken {
            cancelAll()
            return
        }
        let games = (try? container.games.recentGames()) ?? []
        let rating = container.ratings.ratings.values
            .filter { $0.deviation < 250 }
            .map { Int($0.rating.rounded()) }
            .filter { $0 >= 400 }
            .max()
        let isPaid = container.entitlements.plan != .free
        let unsolvedCategory = PuzzleCategory.allCases.first {
            container.learnProgress.solvedCount(in: $0) < LearnContent.puzzles(in: $0).count
        }?.title
        let signals = Signals(
            games: games,
            rating: rating,
            isPaid: isPaid,
            lostLast: lastWasLoss(games: games),
            favoriteTimeControl: favoriteTimeControl(games: games),
            nearNextLevel: nearNextLevel(rating: rating),
            unsolvedPuzzleCategory: unsolvedCategory
        )
        Task { await schedule(signals) }
    }

    static func cancelAll() {
        let ids = (everyoneTiers + paidQuarterlyTiers).map { "\(idPrefix)\($0)" }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    // MARK: - Scheduling

    private struct Signals {
        let games: [GameRecord]
        let rating: Int?
        let isPaid: Bool
        let lostLast: Bool
        let favoriteTimeControl: String
        let nearNextLevel: (points: Int, name: String)?
        let unsolvedPuzzleCategory: String?
    }

    private static func schedule(_ s: Signals) async {
        // Need real history before nudging — nothing to personalize otherwise.
        guard s.games.contains(where: { $0.termination != "aborted" }) else {
            cancelAll(); return
        }

        let center = UNUserNotificationCenter.current()
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            // .provisional grants quietly, with no permission prompt.
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge, .provisional])
            status = await center.notificationSettings().authorizationStatus
        }
        guard status == .authorized || status == .provisional else { return }

        cancelAll()
        let cal = Calendar.current
        let now = Date()
        let hour = usualPlayHour(games: s.games)
        let tiers = s.isPaid ? everyoneTiers + paidQuarterlyTiers : everyoneTiers

        for day in tiers {
            guard let base = cal.date(byAdding: .day, value: day, to: now),
                  let fire = cal.date(bySettingHour: hour, minute: 0, second: 0, of: base),
                  fire > now else { continue }
            let copy = message(forDay: day, signals: s)
            let content = UNMutableNotificationContent()
            content.title = copy.title
            content.body = copy.body
            content.sound = .default
            content.userInfo = ["reason": copy.reason, "tier": day, "action": "play"]
            let trigger = UNCalendarNotificationTrigger(
                dateMatching: cal.dateComponents([.year, .month, .day, .hour, .minute], from: fire),
                repeats: false)
            try? await center.add(UNNotificationRequest(
                identifier: "\(idPrefix)\(day)", content: content, trigger: trigger))
        }
    }

    // MARK: - Copy (personalized per tier)

    private static func message(forDay day: Int, signals s: Signals) -> (title: String, body: String, reason: String) {
        switch day {
        case 3:
            if let near = s.nearNextLevel {
                return ("Almost there ♟️",
                        "You're \(near.points) points from \(near.name). One good game could get you there.",
                        "near_level")
            }
            if s.lostLast {
                return ("Rematch?", "That last game didn't go your way. Ready to even the score?", "avenge_loss")
            }
            return ("Your move ♟️", "Your board's set for \(s.favoriteTimeControl). Fancy a quick game?", "favorite_tc")
        case 7:
            if let cat = s.unsolvedPuzzleCategory {
                return ("Keep your edge", "A fresh set of \(cat) puzzles is waiting to sharpen your tactics.", "puzzles")
            }
            return ("Keep your edge", "A week off dulls the tactics. One game to stay sharp?", "stay_sharp")
        case 14:
            if let r = s.rating {
                return ("Your \(r) rating misses you", "Two weeks off — one quick game to knock off the rust?", "rating_miss")
            }
            return ("It's been a while ♟️", "Two weeks since your last game. Ready for a quick one?", "two_weeks")
        case 30:
            return ("Your board's still set ♟️", "Right where you left it. Come back for a single game?", "winback_30")
        default:
            // Quarterly win-back — paid users only.
            return ("Your coach is still here", "Whenever you're ready, a game and a fresh AI review are waiting.", "winback_paid_quarterly")
        }
    }

    // MARK: - Signals

    /// The hour the player most often finishes games (median of last 30),
    /// defaulting to 7pm — so reminders land when they actually play.
    private static func usualPlayHour(games: [GameRecord]) -> Int {
        let hours = games
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .prefix(30)
            .map { Calendar.current.component(.hour, from: $0.endedAt) }
            .sorted()
        guard !hours.isEmpty else { return 19 }
        return hours[hours.count / 2]
    }

    /// The player's most-played time control, as a short label ("5+3").
    private static func favoriteTimeControl(games: [GameRecord]) -> String {
        let recent = games.filter { $0.opponentType == "engine" && $0.termination != "aborted" }.prefix(30)
        guard !recent.isEmpty else { return "a quick game" }
        var counts: [String: Int] = [:]
        for g in recent {
            let label: String
            if let initial = g.tcInitialSeconds {
                label = "\(initial / 60)+\(g.tcIncrementSeconds ?? 0)"
            } else {
                label = "a game"
            }
            counts[label, default: 0] += 1
        }
        let best = counts.max { $0.value < $1.value }?.key ?? "a quick game"
        return best == "a game" ? "a quick game" : "a \(best) game"
    }

    /// Whether the most recent rated game was a loss (rating dropped).
    private static func lastWasLoss(games: [GameRecord]) -> Bool {
        guard let last = games.first(where: {
            $0.ratingBefore != nil && $0.ratingAfter != nil && $0.termination != "aborted"
        }) else { return false }
        return (last.ratingAfter ?? 0) < (last.ratingBefore ?? 0) - 0.5
    }

    /// If within 150 points of the next level up, the gap and that level's name.
    private static func nearNextLevel(rating: Int?) -> (points: Int, name: String)? {
        guard let rating else { return nil }
        let next = (1...10)
            .map { StrengthLevel.level($0) }
            .filter { $0.ratingEstimate > rating }
            .min { $0.ratingEstimate < $1.ratingEstimate }
        guard let next, next.ratingEstimate - rating <= 150 else { return nil }
        return (next.ratingEstimate - rating, "Level \(next.level) · \(next.displayName)")
    }
}
