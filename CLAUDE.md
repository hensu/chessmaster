# Chessmaster ("Chess AI")

iOS chess app: play rated games vs embedded Stockfish, get AI coaching reports.
SwiftUI app + local Swift packages + Supabase backend. GPL-3.0-or-later (Stockfish).

## Setup after clone

```sh
./scripts/fetch-nnue.sh     # NNUE nets (gitignored, ~112 MB) into Chessmaster/Resources/NNUE
xcodegen generate           # project.yml is the source of truth for the .xcodeproj AND Info.plist
```

## Build & test

- Simulator build (Apple Silicon): `xcodebuild -project Chessmaster.xcodeproj -scheme Chessmaster -destination 'generic/platform=iOS Simulator' ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build -quiet`
  - `generic` without ARCHS=arm64 fails: EngineKit compiles Stockfish with `-march=armv8.2-a+dotprod`, which breaks the x86_64 slice.
- Package tests: `cd Packages/<Pkg> && swift test` (CI runs all packages; see .github/workflows/ci.yml).
- Live UI tests (LiveBackendTests/LiveCoachingTests) need fixture credentials via env: `TEST_RUNNER_UITEST_EMAIL` / `TEST_RUNNER_UITEST_PASSWORD` (never hardcode — public repo). They XCTSkip when unset.

## Versioning & TestFlight

- `project.yml` → `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` are the ONLY place to bump versions; Info.plist references them via `$(...)`. Never hardcode versions in Info.plist — xcodegen regenerates it.
- TestFlight train is 1.0; highest uploaded build is **18** (repo is at 22). Bump `CURRENT_PROJECT_VERSION` above the last upload before every archive, then `xcodegen generate`.
- Uploads are manual (Xcode Organizer). CI does not deploy.

## Architecture map

- `Chessmaster/` app target: Home (play tiles, streaks, AdaptiveLevel), Game, Analysis (replay + coaching report), History, Profile, Onboarding (Cal-AI-style quiz, first run only), Paywall.
- `Packages/ChessDomain` — GameSession (rules/state, stable-identity BoardPieces), GameReplay, TimeControl. Piece identity is load-bearing: BoardView animates by BoardPiece.id; never rebuild piece arrays from FEN for stepping UIs (use `GameReplay.boardPieces(atPly:)`).
- `Packages/EngineKit` — UCIEngine + StockfishCpp. `EngineOpponent` (app target) supports `blunderProbability` for sub-level-1 strength.
- `Packages/BoardUI`, `ClockKit`, `RatingKit` (Glicko-2), `AnalysisKit`, `PersistenceKit` (GRDB), `PaywallKit` (StoreKit 2), `SupabaseSync`, `AudioKitChess`.
- Adaptive difficulty: `Chessmaster/Home/AdaptiveLevel.swift` — level from rating, ±1 on 3-game streaks. Levels 1-5 have BUILT-IN blunder rates (45/30/18/10/5% random moves, StrengthLevel.blunderProbability) because raw Stockfish's floor is ~1200 human Elo (measured 4% player score) — the rates make each rung actually play at its ratingEstimate, which also keeps Glicko updates fair. At level 1, continued losses ease further (+7%/loss past streak, cap 65%). Weak moves are eval-aware since build 35: MultiPV-6 candidates sampled by WeakMovePicker (softmax, temperature = blunderProbability x 500cp) with a small (~rate x 0.12) uniform-howler chance — consistently mediocre, not perfect/terrible coin flips. Re-tune rates from win-rate data.

## Subscription tiers

