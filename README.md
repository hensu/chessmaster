# Chess AI (Chessmaster)

A native iOS chess app: play against Stockfish with a lichess-style board, Glicko-2 ratings, clocks, and saved game history — then get AI coaching feedback after each game and retry the critical positions to prove you've learned.

## Structure

- `Chessmaster/` — thin SwiftUI app target (composition root, routing, screens)
- `Packages/` — local SPM packages:
  - `ChessDomain` — game state machine, models, time controls, PGN
  - `EngineKit` — Stockfish (UCI) engine integration
  - `BoardUI` — lichess-style board rendering and input
  - `ClockKit` — drift-free chess clock
  - `RatingKit` — Glicko-2 rating
  - `AnalysisKit` — post-game eval pass and move classification
  - `PersistenceKit` — local storage (GRDB)
  - `SupabaseSync` — auth and cloud sync
  - `AudioKitChess` — sounds and music
  - `PaywallKit` — StoreKit 2 subscriptions
- `appstore/` — App Store screenshot generator

The coaching backend (Supabase Edge Functions, prompts, model routing) is a
separate proprietary service and is not part of this repository. The app is
fully playable offline without it.

## Building

Requires Xcode 26+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
xcodegen generate
open Chessmaster.xcodeproj
```

## License

GPL-3.0-or-later (see `LICENSE`). This app embeds [Stockfish](https://github.com/official-stockfish/Stockfish), which is GPLv3; the complete corresponding source of the app is this repository. Third-party attributions are listed in `COPYING.md`.
