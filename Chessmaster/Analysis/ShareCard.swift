// Chessmaster — GPL-3.0-or-later
import SwiftUI
import AnalysisKit
import BoardUI
import ChessDomain
import PersistenceKit

/// What the share card shows, pulled from the engine review (free) and,
/// when it exists, the coach's note on the same moment.
struct ShareCardContent {
    struct Moment {
        let pieces: [BoardPiece]
        let arrows: [BoardArrow]
        let moveLabel: String      // "18. Qxb7??"
        let drop: Int              // win chance lost, percent
        let coachLine: String?
    }

    let headline: String           // "Won vs Chess AI Level 5"
    let accuracy: Int
    let playedLike: Int
    let orientation: Piece.Color
    let moment: Moment?
}

extension AnalysisViewModel {
    /// nil until the engine review exists.
    var shareCardContent: ShareCardContent? {
        guard let analysis, let replay else { return nil }
        let userIsWhite = game.playerColor != "black"
        let accuracy = userIsWhite ? analysis.accuracyWhite : analysis.accuracyBlack

        let worst = userMistakes
            .compactMap { eval in dropFor(ply: eval.ply).map { (eval, $0) } }
            .max { $0.1 < $1.1 }
        var moment: ShareCardContent.Moment?
        if let (eval, drop) = worst, drop >= 8 {
            var arrows: [BoardArrow] = []
            if let played = OpponentMove(uci: eval.uci) {
                arrows.append(BoardArrow(from: played.from, to: played.to, color: .red))
            }
            if let bestUCI = eval.bestMoveUCI, let best = OpponentMove(uci: bestUCI) {
                arrows.append(BoardArrow(from: best.from, to: best.to, color: .green))
            }
            let number = (eval.ply + 1) / 2
            moment = .init(
                pieces: replay.boardPieces(atPly: eval.ply - 1),
                arrows: arrows,
                moveLabel: "\(number)\(eval.moverIsWhite ? "." : "...") \(eval.san)\(eval.classification.glyph)",
                drop: Int(drop.rounded()),
                coachLine: coachNote(forPly: eval.ply)?.whatWentWrong
            )
        }

        return ShareCardContent(
            headline: shareHeadline,
            accuracy: Int(accuracy.rounded()),
            playedLike: Self.playedLikeRating(accuracy: accuracy),
            orientation: userIsWhite ? .white : .black,
            moment: moment
        )
    }

    private var shareHeadline: String {
        let userIsWhite = game.playerColor != "black"
        let won = (game.result == "whiteWin" && userIsWhite) || (game.result == "blackWin" && !userIsWhite)
        let verb = game.result == "draw" ? "Drew" : (won ? "Won" : "Lost")
        let opponent: String
        switch game.opponentType {
        case "engine": opponent = game.engineLevel.map { "Chess AI Level \($0)" } ?? "Chess AI"
        case "friend": opponent = "a friend"
        default: opponent = "an opponent"
        }
        return "\(verb) vs \(opponent)"
    }

    /// A playful "played like" number from accuracy (rounded to 50). Not a
    /// rating: a rough curve so 70% reads ~1100 and 90% ~1800.
    static func playedLikeRating(accuracy: Double) -> Int {
        let a = min(max(accuracy, 30), 99)
        let raw = a < 70 ? 400 + (a - 50) * 35 : 1100 + (a - 70) * 35
        return max(200, Int((raw / 50).rounded()) * 50)
    }
}

/// 4:5 portrait card (1080×1350 at 3x) for Instagram, TikTok, Messages.
struct ShareCardView: View {
    let content: ShareCardContent

    private static let ground = Color(red: 0.078, green: 0.063, blue: 0.051)
    private static let panel = Color(red: 0.122, green: 0.094, blue: 0.075)
    private static let cream = Color(red: 0.941, green: 0.851, blue: 0.710)
    private static let muted = Color(red: 0.651, green: 0.580, blue: 0.490)
    private static let green = Color(red: 0.498, green: 0.722, blue: 0.290)
    private static let red = Color(red: 0.875, green: 0.325, blue: 0.325)

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image("AppIconImage")
                    .resizable()
                    .frame(width: 26, height: 26)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                Text("Chess AI")
                    .font(.system(size: 17, weight: .bold, design: .serif))
                    .foregroundStyle(Self.cream)
                Spacer()
                Text("Game review")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Self.muted)
            }

            Text(content.headline)
                .font(.system(size: 22, weight: .bold, design: .serif))
                .foregroundStyle(.white)

            HStack(spacing: 10) {
                stat("\(content.accuracy)%", "Accuracy")
                stat("~\(content.playedLike)", "Played like")
            }

            if let moment = content.moment {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("My biggest mistake")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Self.muted)
                        Spacer()
                        Text(moment.moveLabel)
                            .font(.system(size: 15, weight: .heavy, design: .monospaced))
                            .foregroundStyle(Self.red)
                    }
                    BoardView(pieces: moment.pieces, orientation: content.orientation,
                              arrows: moment.arrows, showsCoordinates: false, interactive: false)
                        .frame(width: 140, height: 140)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .frame(maxWidth: .infinity)
                    Text(moment.coachLine ?? "Cost me \(moment.drop)% win chance. The green arrow was the move.")
                        .font(.system(size: 13))
                        .foregroundStyle(.white.opacity(0.9))
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(12)
                .background(Self.panel, in: RoundedRectangle(cornerRadius: 14))
            } else {
                VStack(spacing: 6) {
                    Text("🎯")
                        .font(.system(size: 40))
                    Text("No big mistakes this game")
                        .font(.system(size: 17, weight: .bold, design: .serif))
                        .foregroundStyle(Self.green)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 30)
                .background(Self.panel, in: RoundedRectangle(cornerRadius: 14))
            }

            Spacer(minLength: 0)

            HStack {
                Text("Can you beat my accuracy?")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Self.cream)
                Spacer()
                Text("getchessai.com")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Self.green)
            }
        }
        .padding(20)
        .frame(width: 360, height: 450)
        .background(Self.ground)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(size: 30, weight: .heavy).monospacedDigit())
                .foregroundStyle(.white)
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Self.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Self.panel, in: RoundedRectangle(cornerRadius: 12))
    }

    /// Rendered card for the share sheet.
    @MainActor
    static func image(for content: ShareCardContent) -> Image? {
        let renderer = ImageRenderer(content: ShareCardView(content: content))
        renderer.scale = 3
        return renderer.uiImage.map { Image(uiImage: $0) }
    }
}
