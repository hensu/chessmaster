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
- App Store **1.0 is released** (its build train is now CLOSED — uploads to 1.0 fail with "Invalid Pre-Release Train"). Current version is **MARKETING_VERSION 1.0.1**, current build **52**. For a new App Store version you must bump `MARKETING_VERSION` above the last approved version (not just the build number); bump `CURRENT_PROJECT_VERSION` too, then `xcodegen generate`. ASC also rejects duplicate build numbers.
- Uploads are manual (Xcode Organizer). CI does not deploy.
- **1.0.1(51) was REJECTED 2026-08-11** (2.1 stale demo password in ASC; 3.1.2c per-month price more prominent than billed amount; 5.1.1v no account deletion). All fixed in build 52. **App Review demo account is `chessai-review@hensu.co`** (dedicated, never rotate — creds + recreate steps in `~/Desktop/chessai-appreview-account.txt`); the UI-test fixture `chessai-uitest@hensu.co` is separate and must NOT be given to Apple (its password rotates). Custom paywall price rows must show the billed amount as the dominant element, per-month equivalents subordinate.
- **Account deletion (5.1.1v)**: Profile → Delete account (confirmation alert) → `SyncService.deleteAccount()` → backend `delete-account` edge function (service role deletes the auth user; every owned table cascades from auth.users; `subscription_events` is transaction-keyed and retained). Apple wants a physical-device screen recording of the flow in ASC review notes.

## Architecture map

- `Chessmaster/` app target: Home (play tiles, streaks, AdaptiveLevel), Game, Analysis (replay + coaching report), History, Profile, Onboarding (Cal-AI-style quiz, first run only), Paywall.
- `Packages/ChessDomain` — GameSession (rules/state, stable-identity BoardPieces), GameReplay, TimeControl. Piece identity is load-bearing: BoardView animates by BoardPiece.id; never rebuild piece arrays from FEN for stepping UIs (use `GameReplay.boardPieces(atPly:)`).
- `Packages/EngineKit` — UCIEngine + StockfishCpp. `EngineOpponent` (app target) supports `blunderProbability` for sub-level-1 strength. UCIEngine serializes whole search transactions with an async lock (single output mailbox + shared option state), and folds options into `search`/`searchCandidates` (`options:` param, applied synchronously before `position`/`go`) so a transaction is atomic — this is what lets background analysis share the one engine with live opponent play. `search` always forces `MultiPV 1`.
- Analysis-during-play: `EngineEvalCache` (AnalysisKit, FEN-keyed, process-wide `.shared`) is warmed by `AnalysisPrefetcher` (app target) while a game is played — it evaluates each reached position at 250ms during the user's thinking time and pauses on the opponent's turn (GameViewModel drives enqueue/resume/pause from game events; reset on start, stop on end). `GameAnalyzer.evaluate` reads the cache first (a miss just recomputes), so the free post-game analysis opens near-instantly when warm (~10ms vs ~1.25s cold for a short game). FENs match exactly because live `GameSession.currentFEN` and post-game `GameReplay.Ply.fenAfter` use the same ChessKit serializer. Prefetch skipped for training games.
- `Packages/BoardUI`, `ClockKit`, `RatingKit` (Glicko-2), `AnalysisKit`, `PersistenceKit` (GRDB), `PaywallKit` (StoreKit 2), `SupabaseSync`, `AudioKitChess`.
- Adaptive difficulty: `Chessmaster/Home/AdaptiveLevel.swift` — **sticky, per-time-control** Auto level. Seeded once from that category's rating (or onboarding self-rating / level 3 for fresh players), then only moves via `recordResult` (called once per finished rated engine game in Auto mode from `GameViewModel.gameEnded`): +1 after 3 wins in a row, −1 after 3 losses, streak then resets and rebuilds; draws reset it. `level(for:)` is a pure read (seeds on first use), so it never wobbles on re-read. Stored in UserDefaults `autoLevel.<category>` / `autoStreak.<category>`. Was previously recomputed-from-rating every game (boundary flip-flop), and the streak/blunder path was silently dead because it compared `GameRecord.result` against `white_win`/`black_win` — the local value is camelCase (`whiteWin`/`blackWin`); same bug fixed in `Streaks.swift` and `AnalysisViewModel.swift`. Levels 1-5 have BUILT-IN blunder rates (45/30/18/10/5% random moves, StrengthLevel.blunderProbability) because raw Stockfish's floor is ~1200 human Elo (measured 4% player score) — the rates make each rung actually play at its ratingEstimate, which also keeps Glicko updates fair. At level 1, continued losses ease further (+7%/loss past streak, cap 65%). Weak moves are eval-aware since build 35: MultiPV-6 candidates sampled by WeakMovePicker (softmax, temperature = blunderProbability x 500cp) with a small (~rate x 0.12) uniform-howler chance — consistently mediocre, not perfect/terrible coin flips. Re-tune rates from win-rate data.

