# Porting the iOS app to Android

The Android app is a line-for-line port of the SwiftUI app in `../Chessmaster` and the Swift
packages in `../Packages`. Same screens, same copy, same game logic, same content files, same
backend. Kotlin + Jetpack Compose, one Gradle module (`app`). Read this whole file before
porting anything. `../CLAUDE.md` describes what every part of the iOS app does.

## Layout

- All Kotlin lives in ONE package: `package com.hensu.chessmaster`. Like a Swift module, no file
  imports another file of the app. Never create sub-packages.
- Folders mirror the iOS folders under `app/src/main/java/com/hensu/chessmaster/`:
  the app target's `Analysis/ Coaching/ Game/ History/ Home/ Learn/ Notifications/ Onboarding/
  Paywall/ Profile/ Rating/ Settings/`, and one folder per Swift package: `ChessDomain/ EngineKit/
  BoardUI/ ClockKit/ RatingKit/ AnalysisKit/ PersistenceKit/ SupabaseSync/ AudioKitChess/
  PaywallKit/`. `Foo.swift` becomes `Foo.kt` in the same folder. A very long Swift file may be
  split into `Foo.kt`, `Foo2.kt`… in that folder.
- Content is shared, not copied: `../Chessmaster/Resources` is the Android assets root, so
  `Assets.text("Learn/puzzles.json")`, `Learn/lessons.json`, `NNUE/nn-*.nnue`,
  `Onboarding/landing-demo.mp4`. Never edit anything outside `android/`.
- Images are in `res/drawable-nodpi`: pieces `R.drawable.piece_wk` … `piece_bp` (white/black +
  k q r b n p), `brand_google`, `brand_appstore`, `brand_instagram`, `brand_reddit`,
  `brand_tiktok`, `brand_youtube`, `launch_logo`, `app_icon_image`. Sounds are in `res/raw`:
  `move capture castle check promote game_start game_end low_time` (.wav) and `music_loop` (.m4a).

## Already there (use these, don't re-make them)

| iOS | Android |
|---|---|
| ChessKit `Square`, `Piece`, `Piece.Color`, `Piece.Kind`, `Position(fen:)`, `Board(position:)` | `ChessDomain/ChessKit.kt`: `Square.e4`, `Square.of("e4")`, `Square.fromRaw(i)`, `square.notation`, `.file.number`, `.rank.value`; `Piece(kind, color, square)`, `Piece.Color.white.opposite`, `Piece.Kind.queen`; `Position.of(fen)` (nullable; `.pieces`, `.sideToMove`, `.fen`, `.piece(square)`); `Board(position).legalMoves(square)`, `.state` (`Board.State.Checkmate(color)`, `.Check`, `.Draw(reason)`, `.Active`) |
| ChessDomain package (all of it) | `ChessDomain/*.kt`, ported: `GameSession`, `GameConfig` (`GameConfig.Opponent.Engine(level)`, `.HumanLocal`, `.Friend`), `GameResult` (`GameResult.Win(color, termination)`, `.Draw(reason)`, `.Aborted`), `GameStatus` (`.Idle`, `.Playing`, `.Finished(result)`), `GameEvent` (`.GameStarted`, `.MovePlayed(move)`, `.GameEnded(result)`), `PlayedMove`, `BoardPiece`, `LastMove(from, to)`, `PendingPromotion(square, color)`, `MoveOutcome`, `GameReplay.of(pgn)` (nullable), `replay.boardPieces(atPly)`, `OpponentMove.of(uci)`, `OpponentProvider` (interface, `suspend fun nextMove(fen, remainingTime)`), `TimeControl(minutes, incrementSeconds)`, `PGNClockAnnotator`. `session.events` is a `Flow<GameEvent>` (`for await event in session.events` → `session.events.collect { }`). **The .kt files are the truth: read them.** |
| `cm_stockfish_start()` and the two pipe fds | `StockfishBridge.start()`, `.send(command)`, `.readLine()` (blocking; one reader thread) |
| `Color(red:green:blue:)`, `.opacity(x)` | `rgb(r, g, b)`, `hex(0xRRGGBB)`, `color.opacity(x)` |
| `.font(.title.bold())`, `.headline`, `.caption2`, `.system(size:weight:)` | `Sys.title.bold()`, `Sys.headline`, `Sys.caption2`, `Sys.font(size, weight)` (TextStyle) |
| `.foregroundStyle(.secondary)`, `.tertiary`, `.primary`, `Color.green`, `.red`, `.orange`, `.blue`, `Color.accentColor` | `Sys.secondary`, `Sys.tertiary`, `Sys.primary`, `Sys.green`, `Sys.red`, `Sys.orange`, `Sys.blue`, `Sys.accent` (these follow light/dark; never hard-code black/white for text or backgrounds the Swift takes from the system) |
| `Color(.systemBackground)`, `.secondarySystemBackground`, `.systemGroupedBackground`, `.secondarySystemGroupedBackground`, `.tertiarySystemFill`, `.separator` | `Sys.background`, `Sys.secondaryBackground`, `Sys.groupedBackground`, `Sys.secondaryGroupedBackground`, `Sys.tertiaryFill`, `Sys.separator` |
| `@Environment(\.colorScheme) == .dark` | `Sys.dark` |
| `UserDefaults.standard`, `@AppStorage("k")` | `Defaults.standard` (`string`, `integer`, `bool`, `double`, `intOrNull`, `boolOrNull`, `has`, `set`, `remove`, `stringArray`, `json`/`setJson`, `stringMap`, `intMap`); in a composable `val x = appStorageBool("k")` / `appStorageString` / `appStorageInt`. **Keep the exact iOS key names.** A `Date` is stored as epoch seconds (`set(key, instant.epochSecond.toDouble())` style: use `double`). |
| `Bundle.main.url(forResource:)` + `Data(contentsOf:)` | `Assets.text("Learn/puzzles.json")`, `Assets.exists(path)` |
| `JSONDecoder()` / `JSONEncoder()` | `AppJson` (kotlinx.serialization, unknown keys ignored) |
| `NotificationCenter.default.post(name:)` / `.onReceive(...)` | `AppEvents.post("name")` / `OnAppEvent("name") { }` |
| `NavigationStack { }` | `NavStack(navigator) { }` (each tab's navigator is in `AppRouter.shared`: `playNav`, `learnNav`, `historyNav`, `profileNav`) |
| `NavigationLink { Dest() }`, `.navigationDestination(for:)`, `router.playPath.append(x)` | `LocalNav.current.push { Dest() }` / `AppRouter.shared.playNav.push { Dest(x) }` |
| `@Environment(\.dismiss)` then `dismiss()` | `LocalNav.current.pop()` for a pushed screen; for a sheet or cover call its `onDismiss` (add an `onDismiss: () -> Unit` parameter to a view that is shown as a sheet/cover and dismisses itself) |
| `.navigationTitle("X")` + `.toolbar { }` on a screen | wrap the screen in `ScreenScaffold("X", large = …, trailing = { ToolbarButton("Done") { } }) { content }` (UI/Chrome.kt: draws the bar, the back arrow when pushed, applies system-bar insets). A screen without a navigation bar handles insets itself (`Modifier.systemBarsPadding()`). |
| `.sheet(isPresented:)` / `.sheet(item:)` | `if (show) Sheet(onDismiss = { show = false }) { … }` (`dismissable = false` for `.interactiveDismissDisabled()`, `large = false` for medium detents) |
| `.fullScreenCover(isPresented:)` | `if (show) FullScreenCover(onDismiss = { show = false }) { … }` |
| `.alert(...)`, `.confirmationDialog(...)` | `if (show) Alert(title, message, confirm = "Delete", destructive = true, onConfirm = { }, onDismiss = { show = false })` |
| `UIImpactFeedbackGenerator`, `UINotificationFeedbackGenerator`, `.sensoryFeedback` | `val haptics = rememberHaptics()`; `haptics.impact()`, `.light()`, `.success()`, `.error()` |
| `@Environment(\.accessibilityReduceMotion)` | `reduceMotion()` |
| `ProcessInfo.processInfo.arguments` | `App.arguments` |
| app context / the foreground activity | `App.context` / `MainActivity.current` |
| `UIApplication.shared.open(url)`, `Link`, `ShareLink(item:)`, sharing an image | `Platform.openURL(url)`, `Platform.shareText(text)`, `Platform.shareImage(bitmap, text)` |
| App Store page / manage subscriptions / version | `Platform.storeURL`, `Platform.manageSubscriptionsURL`, `Platform.version`, `Platform.build` |
| `UNUserNotificationCenter` (authorization, add request, remove pending) | `LocalNotifications.status()`, `.authorized`, `.requestAuthorization { granted -> }`, `.schedule(identifier, title, body, fireAt, userInfo)`, `.cancel(identifiers)`, `.userInfo(intent)` (Core/LocalNotifications.kt) |
| `Info.plist` config (`SUPABASE_URL`, `GA_MEASUREMENT_ID`…) | `BuildConfig.SUPABASE_URL`, `.SUPABASE_ANON_KEY`, `.GA_MEASUREMENT_ID`, `.GA_API_SECRET` |
| `AppRouter`, `AppTab` | `AppRouter.shared` (`selectedTab`, the four navigators), `AppTab.play` |

## Naming rules (so files written by different people fit together without talking)

Every agent ports its own files while others port theirs at the same time. You will call code
that does not exist yet. These rules decide what it is called, so follow them exactly and assume
everyone else does.

1. **Types keep their Swift name.** `GameSession`, `SyncService`, `BoardView`, `AdaptiveLevel`.
2. **Members keep their Swift name and argument order.** `games.fetch(id: x)` is `games.fetch(x)`. Argument labels become Kotlin parameter names: an
   external label wins (`func session(kind:subject:spine:)` → `fun session(kind, subject, spine)`),
   `_ x` uses the internal name. Swift overloads that differ only by label get the label appended:
   `place(_:)` and `place(fromRight:)` → `place(p)` and `placeFromRight(i)`.
   `init(…)` is the constructor; a failable `init?` is `companion fun of(…): T?`.
3. **Plain enums** keep lowercase case names: `enum class Subject { math, english, logic }`.
   With raw values: `enum class Subject(val rawValue: String) { math("math"), … ;
   companion object { fun fromRaw(raw: String): Subject? = entries.firstOrNull { it.rawValue == raw } } }`.
   `Subject(rawValue: s)` → `Subject.fromRaw(s)`; `.allCases` → `.entries`. For JSON use
   `@Serializable` with `@SerialName(raw)` on each case.
4. **Enums with payloads** become a `sealed class` (or `sealed interface`) with the same name.
   Case `counters(Int, shuffle: Bool = false)` → nested `data class Counters(val n: Int, val
   shuffle: Boolean = false)`: the case name with a capital first letter, labels as property
   names, defaults kept. Unlabeled payloads get a sensible name; callers pass them by position.
   A case without payload → `data object Name`. So `.piles(a: 3, b: 4, subtract: false)` is
   `Manipulative.Piles(a = 3, b = 4, subtract = false)` and `case .twoPiles:` is
   `is Manipulative.TwoPiles`.
5. **Structs** → `data class` (`let` → `val`, `var` → `var`); computed properties stay
   properties; `static` members go in a `companion object`. `enum Foo { static … }` namespaces →
   `object Foo`. Extensions → Kotlin extension functions/properties in the file that ports them
   (`extension Subject { var tint }` → `val Subject.tint: Color`).
6. **Types**: `Int`→`Int`, `Double`→`Double`, `CGFloat`→`Float`, `Bool`→`Boolean`, `String`→
   `String`, `[T]`→`List<T>`, `[K: V]`→`Map<K, V>`, `Set<T>`→`Set<T>`, `T?`→`T?`, `CGPoint`→
   `Offset`, `CGSize`→`Size`, `CGRect`→`Rect`, `Color`→`Color`, `Date`→`java.time.Instant`
   (`Date()` → `Instant.now()`; calendar-day math through `java.time.LocalDate` /
   `ZoneId.systemDefault()`), `UUID`→`java.util.UUID`, `TimeInterval`→`Double` seconds, `URL`→
   `String`, `Angle`→ degrees as `Float`. Tuples → `Pair` or a small data class.
7. **Codable** → `@Serializable` data classes with the same JSON keys, decoded with `AppJson`.
   A custom `init(from:)` becomes a custom `KSerializer` (or parsing a `JsonObject`). The Kotlin
   must read the very same files in `../Content` and the same saved shapes the Swift writes.
8. **ObservableObject** → a plain class whose `@Published` properties are Compose state:
   `var enabled by mutableStateOf(true)` (`private(set)` → `private set`). Composables read them
   directly and recompose. `static let shared` → `companion object { val shared by lazy { Foo() } }`.
   `objectWillChange.send()` → keep `private var tick by mutableIntStateOf(0)` in the class, do
   `tick++`, and have every public getter that reads un-observed storage read `tick` first.
   `@Observable` classes are the same thing (every stored `var` a `mutableStateOf`).
   **Environment objects are singletons here**: `@Environment(DependencyContainer.self) var container`
   is `val container = DependencyContainer.shared`; `@Environment(AppRouter.self)` is
   `AppRouter.shared`; `@Environment(LearnProgress.self)` is `DependencyContainer.shared.learnProgress`.
   `@MainActor` is dropped: everything runs on the main thread; `async` functions
   are `suspend` functions; `Task { }` is `MainScope().launch { }` (or `LaunchedEffect` /
   `rememberCoroutineScope()` inside composables); `Task.sleep` is `delay`.
9. **Views** → `@Composable fun Name(…)` with the same name and the same parameters in the same
   order (a trailing `modifier: Modifier = Modifier` may be added). View-returning computed
   properties and helper funcs become private composables in the same file. `@State` →
   `remember { mutableStateOf(…) }`; `@Binding` → a value + `(T) -> Unit`, or `MutableState<T>`.
   `ViewModifier`s → `Modifier` extensions with the lowercase name (`OwlCard` → `owlCard`).
   `ButtonStyle`s → a composable button with the style's name minus "Style"
   (`TrailButtonStyle` → `TrailButton(text) { }`).
10. **Private type names**: two files may not both declare a `private class Foo` (same package =
    clash). Prefix a private type with its file's name when the name is generic (`Row`, `Params`,
    `Raw`, `Card`, `File`, `Plan`).
11. Points are dp. `frame(width: 44)` → `Modifier.size(44.dp)`; The activity pins the font scale to 1, so `sp` equals `dp`.
12. **SF Symbols** → the nearest `Icons.Rounded.*` vector (material-icons-extended is on the
    classpath). Emoji in strings stay as they are.
13. **Drawing**: SwiftUI `Canvas`/`Path`/`Shape` → Compose `Canvas` + `DrawScope` + `Path`, same
    geometry and numbers. `GeometryReader` → `BoxWithConstraints` or `Modifier.onSizeChanged`.
14. **Animation**: `withAnimation(.spring…)`/`.animation(_, value:)` → `animate*AsState`,
    `Animatable`, `updateTransition` with matching durations. `TimelineView(.animation)` → a
    `withFrameNanos` loop. Timed sequences (`DispatchQueue.main.asyncAfter`, `Task.sleep`) →
    coroutines with `delay`. Honor `reduceMotion()` wherever iOS checks Reduce Motion.
15. `#if DEBUG` code stays, under `if (BuildConfig.DEBUG)`. Debug launch flags read `App.arguments`.


16. **Swift concurrency**: an `actor` → a class whose suspend functions take a
    `kotlinx.coroutines.sync.Mutex` (or confine to one dispatcher); `Task.detached`/blocking work →
    `withContext(Dispatchers.IO)` / `Dispatchers.Default`. Everything that touches Compose state
    runs on the main thread. `Duration` → `kotlin.time.Duration` (`.seconds(3)` → `3.seconds`).
    `throws` → Kotlin exceptions; a Swift error enum → a sealed class extending `Exception`.
    `try?` → `runCatching { }.getOrNull()`. Never swallow `CancellationException`.
17. **Enums with payloads used as a `switch` on `.case`** follow rule 4; a Swift `case .engine(let
    level)` is `is GameConfig.Opponent.Engine -> opponent.level`.

## Platform decisions (already made; follow them)

- **Database (GRDB)** → plain `android.database.sqlite.SQLiteOpenHelper` (no Room), same tables,
  columns and migrations as `PersistenceKit/AppDatabase.swift`; repositories keep their Swift
  API as suspend functions (or plain functions where the Swift is synchronous). Observation
  (`ValueObservation`) → a `Flow`/Compose state the repository refreshes after each write.
- **Supabase (supabase-swift)** → OkHttp calls to the same project: Auth (`/auth/v1/…`), PostgREST
  (`/rest/v1/…`, RPC `/rest/v1/rpc/<fn>`), Edge Functions (`/functions/v1/<name>`), with the
  `apikey` header and the user's bearer token; the session (access + refresh token, refreshed
  before expiry) is kept in EncryptedSharedPreferences. Same tables, columns, RPC names,
  function names and payloads as the Swift. **Realtime is not used**: where the Swift listens
  on a Realtime channel as "just a poke" (friend games), poll the same RPC every 2 seconds
  while the screen is visible.
- **Sign in with Google / Apple** → Supabase's web OAuth flow in a Custom Tab
  (`/auth/v1/authorize?provider=google|apple&redirect_to=chessmaster://auth-callback`, PKCE or
  implicit as the Swift web flow does); the callback arrives in `ChessmasterApp.onOpenURL`.
  No Google Play services sign-in SDK. Email + password and email code are the same REST calls.
- **Push (APNs)** → not on Android yet (the push SDK is closed-source; this app is GPL like the
  iOS one, which is also why analytics is SDK-free). `PushRegistration` is a no-op,
  `sync.hasRemoteToken` stays false, so the LOCAL re-engagement notifications run.
- **StoreKit 2** → Google Play Billing 8 (`billing-ktx`). The four subscriptions have the SAME
  product IDs on Play (`com.chessmaster.premium.monthly|yearly`, `com.chessmaster.diamond.monthly|yearly`),
  each with one base plan (`monthly` or `yearly`) and an offer with id `free-trial` (7 days, new
  customers). "Eligible for intro offer" = Play returned the `free-trial` offer for that product.
  After a purchase (and on refresh for owned purchases) call
  `sync.verifySubscription(productID, purchaseToken)`, which POSTs
  `{"platform":"android","product_id":…,"purchase_token":…}` to the `verify-subscription`
  function; acknowledge the purchase. Offer-code redemption (friend referral, creators) opens
  `https://play.google.com/redeem?code=CODE`.
- **Analytics** → the same GA4 Measurement Protocol POST, same event names and parameters;
  the `device` block says Android (category mobile, operating_system Android, model from `Build`).
- **App Store review prompt** → Play In-App Review (`review-ktx`); "Rate Chess AI" opens `Platform.storeURL`.
- **Contacts invite** (CNContactPicker + MFMessageCompose) → the system contact picker
  (`ActivityResultContracts.PickContact`, phone number via the picked contact; no READ_CONTACTS
  if avoidable, otherwise say so) and an `ACTION_SENDTO` `smsto:` intent with the body.
- **Speech (AVSpeechSynthesizer)** → `android.speech.tts.TextToSpeech`. **Video (AVPlayer)** → media3 ExoPlayer.
  **Sounds (AVAudioPlayer)** → `SoundPool` for effects, `MediaPlayer` for the music loop.
- **ImageRenderer** (share card) → draw the same card into a 1080×1350 `Bitmap` (Canvas or a
  composable captured with `GraphicsLayer.toImageBitmap()`).
- Visible text that names an Apple thing is changed for Android ("App Store" → "Google Play",
  "iPhone" → "phone", "Apple ID"/"iTunes account" → "Google Play account", "Settings > Apple ID" →
  "Google Play > Payments & subscriptions"). Everything else is byte-for-byte.

## Fidelity rules

- **Port everything in your files**: every type, function, constant and branch. No "simplified"
  versions, no `TODO` bodies. If something truly cannot exist on Android, port the closest
  working behavior and say so in your report.
- **Every user-visible string must be byte-for-byte the Swift string**, including punctuation,
  curly quotes, dashes, emoji and spacing (except the Apple names above).
- Keep the numbers: sizes, paddings, corner radii, colors, durations, thresholds, ranges.
- Keep analytics event names/params, UserDefaults keys, accessibility identifiers (as
  `Modifier.testTag("id")`), launch-argument behavior (`--uitest`, `--premium`, `--preview-…`).
- Keep the doc comments (they explain why), in plain English.
- Random choice may differ run to run; the distributions and rules must be the same.

## Working alongside the other agents

- Write only the files you were given. Do not edit `build.gradle.kts`, the manifest, `Core/`,
  `UI/`, `ChessDomain/`, `AppRouter.kt`, `MainActivity.kt` or anyone else's file. If you need a
  dependency, permission, manifest entry or helper, say so in your report (and, for a helper,
  put it in your own file).
- Before calling something outside your files, open its **Swift** source to get the exact name
  and signature, apply the naming rules above, and check whether its `.kt` already exists
  (`ls`/`grep` under `app/src/main/java`) — if it does, the Kotlin file is the truth.
- Compile with (from `android/`):
  `./gradlew :app:compileDebugKotlin 2>&1 | grep -E "^e: .*/(FileA|FileB)\.kt"`
  listing your own files. Other agents' files are in flux, so the build as a whole will fail;
  only your own files' errors matter. Errors that are *unresolved references to things another
  file will define* are expected: leave the call as the naming rules say it should be. Fix
  every other error in your files. Gradle runs one build at a time, so compile a few times
  (after each big chunk), not after every edit. Do not start an emulator and do not commit.
- Final report (short): the files you wrote; anything you could not port exactly and what you
  did instead; names you chose where the rules left a choice (unlabeled payload names, split
  overloads, tuple classes); references that are still unresolved and which Swift file they
  belong to; dependencies/permissions/manifest entries you need.