- Platinum = historical `com.chessmaster.premium.*` product IDs (grandfathered, $7.99/$59.99); Diamond = `com.chessmaster.diamond.*` ($12.99/$99.99). Plan derives from `entitlements.product_id` via `planFor()` in `_shared/coaching.ts` and `Plan.plan(forProductID:)` in PaywallKit — no plan column.
- Tiering: Free = 1 Flash review/week (Monday-keyed `free_reviews` counter); Platinum = unlimited Flash + `PLATINUM_DEEP_REVIEWS` (5) Opus/month; Diamond = all reviews on `COACHING_DEEP_MODEL` (claude-opus-4-8) + exclusive player review (`diamond_required` gate). Fair-use cap 150/mo for paid.
- Client: `EntitlementStore.plan` (free/platinum/diamond); `--premium` arg = platinum, `--diamond` = diamond. GA user property `plan`.

## Backend (separate private repo)

The Supabase backend (edge functions, coaching prompts, migrations) lives in
**github.com/hensu/chessmaster-backend** (private) — split out 2026-07-16 so
this GPL client repo could go public. Facts below describe the deployed
service this client talks to.

## Backend service (Supabase, project ref ucpaqfjmicxfygzhquqo)

- Deploy: `supabase functions deploy <name>` (needs `supabase login` in a real terminal — the token persists globally).
- `generate-coaching-report`: per-game report. Tiered gemini-3.1-pro-preview (first 15/mo) → gemini-3.5-flash; routes claude-*/gpt-* by prefix. Prompt/schema in `functions/_shared/coaching.ts`.
- `generate-player-review`: cross-game overview, `PLAYER_REVIEW_MODEL` env, default gemini-3.5-flash (won the 2026-07 bake-off round 2 on grounding-per-dollar; Opus 4.8 was previous default).
- Secrets set on the project: GEMINI_API_KEY, ANTHROPIC_API_KEY, OPENAI_API_KEY, model overrides. Gemini key also lives in GCP project chessmaster-502217 (`gcloud services api-keys ...`, key "chessmaster-coaching").
- The SUPABASE_ANON_KEY in project.yml is the public client key — safe to commit.

## Analytics & feature flags

- GA4 is the ONLY analytics sink (Measurement Protocol POST in SyncService.swift — no SDK, GPL reasons; `G-8JG1S0FTK3`). Nothing analytics-related is stored in Supabase (events table dropped in migration 0007). Track via `container.sync.track("name", ["k": "v"])`; `--uitest` runs are excluded.
- Every hit carries user properties: `signed_in`, `premium` (RootView keeps it current), and `flag_<key>` for each feature flag. New event params must be registered as GA4 custom dimensions (Admin) to appear outside Realtime.
- Feature flags / A/B: `app_config` table (migration 0008; key/enabled/ab_split, dashboard-managed, read-only to clients). Gate UI with `container.sync.flag("key")`. `ab_split=true` → stable per-install 50/50 variant. Keys ≤19 chars (GA property-name cap). Cached in UserDefaults `flags.cache`; refreshed at launch in RootView.

## Learn tab (puzzles + lessons)

- `Chessmaster/Learn/` — LearnScreen (tab root), PuzzleScreen/PuzzleViewModel, LessonScreen/LessonViewModel, LearnModels (content loaders + LearnProgress in UserDefaults).
- Content bundles: `Chessmaster/Resources/Learn/puzzles.json` (240 curated lichess CC0 puzzles, 6 categories × 40, mirrors the coaching theme taxonomy; moves[0] is the opponent's setup move) and `lessons.json` (hand-written guided lessons; `expected` is a list of accepted UCI moves per step).
- Puzzle/lesson boards reuse GameSession (humanLocal) + BoardView; wrong moves rebuild the session at the solved prefix. Alternative checkmates count as solved.
- To refresh/extend puzzles: stream `database.lichess.org/lichess_db_puzzle.csv.zst` (CC0), filter by theme/rating/popularity — see git history for the curation script shape.

## Conventions

- Keep this file updated when architecture, process, or backend facts change — future sessions rely on it (past progress was lost because it didn't exist until 2026-07-13).
- UI test launch args gate app behavior: `--uitest`, `--reset-welcome` (forces full onboarding quiz), `--uitest-signin-*`, `--autostart*`.
