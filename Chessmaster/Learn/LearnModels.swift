// Chessmaster — GPL-3.0-or-later
import Foundation

/// A tactics puzzle from the bundled lichess CC0 set. `moves` are UCI:
/// moves[0] is the opponent's setup move (played automatically), then the
/// solver and the opponent alternate.
struct Puzzle: Codable, Identifiable, Hashable {
    let id: String
    let fen: String
    let moves: [String]
    let rating: Int
    let category: String
}

enum PuzzleCategory: String, CaseIterable, Identifiable {
    case mateIn1 = "mate_in_1"
    case mateIn2 = "mate_in_2"
    case fork
    case pin
    case hangingPiece = "hanging_piece"
    case endgame

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mateIn1: "Mate in 1"
        case .mateIn2: "Mate in 2"
        case .fork: "Forks"
        case .pin: "Pins"
        case .hangingPiece: "Hanging Pieces"
        case .endgame: "Endgames"
        }
    }

    var icon: String {
        switch self {
        case .mateIn1: "crown.fill"
        case .mateIn2: "crown"
        case .fork: "arrow.triangle.branch"
        case .pin: "pin.fill"
        case .hangingPiece: "exclamationmark.triangle.fill"
        case .endgame: "flag.checkered"
        }
    }
}

/// A guided lesson: each step is a position, an instruction, and the
/// accepted moves.
struct Lesson: Codable, Identifiable, Hashable {
    struct Step: Codable, Hashable {
        let fen: String
        let prompt: String
        let expected: [String]
        let success: String
    }

    let id: String
    let title: String
    let subtitle: String
    let steps: [Step]
}

/// Bundled content, loaded once.
enum LearnContent {
    static let puzzles: [Puzzle] = load("puzzles", key: "puzzles")
    static let lessons: [Lesson] = load("lessons", key: "lessons")

    static func puzzles(in category: PuzzleCategory) -> [Puzzle] {
        puzzles.filter { $0.category == category.rawValue }.sorted { $0.rating < $1.rating }
    }

    private static func load<T: Decodable>(_ resource: String, key: String) -> [T] {
        guard let url = Bundle.main.url(forResource: resource, withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let object = try? JSONDecoder().decode([String: [T]].self, from: data)
        else { return [] }
        return object[key] ?? []
    }
}

/// Solve/complete progress, device-local.
@Observable @MainActor
final class LearnProgress {
    private static let puzzleKey = "learn.solvedPuzzles"
    private static let lessonKey = "learn.completedLessons"

    private(set) var solvedPuzzles: Set<String>
    private(set) var completedLessons: Set<String>

    init() {
        solvedPuzzles = Set(UserDefaults.standard.stringArray(forKey: Self.puzzleKey) ?? [])
        completedLessons = Set(UserDefaults.standard.stringArray(forKey: Self.lessonKey) ?? [])
    }

    func markSolved(_ puzzleID: String) {
        solvedPuzzles.insert(puzzleID)
        UserDefaults.standard.set(Array(solvedPuzzles), forKey: Self.puzzleKey)
    }

    func markCompleted(_ lessonID: String) {
        completedLessons.insert(lessonID)
        UserDefaults.standard.set(Array(completedLessons), forKey: Self.lessonKey)
    }

    func solvedCount(in category: PuzzleCategory) -> Int {
        LearnContent.puzzles(in: category).filter { solvedPuzzles.contains($0.id) }.count
    }
}
