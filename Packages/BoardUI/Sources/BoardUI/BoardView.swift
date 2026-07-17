// BoardUI — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import SwiftUI

/// Lichess-style chess board: layered squares, highlights, legal-move dots,
/// pieces, drag layer, and promotion picker. Presentation-only — game rules
/// stay behind the closures so this view also serves read-only replay.
public struct BoardView: View {
    let pieces: [BoardPiece]
    let orientation: Piece.Color
    let lastMove: (from: Square, to: Square)?
    let checkSquare: Square?
    let arrows: [BoardArrow]
    let showsCoordinates: Bool
    let theme: BoardTheme
    let interactive: Bool
    let canSelect: (Square) -> Bool
    let legalTargets: (Square) -> [Square]
    let onMove: (Square, Square) -> Void
    let pendingPromotion: (square: Square, color: Piece.Color)?
    let onPromote: (Piece.Kind) -> Void
    let onCancelPromotion: () -> Void

    @State private var selectedSquare: Square?
    @State private var dragging: DragState?

    private struct DragState {
        var from: Square
        var location: CGPoint
        var piece: BoardPiece
    }

    public init(
        pieces: [BoardPiece],
        orientation: Piece.Color = .white,
        lastMove: (from: Square, to: Square)? = nil,
        checkSquare: Square? = nil,
        arrows: [BoardArrow] = [],
        showsCoordinates: Bool = true,
        theme: BoardTheme = .brown,
        interactive: Bool = true,
        canSelect: @escaping (Square) -> Bool = { _ in false },
        legalTargets: @escaping (Square) -> [Square] = { _ in [] },
        onMove: @escaping (Square, Square) -> Void = { _, _ in },
        pendingPromotion: (square: Square, color: Piece.Color)? = nil,
        onPromote: @escaping (Piece.Kind) -> Void = { _ in },
        onCancelPromotion: @escaping () -> Void = {}
    ) {
        self.pieces = pieces
        self.orientation = orientation
        self.lastMove = lastMove
        self.checkSquare = checkSquare
        self.arrows = arrows
        self.showsCoordinates = showsCoordinates
        self.theme = theme
        self.interactive = interactive
        self.canSelect = canSelect
        self.legalTargets = legalTargets
        self.onMove = onMove
        self.pendingPromotion = pendingPromotion
        self.onPromote = onPromote
        self.onCancelPromotion = onCancelPromotion
    }