## Subscription tiers

- Platinum = historical `com.chessmaster.premium.*` product IDs (grandfathered, $7.99/mo, $49.99/yr); Diamond = `com.chessmaster.diamond.*` ($12.99/mo, $79.99/yr); all plans have a 7-day free trial (introductoryOffer P1W). Plan derives from `entitlements.product_id` via `planFor()` in `_shared/coaching.ts` and `Plan.plan(forProductID:)` in PaywallKit — no plan column.
- Tiering: on-device ENGINE analysis (eval/accuracy/best-move/mistake stepper) is FREE for everyone, incl. auto post-game (runPostGameAnalysis has no premium gate). AI coaching report is a HARD PAYWALL: `requestCoaching()` shows the paywall for non-premium and `generate-coaching-report` returns `premium_required` for the free plan (no free weekly). Platinum = unlimited Flash + `PLATINUM_DEEP_REVIEWS` (5) Opus/month; Diamond = all reviews on `COACHING_DEEP_MODEL` (claude-opus-4-8) + exclusive player review (`diamond_required`). Fair-use cap 150/mo. In-app the AI coach is a first-class "Midnight Glow" CTA below the board ("Get AI Coaching", id `getAICoaching`) — dark ink card (`ctaInk`) with emerald sparkles/border, mint subtitle, and a breathing emerald glow (`ctaGlow` state, honors `accessibilityReduceMotion`); non-premium shows the subtitle suffixed "· Premium" with a gold crown, and tapping opens the paywall.
- **Proactive first report** (`Chessmaster/Home/AutoCoach.swift`): a premium (trial or paid) player who has never received a coaching report gets one generated automatically for their latest engine game (≥16 plies) on next app open, shown as a "Your AI coach reviewed your last game" card on Home → Analysis scrolled to the report. Server-confirmed via `SyncService.hasCompletedCoachingReport()` (unknown = don't spend), once per player (`autoCoach.done`), max 3 attempts. Events `auto_coach_started/ready/failed/shown/opened/later`. Shared generation path: `CoachingGenerator`.
- Client: `EntitlementStore.plan` (free/platinum/diamond); `--premium` arg = platinum, `--diamond` = diamond. GA user property `plan`.
- Onboarding paywall (`Chessmaster/Onboarding/OnboardingPaywall.swift`): Cal-AI-style soft paywall shown once, right after **mandatory** sign-up (WelcomeScreen: `.signup` → `.paywall`; the Skip/"Play anyway" paths are removed, so onboarding requires an account — 5.1.1(v) risk accepted). Both tiers with an Annual/Monthly toggle (Diamond pre-selected), 7-day free trial, per-month price shown prominently with "$X billed annually" on its own row + full amount in fine print (Apple flags per-month that hides the annual charge). App Store payment only via `EntitlementStore.purchase` (native sheet); Terms/Privacy/Restore; the close ✕ fades in after ~6s so it's dismissible. `source:"onboarding"` analytics. RootView presents the welcome cover via a decide-once `welcomePresented` state (survives sign-in mid-flow). Trial only renders when the introductory offer exists in App Store Connect + the user is eligible; the StoreKit **simulator** doesn't surface config-file intro offers, so verify trial UI on device / with an eligible account.

## Backend (separate private repo)

The Supabase backend (edge functions, coaching prompts, migrations) lives in
**github.com/hensu/chessmaster-backend** (private) — split out 2026-07-16 so
this GPL client repo could go public. Facts below describe the deployed
service this client talks to.

## Backend service (Supabase, project ref ucpaqfjmicxfygzhquqo)

- Deploy: `supabase functions deploy <name>` (needs `supabase login` in a real terminal — the token persists globally).
- `generate-coaching-report`: per-game report. Tiered gemini-3.1-pro-preview (first 15/mo) → gemini-3.5-flash; routes claude-*/gpt-* by prefix. Prompt/schema in `functions/_shared/coaching.ts`.
- `generate-player-review`: cross-game overview, `PLAYER_REVIEW_MODEL` env, default gemini-3.5-flash (won the 2026-07 bake-off round 2 on grounding-per-dollar; Opus 4.8 was previous default).
- Receipt verification (rebuilt 2026-08-13): `verify-subscription` and the App Store webhook do NOT verify Apple JWS crypto — the Apple library's chain verifier can't run on Supabase's Deno (X509Certificate stubs throw ERR_NOT_IMPLEMENTED; it silently broke ALL purchase registration since launch). Instead, JWS is an untrusted hint; the authoritative transaction is fetched from Apple's App Store Server API over TLS (`_shared/appstore-api.ts`; APPSTORE_KEY_ID/ISSUER_ID/PRIVATE_KEY secrets = team ASC key 736XCSM36H). Never reintroduce SignedDataVerifier. config.toml pins verify_jwt=false for appstore-notifications/health-check.
- Secrets set on the project: GEMINI_API_KEY, ANTHROPIC_API_KEY, OPENAI_API_KEY, model overrides. Gemini key also lives in GCP project chessmaster-502217 (`gcloud services api-keys ...`, key "chessmaster-coaching").
- The SUPABASE_ANON_KEY in project.yml is the public client key — safe to commit.

## Analytics & feature flags

- GA4 is the ONLY analytics sink (Measurement Protocol POST in SyncService.swift — no SDK, GPL reasons; `G-8JG1S0FTK3`). Nothing analytics-related is stored in Supabase (events table dropped in migration 0007). Track via `container.sync.track("name", ["k": "v"])`; `--uitest` runs are excluded.
- Each hit also sends `user_location.country_id` (device region) and a `device` block (OS/version/model/language) — MP gets no IP geo or device detection, so without them GA shows Country/Device as (not set). `engagement_time_msec` is real foreground time since the previous hit (`user_engagement` flushes on background; new `session_id` after 30 min away, driven by scenePhase in ChessmasterApp). Screens are sent as `page_view` (`page_title`) via the `.trackScreen("Name")` modifier — the property is a web stream, so screen_view wouldn't populate Pages and screens.
- Every hit carries user properties: `signed_in`, `premium` (RootView keeps it current), and `flag_<key>` for each feature flag. New event params must be registered as GA4 custom dimensions (Admin) to appear outside Realtime.
- Feature flags / A/B: `app_config` table (migration 0008; key/enabled/ab_split, dashboard-managed, read-only to clients). Gate UI with `container.sync.flag("key")`. `ab_split=true` → stable per-install 50/50 variant. Keys ≤19 chars (GA property-name cap). Cached in UserDefaults `flags.cache`; refreshed at launch in RootView.

## Notifications

- All **local** (no push/APNs yet). Two schedulers, both skip `--uitest`, both fire at the player's median play hour:
  - `StreakNotifier` — evening streak-saver + next-morning milestone, only when a streak (≥2) exists (asks full permission then).
  - `ReengagementNotifier` (`Chessmaster/Notifications/`) — "come back" nudges for lapsed users, personalized from on-device data (near a level-up, last-game loss, favorite time control, unsolved puzzle category, rating). Cadence: day 3/7/14/30 for everyone, plus ~quarterly (120/210/300/390) **for paid users only**.
  - `CoachNudgeNotifier` — for premium players who have never had an AI report (same gate as AutoCoach): "Your AI coach is ready" next day at play hour, plus "Your trial ends tomorrow" the day before `EntitlementStore.trialEndsAt` (StoreKit introductory-offer expiry). Rides ReengagementNotifier.refresh's hooks (runs even when server push owns re-engagement); tap → Play, where AutoCoach delivers the report. Reasons `coach_ready` / `coach_trial_end` in `reengage_opened`.
  - Permission: **full/loud** authorization (`[.alert,.sound,.badge]`) requested via `NotificationPermission.requestFullIfNeeded()` after the first **win** (or by the 3rd game) — chess.com-style high-intent ask (build 48; earlier builds used quiet provisional). Notifiers only schedule once authorized. (Re)scheduled from *now* on launch, on background (`ChessmasterApp` scenePhase), and after each game — returning resets the cascade. `NotificationCoordinator` handles taps → tracks `reengage_opened` (params `reason`,`tier`) and routes to Play.
- Phase 2 (server push) is BUILT. Client: `aps-environment` entitlement (via project.yml; `development` = sandbox — flip to `production` for the App Store build), `PushAppDelegate` (@UIApplicationDelegateAdaptor) captures the APNs token, `SyncService.registerDeviceToken` upserts it to `device_tokens` (env sandbox/production via `#if DEBUG`, tz, locale) for signed-in users, cleared for this device on sign-out; when a token is registered the client suppresses its LOCAL re-engagement (`ReengagementNotifier` returns early) so the server owns it. Backend (separate `chessmaster-backend` repo): `0009` migration (device_tokens + reengagement_log), `send-reengagement` edge function (token-guarded, `?dry=1`, per-user dormancy targeting mirroring the client tiers, 60-day dedup, ES256 APNs sender in `_shared/apns.ts`, dead-token pruning), daily `reengage.yml` cron. **Not live yet**: needs the APNs `.p8` secrets (`APNS_KEY_ID`/`APNS_TEAM_ID`/`APNS_PRIVATE_KEY`/`APNS_TOPIC`) set on the Supabase project + the `REENGAGE_TOKEN` GitHub secret, plus a new build. Verified: migration deployed, function dry-run 200, targeting unit-tested, client token upload confirmed live (sandbox row with correct tz/locale).

## App Store review prompt

- `Chessmaster/ReviewPrompt.swift` — `AppStore.requestReview` at happy moments: engine-game win (GameViewModel), leaving Analysis with a coaching report, 5 first-try puzzle solves in a launch. Gates: ≥1 finished engine game, once per app version, ≥30 days apart, skipped while notification permission is still undetermined (that alert is also asked after a win). No pre-screen (guideline 5.6.1). Event `review_requested` (`trigger`).

## Learn tab (puzzles + lessons)

- `Chessmaster/Learn/` — LearnScreen (tab root), PuzzleScreen/PuzzleViewModel, LessonScreen/LessonViewModel, LearnModels (content loaders + LearnProgress in UserDefaults).
- Content bundles: `Chessmaster/Resources/Learn/puzzles.json` (**1369** curated lichess CC0 puzzles: **11 categories** (mate in 1/2/3, fork, pin, skewer, discovered attack, double check, deflection, hanging piece, endgame) × ~126, spread ~18 per rating band across a **difficulty ladder 600→2879** so it's not all-easy; `{"puzzles":[…]}`, moves[0] is the opponent's setup move). Categories are `PuzzleCategory` (LearnModels.swift) — the curation script maps each rawValue → its lichess theme. and `lessons.json` (**16** hand-written guided lessons — piece basics → patterns: fork/pin/skewer, K+Q & smothered mate, promotion, castling; `expected` is a list of accepted UCI moves per step). New lessons are authored + engine-validated (python-chess: every FEN parses, every move legal, mate/check steps verified) — see the curation script shape; never copy lichess lesson prose (not CC0).
- Puzzle/lesson boards reuse GameSession (humanLocal) + BoardView; wrong moves rebuild the session at the solved prefix. Alternative checkmates count as solved.
- To refresh/extend puzzles: stream `database.lichess.org/lichess_db_puzzle.csv.zst` (CC0, ~302MB) → `zstd -dc | python3 curate.py`, filtering by theme (→ our category), popularity≥85, plays≥100, RD≤100, and bucketing ~18 per rating band per category for a difficulty ladder. The 740-puzzle set was built this way (2026-08-01).

## Conventions

- Keep this file updated when architecture, process, or backend facts change — future sessions rely on it (past progress was lost because it didn't exist until 2026-07-13).
- UI test launch args gate app behavior: `--uitest`, `--reset-welcome` (forces full onboarding quiz), `--uitest-signin-*`, `--autostart*`.
