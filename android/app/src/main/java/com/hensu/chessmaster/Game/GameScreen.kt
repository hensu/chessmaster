// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GameScreen(
    config: GameConfig,
    advice: String? = null,
    originalOutcome: String? = null,
    arrows: List<BoardArrow> = emptyList(),
    onDismiss: (() -> Unit)? = null,
) {
    val model = remember {
        GameViewModel(config).also {
            it.trainingAdvice = advice
            it.trainingOriginal = originalOutcome
            it.trainingArrows = arrows
        }
    }
    GameScreenBody(model, onDismiss)
}

@Composable
fun GameScreen(friend: FriendGameLink, onDismiss: (() -> Unit)? = null) {
    val model = remember { GameViewModel(friend.config, friend = friend) }
    GameScreenBody(model, onDismiss)
}

@Composable
fun GameScreen(resume: InProgressGameRecord, onDismiss: (() -> Unit)? = null) {
    val model = remember {
        val config = GameConfig(resume) ?: GameConfig()
        GameViewModel(config, resume = resume)
    }
    GameScreenBody(model, onDismiss)
}

/** SwiftUI's monospacedDigit(). */
private fun TextStyle.gameScreenDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
private fun GameScreenBody(model: GameViewModel, onDismiss: (() -> Unit)?) {
    var analyzedGame by remember { mutableStateOf<GameRecord?>(null) }
    val container = DependencyContainer.shared
    val nav = LocalNavOrNull.current
    // @Environment(\.dismiss): the caller's closure, else pop the pushed
    // screen; a friend game sits in RootView's cover, which closes when the
    // active friend game is cleared.
    val dismiss: () -> Unit = {
        if (onDismiss != null) onDismiss()
        else if (nav != null && nav.depth > 0) nav.pop()
        else if (model.isFriendGame) container.activeFriendGame = null
    }
    val playing = model.session.status.isPlaying

    // .navigationBarBackButtonHidden while the game is on.
    BackHandler(enabled = playing) {}

    ScreenScaffold(
        model.title,
        modifier = Modifier.trackScreen("Game"),
        background = Sys.background,
        leading = if (playing) ({}) else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Replay training: the coach's advice rides above the board.
                val advice = model.trainingAdvice
                if (advice != null && playing) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Sys.orange.opacity(0.12), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = Sys.primary,
                                 modifier = Modifier.size(16.dp))
                            Text(advice, style = Sys.footnote, color = Sys.primary)
                        }
                        if (model.session.moveHistory.isEmpty() && model.trainingArrows.isNotEmpty()) {
                            Text("On the board: red — your move · green — Chess AI",
                                 style = Sys.caption2, color = Sys.secondary)
                        }
                    }
                }

                GameScreenPlayerBar(model, model.orientation.opposite)

                BoardView(
                    pieces = model.session.boardPieces,
                    orientation = model.orientation,
                    lastMove = model.session.lastMove,
                    checkSquare = model.session.checkedKingSquare,
                    // Retry-training: played vs suggested, until the first move.
                    arrows = if (model.session.moveHistory.isEmpty()) model.trainingArrows else emptyList(),
                    canSelect = { square ->
                        val piece = if (model.session.status.isPlaying) model.session.piece(square) else null
                        piece != null && piece.color == model.session.sideToMove &&
                            model.session.isUserControlled(piece.color) &&
                            !model.session.opponentThinking
                    },
                    legalTargets = { model.session.legalTargets(it) },
                    onMove = { from, to -> model.session.attemptUserMove(from, to) },
                    pendingPromotion = model.session.pendingPromotion,
                    onPromote = { model.session.completePromotion(it) },
                    onCancelPromotion = { model.session.cancelPromotion() },
                )

                GameScreenPlayerBar(model, model.orientation)

                Spacer(Modifier.weight(1f))

                GameScreenBottomBar(model)
            }

            // While the post-game analysis runs, the result + a kind word
            // replace the old Done/Rematch sheet; analysis opens by itself.
            AnimatedVisibility(
                visible = model.preparingInsights && !model.showGameOver && analyzedGame == null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    Modifier.padding(24.dp).widthIn(max = 320.dp)
                        .shadow(20.dp, RoundedCornerShape(20.dp))
                        .background(Sys.secondaryBackground, RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(model.resultHeadline, style = Sys.title2.bold(), color = Sys.primary)
                    model.ratingChange?.let { change ->
                        val delta = change.after.rating.roundToInt() - change.before.rating.roundToInt()
                        Text(if (delta >= 0) "+$delta" else "$delta",
                             style = Sys.title3.bold().gameScreenDigits(),
                             color = if (delta >= 0) Sys.green else Sys.red)
                    }
                    Text(model.encouragement, style = Sys.subheadline, color = Sys.primary,
                         textAlign = TextAlign.Center)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameScreenSpinner()
                        Text("Analyzing your game…", style = Sys.footnote, color = Sys.secondary)
                    }
                }
            }
        }
    }

    if (model.showResignConfirm) {
        Alert("Resign this game?", confirm = "Resign", destructive = true,
              onConfirm = { model.session.resign() },
              onDismiss = { model.showResignConfirm = false })
    }

    if (model.showGameOver) {
        Sheet(onDismiss = { model.showGameOver = false }, large = false) {
            GameOverSheet(
                model = model,
                dismissGame = { dismiss() },
                onAnalyze = { analyzedGame = model.savedGameRecord },
                onDismiss = { model.showGameOver = false },
            )
        }
    }

    analyzedGame?.let { game ->
        val close = {
            analyzedGame = null
            dismiss()
        }
        FullScreenCover(onDismiss = close, dismissable = true) {
            // The analysis screen draws its own navigation bar; the cover's
            // leading "Close" sits in a row above it.
            Column(Modifier.fillMaxSize().background(Sys.groupedBackground)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ToolbarButton("Close") { close() }
                }
                Box(Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                    NavStack {
                        AnalysisScreen(game = game, openAtWorstMoment = true, source = "post_game")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        model.attach(container)
        model.start()
    }
    // An aborted game has nothing to show: straight back home.
    LaunchedEffect(playing) {
        val status = model.session.status
        if (!playing && status is GameStatus.Finished && status.result == GameResult.Aborted) {
            dismiss()
        }
    }
    // Insights ready while the game-over sheet is up: swap it for the
    // analysis view automatically (Close = skip).
    LaunchedEffect(model.postGameInsights?.id) {
        val record = model.postGameInsights ?: return@LaunchedEffect
        if (model.rematchRequested) return@LaunchedEffect
        model.showGameOver = false
        delay(400)
        analyzedGame = record
    }
}

/** ProgressView().controlSize(.small) */
@Composable
private fun GameScreenSpinner() {
    CircularProgressIndicator(Modifier.size(16.dp), color = Sys.secondary, strokeWidth = 2.dp)
}

/** A `.bordered` button with a full-width label. */
@Composable
private fun GameScreenBorderedButton(
    text: String,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (destructive) Sys.red else Sys.accent
    Row(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (destructive) Sys.red.opacity(0.15) else Sys.tertiaryFill)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = Sys.body, color = tint, maxLines = 1)
    }
}