    public var body: some View {
        GeometryReader { proxy in
            let squareSize = min(proxy.size.width, proxy.size.height) / 8
            ZStack(alignment: .topLeading) {
                squaresLayer(squareSize: squareSize)
                highlightLayer(squareSize: squareSize)
                dotsLayer(squareSize: squareSize)
                piecesLayer(squareSize: squareSize)
                if !arrows.isEmpty {
                    ArrowsLayer(arrows: arrows, squareSize: squareSize, orientation: orientation)
                }
                dragLayer(squareSize: squareSize)
                if let promo = pendingPromotion {
                    PromotionPickerView(
                        color: promo.color,
                        square: promo.square,
                        squareSize: squareSize,
                        orientation: orientation,
                        onPick: onPromote,
                        onCancel: onCancelPromotion
                    )
                }
            }
            .contentShape(Rectangle())
            .gesture(boardGesture(squareSize: squareSize), including: interactive && pendingPromotion == nil ? .all : .none)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    // MARK: Layers

    private func squaresLayer(squareSize: CGFloat) -> some View {
        Canvas { context, _ in
            for square in Square.allCases {
                let rect = SquareGeometry.rect(of: square, squareSize: squareSize, orientation: orientation)
                let isLight = square.color == .light
                context.fill(Path(rect), with: .color(isLight ? theme.lightSquare : theme.darkSquare))
            }
            if showsCoordinates {
                drawCoordinates(context: context, squareSize: squareSize)
            }
        }
    }

    private func drawCoordinates(context: GraphicsContext, squareSize: CGFloat) {
        let fontSize = max(8, squareSize * 0.22)
        // Rank numbers in the top-left of the leftmost column.
        for rankValue in 1...8 {
            let square = squareOn(file: orientation == .white ? 1 : 8, rank: rankValue)
            let (x, _) = SquareGeometry.gridCoordinates(of: square, orientation: orientation)
            guard x == 0 else { continue }
            let rect = SquareGeometry.rect(of: square, squareSize: squareSize, orientation: orientation)
            let color = square.color == .light ? theme.darkSquare : theme.lightSquare
            context.draw(
                Text(String(rankValue)).font(.system(size: fontSize, weight: .semibold)).foregroundStyle(color),
                at: CGPoint(x: rect.minX + fontSize * 0.45, y: rect.minY + fontSize * 0.55)
            )
        }
        // File letters in the bottom-right of the bottom row.
        for fileNumber in 1...8 {
            let square = squareOn(file: fileNumber, rank: orientation == .white ? 1 : 8)
            let (_, y) = SquareGeometry.gridCoordinates(of: square, orientation: orientation)
            guard y == 7 else { continue }
            let rect = SquareGeometry.rect(of: square, squareSize: squareSize, orientation: orientation)
            let color = square.color == .light ? theme.darkSquare : theme.lightSquare
            let letter = String(Character(UnicodeScalar(96 + fileNumber)!))
            context.draw(
                Text(letter).font(.system(size: fontSize, weight: .semibold)).foregroundStyle(color),
                at: CGPoint(x: rect.maxX - fontSize * 0.45, y: rect.maxY - fontSize * 0.6)
            )
        }
    }

    private func squareOn(file: Int, rank: Int) -> Square {
        Square(rawValue: (rank - 1) * 8 + (file - 1)) ?? .a1
    }

    @ViewBuilder
    private func highlightLayer(squareSize: CGFloat) -> some View {
        if let lastMove {
            squareFill(lastMove.from, color: theme.lastMoveHighlight, squareSize: squareSize)
            squareFill(lastMove.to, color: theme.lastMoveHighlight, squareSize: squareSize)
        }
        if let selectedSquare {
            squareFill(selectedSquare, color: theme.selection, squareSize: squareSize)
        }
        if let checkSquare {
            let rect = SquareGeometry.rect(of: checkSquare, squareSize: squareSize, orientation: orientation)
            RadialGradient(
                colors: [theme.checkCore.opacity(0.9), theme.checkCore.opacity(0.0)],
                center: .center,
                startRadius: squareSize * 0.05,
                endRadius: squareSize * 0.65
            )
            .frame(width: rect.width, height: rect.height)
            .offset(x: rect.minX, y: rect.minY)
        }
    }

    private func squareFill(_ square: Square, color: Color, squareSize: CGFloat) -> some View {
        let rect = SquareGeometry.rect(of: square, squareSize: squareSize, orientation: orientation)
        return color
            .frame(width: rect.width, height: rect.height)
            .offset(x: rect.minX, y: rect.minY)
    }

    @ViewBuilder
    private func dotsLayer(squareSize: CGFloat) -> some View {
        if let selected = selectedSquare {
            ForEach(legalTargets(selected), id: \.self) { target in
                let rect = SquareGeometry.rect(of: target, squareSize: squareSize, orientation: orientation)
                let occupied = pieces.contains { $0.square == target }
                Group {
                    if occupied {
                        // Capture: ring around the edge of the square (lichess style).
                        Circle()
                            .stroke(theme.legalDot, lineWidth: squareSize * 0.09)
                            .frame(width: squareSize * 0.95, height: squareSize * 0.95)
                    } else {
                        Circle()
                            .fill(theme.legalDot)
                            .frame(width: squareSize * 0.33, height: squareSize * 0.33)
                    }
                }
                .position(x: rect.midX, y: rect.midY)
            }
        }
    }

    private func piecesLayer(squareSize: CGFloat) -> some View {
        ForEach(pieces) { piece in
            let center = SquareGeometry.center(of: piece.square, squareSize: squareSize, orientation: orientation)
            pieceImage(kind: piece.kind, color: piece.color)
                .resizable()
                .scaledToFit()
                .frame(width: squareSize, height: squareSize)
                .position(center)
                .opacity(dragging?.piece.id == piece.id ? 0 : 1)
                .animation(.easeOut(duration: 0.12), value: piece.square)
                .accessibilityLabel("\(piece.color == .white ? "White" : "Black") \(String(describing: piece.kind)) on \(piece.square.notation)")
        }
    }

    @ViewBuilder
    private func dragLayer(squareSize: CGFloat) -> some View {
        if let drag = dragging {
            // Ghost target indicator under the finger.
            if let target = SquareGeometry.square(at: drag.location, squareSize: squareSize, orientation: orientation) {
                let rect = SquareGeometry.rect(of: target, squareSize: squareSize, orientation: orientation)
                Circle()
                    .fill(Color.black.opacity(0.12))
                    .frame(width: squareSize * 2, height: squareSize * 2)
                    .position(x: rect.midX, y: rect.midY)
            }
            pieceImage(kind: drag.piece.kind, color: drag.piece.color)
                .resizable()
                .scaledToFit()
                .frame(width: squareSize * 1.6, height: squareSize * 1.6)
                .position(x: drag.location.x, y: drag.location.y - squareSize * 0.6)
                .allowsHitTesting(false)
        }
    }

    // MARK: Input

    private func boardGesture(squareSize: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard let square = SquareGeometry.square(at: value.startLocation, squareSize: squareSize, orientation: orientation) else { return }
                if dragging == nil {
                    // Only lift a piece the user is allowed to move.
                    if let piece = pieces.first(where: { $0.square == square }), canSelect(square) {
                        dragging = DragState(from: square, location: value.location, piece: piece)
                        selectedSquare = square
                    }
                } else {
                    dragging?.location = value.location
                }
            }
            .onEnded { value in
                defer { dragging = nil }
                let endSquare = SquareGeometry.square(at: value.location, squareSize: squareSize, orientation: orientation)

                if let drag = dragging {
                    let moved = value.location.distance(to: value.startLocation) > squareSize * 0.3
                    if moved, let end = endSquare {
                        if end != drag.from {
                            attemptMove(from: drag.from, to: end)
                        } else {
                            selectedSquare = drag.from
                        }
                    }
                    // A stationary press-release is a tap: selection already set,
                    // wait for the second tap.
                    return
                }

                // No piece lifted: this is a tap on a destination (or empty square).
                guard let end = endSquare else { return }
                if let selected = selectedSquare {
                    if selected == end {
                        selectedSquare = nil
                    } else if canSelect(end) {
                        selectedSquare = end
                    } else {
                        attemptMove(from: selected, to: end)
                    }
                }
            }
    }

    private func attemptMove(from: Square, to: Square) {
        if legalTargets(from).contains(to) {
            onMove(from, to)
            selectedSquare = nil
        } else if canSelect(to) {
            selectedSquare = to
        } else {
            selectedSquare = nil
        }
    }
}

private extension CGPoint {
    func distance(to other: CGPoint) -> CGFloat {
        hypot(x - other.x, y - other.y)
    }
}
