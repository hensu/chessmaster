// Chessmaster — GPL-3.0-or-later
import Foundation

/// Wordle-style daily puzzle: everyone gets the same puzzle on the same
/// (local) day, numbered from launch day. Solving it builds a streak and
/// produces a shareable result. Device-local, like LearnProgress.
enum DailyPuzzle {
    /// Daily #1. Puzzles are picked by day number, so this must never change.
    private static let epoch = DateComponents(calendar: .current, year: 2026, month: 11, day: 1)

    private static let streakKey = "daily.streak"
    private static let lastDayKey = "daily.lastSolvedDay"
    private static let resultsKey = "daily.results"   // "day" → attempts (negative = used a hint)

    /// Today's number (#1 on the epoch day).
    static func number(on date: Date = .now) -> Int {
        let calendar = Calendar.current
        guard let start = epoch.date else { return 1 }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: start),
                                           to: calendar.startOfDay(for: date)).day ?? 0
        return max(1, days + 1)
    }

    /// Mid-difficulty pool so a daily is satisfying for most players.
    private static let pool: [Puzzle] = LearnContent.puzzles
        .filter { (800...1700).contains($0.rating) }
        .sorted { $0.id < $1.id }

    static func puzzle(number: Int = number()) -> Puzzle? {
        guard !pool.isEmpty else { return nil }
        // Stride by a prime so consecutive days hop across themes/ratings.
        return pool[(number * 7919) % pool.count]
    }

    // MARK: - Results and streak

    struct Result {
        let attempts: Int
        let usedHint: Bool
    }

    static func result(number: Int = number()) -> Result? {
        let results = UserDefaults.standard.dictionary(forKey: resultsKey) as? [String: Int] ?? [:]
        guard let value = results[String(number)] else { return nil }
        return Result(attempts: abs(value), usedHint: value < 0)
    }

    /// Records today's first solve; later replays don't change it.
    /// Returns the streak after recording.
    @discardableResult
    static func recordSolve(attempts: Int, usedHint: Bool, number: Int = number()) -> Int {
        let defaults = UserDefaults.standard
        var results = defaults.dictionary(forKey: resultsKey) as? [String: Int] ?? [:]
        guard results[String(number)] == nil else { return streak(today: number) }
        results[String(number)] = usedHint ? -attempts : attempts
        defaults.set(results, forKey: resultsKey)

        let last = defaults.integer(forKey: lastDayKey)
        let previous = defaults.integer(forKey: streakKey)
        let streak = last == number - 1 ? previous + 1 : 1
        defaults.set(streak, forKey: streakKey)
        defaults.set(number, forKey: lastDayKey)
        return streak
    }

    /// Current streak: alive if today's or yesterday's daily was solved.
    static func streak(today: Int = number()) -> Int {
        let defaults = UserDefaults.standard
        let last = defaults.integer(forKey: lastDayKey)
        return last >= today - 1 ? defaults.integer(forKey: streakKey) : 0
    }

    /// "Chess AI Daily #12 / 🟥🟩 / 🔥 5-day streak / link".
    static func shareText(number: Int = number()) -> String? {
        guard let result = result(number: number) else { return nil }
        let squares = String(repeating: "🟥", count: max(0, result.attempts - 1)) + "🟩"
        let hint = result.usedHint ? " 💡" : ""
        let tries = result.attempts == 1 ? "first try" : "\(result.attempts) tries"
        var lines = ["Chess AI Daily #\(number)", "\(squares)\(hint) (\(tries))"]
        let streak = streak(today: number)
        if streak > 1 { lines.append("🔥 \(streak)-day streak") }
        lines.append("https://getchessai.com/daily/")
        return lines.joined(separator: "\n")
    }
}