/** Always-visible game controls — no hunting through menus mid-game. */
@Composable
private fun GameScreenBottomBar(model: GameViewModel) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameScreenBorderedButton("Flip", Icons.Rounded.SwapVert, modifier = Modifier.weight(1f)) {
            model.orientation = model.orientation.opposite
        }

        if (model.session.status.isPlaying) {
            if (model.session.moveHistory.size < 2) {
                GameScreenBorderedButton("Abort", Icons.Rounded.Close, destructive = true,
                                         modifier = Modifier.weight(1f)) {
                    model.session.abort()
                }
            } else {
                GameScreenBorderedButton("Resign", Icons.Rounded.Flag, destructive = true,
                                         modifier = Modifier.weight(1f).testTag("resignButton")) {
                    model.showResignConfirm = true
                }
            }
        }
    }
}

@Composable
private fun GameScreenPlayerBar(model: GameViewModel, color: Piece.Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (model.session.isUserControlled(color) || model.isFriendGame) Icons.Rounded.Person
            else Icons.Rounded.DesktopWindows,
            contentDescription = null, tint = Sys.secondary, modifier = Modifier.size(18.dp),
        )
        Text(model.playerName(color), style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary,
             maxLines = 1)
        if (model.session.opponentThinking && !model.session.isUserControlled(color) &&
            model.session.sideToMove == color
        ) {
            GameScreenSpinner()
        }
        // Captured pieces + point edge, lichess-style.
        val captured = model.capturedPieces(color)
        if (captured.isNotEmpty()) {
            Row(Modifier.padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                for (kind in captured) {
                    Image(painterResource(pieceImageRes(kind, color.opposite)),
                          contentDescription = null, modifier = Modifier.size(15.dp))
                }
            }
        }
        val advantage = model.materialAdvantage(color)
        if (advantage > 0) {
            Text("+$advantage", style = Sys.caption.bold().gameScreenDigits(), color = Sys.secondary)
        }
        Spacer(Modifier.weight(1f))
        model.clock?.let { clock ->
            ClockView(clock = clock, side = color.clockSide)
        }
    }
}

@Composable
fun GameOverSheet(
    model: GameViewModel,
    dismissGame: () -> Unit,
    onAnalyze: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val dismissSheet = onDismiss
    val container = DependencyContainer.shared
    var showPaywall by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(model.resultHeadline, style = Sys.title2.bold(), color = Sys.primary)
        Text(model.resultDetail, style = Sys.body, color = Sys.secondary)
        Text(model.encouragement, style = Sys.subheadline, color = Sys.primary, textAlign = TextAlign.Center,
             modifier = Modifier.padding(horizontal = 24.dp))

        // Replay training: then vs now.
        model.trainingOriginal?.let { original ->
            Text(original, style = Sys.footnote, color = Sys.secondary, textAlign = TextAlign.Center,
                 modifier = Modifier.padding(horizontal = 24.dp))
        }

        model.ratingChange?.let { change ->
            val before = change.before.rating.roundToInt()
            val after = change.after.rating.roundToInt()
            val delta = after - before
            val style = Sys.title3.gameScreenDigits()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$before", style = style, color = Sys.secondary)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Sys.tertiary,
                     modifier = Modifier.size(14.dp))
                Text("$after", style = style.weight(FontWeight.SemiBold), color = Sys.primary)
                Text(if (delta >= 0) "+$delta" else "$delta", style = style.bold(),
                     color = if (delta >= 0) Sys.green else Sys.red)
            }
        }

        if (model.preparingInsights) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameScreenSpinner()
                Text("Preparing your insights…", style = Sys.footnote, color = Sys.secondary)
            }
        } else if (model.savedGameRecord != null) {
            // The hero CTA (chess.com-style): the review is the product.
            // Engine analysis is free; the AI coach lives (paywalled)
            // inside. Everyone can open the review.
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Sys.green)
                    .clickable {
                        container.sync.track("insights_tapped",
                            mapOf("premium" to if (container.entitlements.isPremium) "true" else "false"))
                        dismissSheet()
                        onAnalyze()
                    }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Review game", style = Sys.headline, color = Color.White)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameScreenBorderedButton("Done", modifier = Modifier.weight(1f)) {
                dismissSheet()
                dismissGame()
            }

            if (!model.isFriendGame) {
                GameScreenBorderedButton("Rematch", modifier = Modifier.weight(1f)) {
                    container.sync.track("rematch_tapped")
                    dismissSheet()
                    model.rematchRequested = true
                }
            }
        }

        // Nudge account-less players to save what they just played.
        if (container.sync.isConfigured && !container.sync.isSignedIn) {
            Row(
                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        container.sync.track("signin_nudge_tapped")
                        showAccountSheet = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = Sys.accent,
                     modifier = Modifier.size(16.dp))
                Text("Sign in to save this game", style = Sys.footnote, color = Sys.accent)
            }
        }
    }

    if (showPaywall) {
        Sheet(onDismiss = { showPaywall = false }) {
            AppPaywall(source = "game_insights")
        }
    }
    if (showAccountSheet) {
        Sheet(onDismiss = { showAccountSheet = false }) {
            AccountSheet(sync = container.sync, onDismiss = { showAccountSheet = false })
        }
    }
}
