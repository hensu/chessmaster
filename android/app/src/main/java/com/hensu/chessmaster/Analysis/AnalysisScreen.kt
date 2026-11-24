// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `openAtWorstMoment`: start the guided mistake review on open.
 * `scrollToCoaching`: opened from the proactive first report: land on the report itself.
 */
@Composable
fun AnalysisScreen(
    game: GameRecord,
    openAtWorstMoment: Boolean = false,
    source: String = "history",
    scrollToCoaching: Boolean = false,
) {
    val model = remember { AnalysisViewModel(game) }
    val container = DependencyContainer.shared
    /** The appear block re-fires when the screen comes back; count one open. */
    var trackedOpen by remember { mutableStateOf(false) }
    var shareImage by remember { mutableStateOf<Bitmap?>(null) }
    val scrollState = rememberScrollState()
    /** Where the coaching section starts in the scrolled content: `.id("coaching")`. */
    var coachingTop by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun renderShareCard() {
        shareImage = model.shareCardContent?.let { ShareCardView.image(it) }
    }

    fun speakCurrentNote() {
        val index = model.mistakeIndex ?: return
        val mistakes = model.userMistakes
        if (index >= mistakes.size) return
        val note = model.coachNote(mistakes[index].ply) ?: return
        container.coachVoice.speak("${note.whatWentWrong} ${note.betterPlan}")
    }

    val scrollToCoachingSection: () -> Unit = {
        scope.launch { scrollState.animateScrollTo(coachingTop) }
    }

    ScreenScaffold(
        "Analysis",
        modifier = Modifier.trackScreen("Analysis"),
        background = Sys.background,
        trailing = {
            // Exports the game as standard PGN text — the recipient can
            // import it on lichess/chess.com. The AI coaching report is
            // NOT included (it lives in the recipient-less backend).
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable {
                    // Counts share-sheet opens; completed exports aren't observable.
                    container.sync.track("pgn_exported")
                    Platform.shareText(model.game.pgn, "Your game (PGN) — opens on lichess & chess.com")
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.IosShare, contentDescription = "Share game (PGN)", tint = Sys.accent)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                // The eval bar is as tall as the board beside it.
                val board = maxWidth - 12.dp - 6.dp
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    EvalBarView(model.evalBarPercent, Modifier.height(board))
                    Box(Modifier.size(board)) {
                        BoardView(
                            pieces = model.currentBoardPieces,
                            orientation = if (model.game.playerColor == "black") Piece.Color.black else Piece.Color.white,
                            lastMove = model.lastMove,
                            arrows = model.boardArrows,
                            interactive = false,
                        )
                    }
                }
            }

            AnalysisCoachingCTA(model)

            if (model.userMistakes.isNotEmpty()) {
                AnalysisMistakeReview(model, ::speakCurrentNote, scrollToCoachingSection)
            }

            AnalysisReplayControls(model)

            AnalysisSection(model)

            // Shareable card (accuracy, "played like", biggest mistake + the
            // coach's line when a report exists). Re-rendered when the report lands.
            shareImage?.let { image ->
                AnalysisBorderedButton(
                    onClick = {
                        container.sync.track("review_card_shared", mapOf(
                            "has_report" to if (model.coachingReport == null) "false" else "true",
                        ))
                        Platform.shareImage(
                            image,
                            "My Chess AI game review. Can you beat my accuracy? ${Platform.storeURL}",
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) { color ->
                    AnalysisLabel("Share my review", Icons.Rounded.IosShare, Sys.subheadline.weight(FontWeight.SemiBold), color)
                }
            }

            Box(Modifier.fillMaxWidth().onGloballyPositioned { coachingTop = it.positionInParent().y.roundToInt() }) {
                AnalysisCoachingSection(model)
            }

            AnalysisMoveList(model)
        }
    }

    LaunchedEffect(Unit) {
        // onAppear
        model.attach(container)
        if (!trackedOpen) {
            trackedOpen = true
            container.sync.track("analysis_opened", mapOf("source" to source))
        }
        if (openAtWorstMoment) {
            model.startGuidedReview()
        }
        if (shareImage == null) renderShareCard()

        // 3 games without the coach: this one's report runs by itself.
        var scroll = scrollToCoaching
        if (CoachHabit.isDue(model.game.id)) {
            CoachHabit.clearDue()
            model.attach(container)
            if (model.isPremium && model.coachingReport == null) {
                model.requestCoaching(automatic = true)
                scroll = true
            }
        }
        if (!scroll) return@LaunchedEffect
        delay(350)   // let the push settle
        scrollState.animateScrollTo(coachingTop)
    }
    // The coach reads each note aloud as the player steps through
    // moments (mutable via the speaker button or Profile → Sound).
    AnalysisOnChange(model.mistakeIndex) { speakCurrentNote() }
    AnalysisOnChange(model.coachingReport) { report ->
        if (report != null) speakCurrentNote()
        renderShareCard()
    }
    AnalysisOnChange(model.analysis == null) { renderShareCard() }
    AnalysisOnChange(model.trainingConfig) { config ->
        if (config != null) container.coachVoice.stop()
    }
    DisposableEffect(Unit) {
        onDispose {
            container.coachVoice.stop()
            // Just read an AI review — ask on the way out, not mid-read.
            if (model.coachingReport != null && model.trainingConfig == null) {
                ReviewPrompt.maybeRequest("coaching_report", container)
            }
        }
    }

    if (model.showPaywall) {
        Sheet(onDismiss = { model.showPaywall = false }) {
            AppPaywall(source = model.paywallSource, onClose = { model.showPaywall = false })
        }
    }
    model.trainingConfig?.let { config ->
        FullScreenCover(onDismiss = { model.trainingConfig = null }) {
            Box(Modifier.fillMaxSize()) {
                NavStack {
                    GameScreen(
                        config = config,
                        advice = model.trainingAdvice,
                        originalOutcome = model.trainingOriginal,
                        arrows = model.trainingArrows,
                        onDismiss = { model.trainingConfig = null },
                    )
                }
                // The leading toolbar item of the training cover.
                Box(
                    Modifier.statusBarsPadding().height(48.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ToolbarButton("End training") { model.trainingConfig = null }
                }
            }
        }
    }
}

// Midnight Glow CTA palette.
private val analysisCtaInk = rgb(0.063, 0.086, 0.122)       // #10161f
private val analysisCtaEmerald = rgb(0.063, 0.725, 0.506)   // #10b981
private val analysisCtaMint = rgb(0.498, 0.914, 0.784)      // #7fe9c8
private val analysisCtaGold = rgb(0.961, 0.769, 0.318)      // #f5c451

/** SwiftUI's `.easeInOut`. */
private val analysisEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
/** SwiftUI's `.easeOut`. */
private val analysisEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * First-class AI coaching call-to-action, pinned under the board.
 * Hidden once a report exists (notes then live in the cards + full
 * report below); a spinner replaces it while generating.
 */
@Composable
private fun AnalysisCoachingCTA(model: AnalysisViewModel) {
    if (model.coachingReport != null) {
        // Nothing.
    } else if (model.coachingLoading) {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(Sys.secondary.opacity(0.08), RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Sys.secondary, strokeWidth = 2.dp)
            Text("Chess AI is analyzing your game…", style = Sys.subheadline, color = Sys.secondary)
        }
    } else if (model.coachingAvailable) {
        // Breathing emerald halo — draws the eye without a hard flash.
        // Drives the glow from 0 (resting) to 1 (full) and back, 2.6s each way.
        val glow: State<Float> = if (reduceMotion()) {
            remember { mutableStateOf(0f) }
        } else {
            rememberInfiniteTransition(label = "ctaGlow").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2600, easing = analysisEaseInOut), RepeatMode.Reverse),
                label = "ctaGlow",
            )
        }
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .testTag("getAICoaching")
                .drawBehind {
                    val t = glow.value
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.color = analysisCtaEmerald.opacity(0.22 + 0.33 * t).toArgb()
                    paint.maskFilter = BlurMaskFilter((9f + 11f * t).dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                    val corner = 16.dp.toPx()
                    drawIntoCanvas {
                        it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, paint)
                    }
                }
                .clip(shape)
                .background(analysisCtaInk)
                .border(1.dp, analysisCtaEmerald.opacity(0.45), shape)
                .clickable { model.requestCoaching() }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = analysisCtaEmerald, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Get AI Coaching", style = Sys.headline, color = Color.White)
                Text(
                    if (model.isPremium) "Personal notes on every mistake"
                    else "Personal notes on every mistake · Premium",
                    style = Sys.caption,
                    color = analysisCtaMint,
                )
            }
            Icon(
                if (model.isPremium) Icons.Rounded.ChevronRight else Icons.Rounded.WorkspacePremium,
                contentDescription = null,
                tint = if (model.isPremium) analysisCtaEmerald else analysisCtaGold,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Guided walkthrough of the player's mistakes: step through each one
 * (board shows played-in-red vs best-in-green), finish with the coach.
 */
@Composable
private fun AnalysisMistakeReview(model: AnalysisViewModel, speakCurrentNote: () -> Unit, scrollToCoaching: () -> Unit) {
    val container = DependencyContainer.shared
    val mistakes = model.userMistakes
    // The index can trail the list for a frame while the analysis lands.
    val index = model.mistakeIndex?.takeIf { it < mistakes.size }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Sys.secondary.opacity(0.08), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (index != null) {
                val mistake = mistakes[index]
                Text("Key moment ${index + 1} of ${mistakes.size}", style = Sys.caption, color = Sys.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${mistake.san}${mistake.classification.glyph}", style = Sys.headline.analysisMono(), color = Sys.red)
                    mistake.bestMoveUCI?.let { best ->
                        AnalysisLabel(analysisFormatUCI(best), Icons.Rounded.TurnRight, Sys.headline.analysisMono(), Sys.green)
                    }
                }
                val drop = model.dropFor(mistake.ply)
                if (drop != null && drop >= 1) {
                    Text(
                        "ply ${mistake.ply} · cost you ${drop.roundToInt()}% win chance",
                        style = Sys.caption2,
                        color = Sys.orange,
                    )
                }
            } else {
                Text("${mistakes.size} key moments in this game", style = Sys.caption, color = Sys.secondary)
                Text(
                    "Review them",
                    style = Sys.subheadline.weight(FontWeight.SemiBold),
                    color = Sys.accent,
                    modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { model.startGuidedReview() },
                )
            }
        }

        // Big, unmistakable stepper (chess.com-style): Back small,
        // Next carries the walkthrough.
        if (model.mistakeIndex != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AnalysisBorderedButton(
                    onClick = { model.stepMistake(-1) },
                    enabled = (model.mistakeIndex ?: 0) != 0,
                    large = true,
                ) { color ->
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                }

                AnalysisBorderedButton(
                    onClick = { model.stepMistake(1) },
                    modifier = Modifier.weight(1f),
                    enabled = model.mistakeIndex != mistakes.size - 1,
                    large = true,
                ) { color ->
                    Text("Next", style = Sys.headline, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // The coach's note for THIS mistake, inline once the report exists.
        val note = index?.let { model.coachNote(mistakes[it].ply) }
        if (index != null && note != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val speaking = container.coachVoice.speaking
                    val pulse = remember { Animatable(1f) }
                    LaunchedEffect(speaking) {
                        if (speaking) {
                            pulse.animateTo(1.1f, infiniteRepeatable(tween(450, easing = analysisEaseInOut), RepeatMode.Reverse))
                        } else {
                            pulse.animateTo(1f, tween(200, easing = analysisEaseOut))
                        }
                    }
                    Box(
                        Modifier
                            .scale(pulse.value)
                            .background(rgb(0xB5 / 255.0, 0x88 / 255.0, 0x63 / 255.0), CircleShape)
                            .padding(5.dp),
                    ) {
                        PieceImage(Piece.Kind.knight, Piece.Color.white, Modifier.size(20.dp))
                    }
                    Text("Coach", style = Sys.caption.bold(), color = Sys.secondary)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (container.coachVoice.enabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        contentDescription = "Coach voice",
                        tint = Sys.secondary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                container.coachVoice.enabled = !container.coachVoice.enabled
                                if (container.coachVoice.enabled) speakCurrentNote()
                            },
                    )
                }
                Text(note.whatWentWrong, style = Sys.footnote, color = Sys.primary)
                AnalysisLabel(note.betterPlan, Icons.Rounded.Lightbulb, Sys.footnote, Sys.green)
                Text(
                    note.theme.replace("_", " "),
                    style = Sys.caption2,
                    color = Sys.orange,
                    modifier = Modifier
                        .background(Sys.orange.opacity(0.15), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        } else if (model.coachingReport != null && index != null) {
            // The report exists but skipped this (smaller) mistake:
            // fall back to the engine's preference so no flagged move
            // is ever noteless.
            model.evalFor(mistakes[index].ply)?.bestMoveUCI?.let { best ->
                AnalysisLabel(
                    "Chess AI suggested ${best.take(2)}→${best.drop(2).take(2)} here.",
                    Icons.Rounded.Lightbulb,
                    Sys.footnote,
                    Sys.green,
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        // Quiet inline action — discoverable here, never shouting.
        if (index != null) {
            AnalysisLabel(
                if (model.isPremium) "Replay from here" else "Replay from here (Premium)",
                Icons.Rounded.Replay,
                Sys.footnote,
                Sys.secondary,
                Modifier.clickable(remember { MutableInteractionSource() }, null) {
                    model.startTraining(mistakes[index].ply)
                },
            )
        }

        if (model.mistakeIndex == mistakes.size - 1 && model.coachingReport != null) {
            AnalysisLabel(
                "Chess AI's overall assessment",
                Icons.AutoMirrored.Rounded.Notes,
                Sys.subheadline,
                Sys.accent,
                Modifier.clickable(remember { MutableInteractionSource() }, null) { scrollToCoaching() },
            )
        }
    }
}

/** "g7g6" → "g7→g6" (readable without SAN reconstruction). */
private fun analysisFormatUCI(uci: String): String {
    if (uci.length < 4) return uci
    return "${uci.take(2)}→${uci.drop(2).take(2)}"
}

@Composable
private fun AnalysisCoachingSection(model: AnalysisViewModel) {
    val container = DependencyContainer.shared
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val report = model.coachingReport
        if (report != null) {
            CoachingReportView(report, onJump = { ply ->
                container.sync.track("coach_note_jump", mapOf("ply" to ply.toString()))
                model.jump(ply)
            })
        } else if (model.coachingLoading) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Sys.secondary, strokeWidth = 2.5.dp)
                Text("Chess AI is reviewing the game…", style = Sys.footnote, color = Sys.secondary)
            }
        } else if (model.analysis != null) {
            model.coachingError?.let { message ->
                Text(message, style = Sys.footnote, color = Sys.red)
            }
            // The mistake card carries the "Ask Chess AI" entry point;
            // this one only appears for games with no flagged mistakes.
        }
    }
}

@Composable
private fun AnalysisReplayControls(model: AnalysisViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        AnalysisReplayButton(Icons.Rounded.SkipPrevious, model.currentPly != 0, Modifier.testTag("replayFirst")) {
            model.jump(0)
        }
        AnalysisReplayButton(Icons.Rounded.ChevronLeft, model.currentPly != 0, Modifier.testTag("replayBack")) {
            model.jump(model.currentPly - 1)
        }
        Text(
            "${model.currentPly}/${model.plyCount}",
            style = Sys.footnote,
            color = Sys.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 50.dp).testTag("plyCounter"),
        )
        AnalysisReplayButton(Icons.Rounded.ChevronRight, model.currentPly != model.plyCount, Modifier.testTag("replayForward")) {
            model.jump(model.currentPly + 1)
        }
        AnalysisReplayButton(Icons.Rounded.SkipNext, model.currentPly != model.plyCount) {
            model.jump(model.plyCount)
        }
    }
}

@Composable
private fun AnalysisReplayButton(icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnalysisBorderedButton(onClick = onClick, modifier = modifier, enabled = enabled) { color ->
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun AnalysisSection(model: AnalysisViewModel) {
    val analysis = model.analysis
    if (analysis != null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Live winning chances at the scrubbed position; each side's
            // whole-game accuracy rides along as the sublabel.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisWinCard("White", model.evalBarPercent, analysis.accuracyWhite, Modifier.weight(1f))
                AnalysisWinCard("Black", 100 - model.evalBarPercent, analysis.accuracyBlack, Modifier.weight(1f))
            }

            AnalysisEvalGraph(model, analysis)

            if (analysis.criticalPlies.isNotEmpty()) {
                AnalysisCriticalStrip(model, analysis)
            }
        }
    } else if (model.analyzing) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                progress = { model.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Sys.accent,
                trackColor = Sys.tertiaryFill,
                drawStopIndicator = {},
            )
            Text("Chess AI is analyzing…", style = Sys.footnote, color = Sys.secondary)
        }
    } else {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Sys.accent)
                .clickable { model.runAnalysis() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnalysisLabel("Analyze game", Icons.Rounded.MonitorHeart, Sys.body, Color.White)
        }
    }
}

@Composable
private fun AnalysisWinCard(side: String, winPercent: Double, accuracy: Double, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Sys.secondary.opacity(0.08), RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${winPercent.roundToInt()}%", style = Sys.headline, color = Sys.primary)
        Text("$side · accuracy ${accuracy.roundToInt()}%", style = Sys.caption2, color = Sys.secondary)
    }
}

/**
 * White's winning chances over the game (the Swift Charts area graph): the
 * area under the win% line, a dashed rule at 50, a blue rule at the current
 * ply, ply numbers along the bottom. Touch or drag to scrub.
 */
@Composable
private fun AnalysisEvalGraph(model: AnalysisViewModel, analysis: GameAnalysis) {
    val plies = analysis.plies
    // A round step for the ply axis, about five labels across.
    val last = plies.lastOrNull()?.ply ?: 1
    val step = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200).firstOrNull { last / it <= 5 } ?: 500
    val domainMax = (ceil(last.toDouble() / step) * step).toInt().coerceAtLeast(1)
    val currentPly = model.currentPly
    val measurer = rememberTextMeasurer()
    val labelStyle = Sys.font(10).copy(color = Sys.secondary)
    val ruleColor = Sys.secondary
    val currentColor = Sys.blue
    val axisHeight = 16.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(Color.Black.opacity(0.75), RoundedCornerShape(8.dp))
            .pointerInput(domainMax) {
                fun jump(x: Float) {
                    val ply = (x / size.width * domainMax).roundToInt()
                    model.jump(ply)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    jump(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        jump(change.position.x)
                        change.consume()
                    }
                }
            }
    ) {
        val plotHeight = size.height - axisHeight.toPx()
        fun x(ply: Int): Float = ply.toFloat() / domainMax * size.width
        fun y(win: Double): Float = (plotHeight * (1 - win / 100.0)).toFloat()

        if (plies.isNotEmpty()) {
            val area = Path()
            area.moveTo(x(plies.first().ply), plotHeight)
            for (ply in plies) area.lineTo(x(ply.ply), y(ply.winPercentWhite))
            area.lineTo(x(plies.last().ply), plotHeight)
            area.close()
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(Color.White.opacity(0.85), Color.White.opacity(0.4)),
                    startY = 0f, endY = plotHeight,
                ),
            )
        }
        drawLine(
            ruleColor,
            Offset(0f, y(50.0)), Offset(size.width, y(50.0)),
            strokeWidth = 0.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        if (currentPly > 0) {
            drawLine(currentColor, Offset(x(currentPly), 0f), Offset(x(currentPly), plotHeight), strokeWidth = 1.dp.toPx())
        }
        var tick = 0
        while (tick <= domainMax) {
            val label = measurer.measure(tick.toString(), labelStyle)
            val left = (x(tick) + 3.dp.toPx()).coerceAtMost(size.width - label.size.width - 2.dp.toPx())
            drawText(label, topLeft = Offset(left, plotHeight + (axisHeight.toPx() - label.size.height) / 2))
            tick += step
        }
    }
}

@Composable
private fun AnalysisCriticalStrip(model: AnalysisViewModel, analysis: GameAnalysis) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (ply in analysis.criticalPlies) {
            val color = if (ply.classification == MoveClassification.blunder) Sys.red else Sys.orange
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.opacity(0.15))
                    .clickable { model.showMistake(ply.ply) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${ply.san}${ply.classification.glyph}", style = Sys.subheadline.bold().analysisMono(), color = color)
                Text("move ${(ply.ply + 1) / 2}", style = Sys.caption2, color = color)
            }
        }
    }
}

@Composable
private fun AnalysisMoveList(model: AnalysisViewModel) {
    val replay = model.replay ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until (replay.plies.size + 1) / 2) {
            val whitePly = row * 2 + 1
            val blackPly = row * 2 + 2
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${row + 1}.", style = Sys.callout.analysisMono(), color = Sys.secondary, maxLines = 1, modifier = Modifier.width(36.dp))
                Box(Modifier.weight(1f)) { AnalysisMoveCell(model, replay, whitePly) }
                Box(Modifier.weight(1f)) {
                    if (blackPly <= replay.plies.size) {
                        AnalysisMoveCell(model, replay, blackPly)
                    } else {
                        Text("")
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisMoveCell(model: AnalysisViewModel, replay: GameReplay, ply: Int) {
    val info = replay.plies[ply - 1]
    val eval = model.evalFor(ply)
    val isFlagged = eval?.classification == MoveClassification.blunder || eval?.classification == MoveClassification.mistake
    Text(
        info.san + (eval?.classification?.glyph ?: ""),
        style = Sys.callout.analysisMono(),
        fontWeight = if (model.currentPly == ply) FontWeight.Bold else FontWeight.Normal,
        color = analysisGlyphColor(eval?.classification),
        modifier = Modifier
            .background(analysisCellBackground(model, ply, eval?.classification), RoundedCornerShape(4.dp))
            .clickable(remember { MutableInteractionSource() }, null) {
                // Flagged moves open in "played vs best" view; others just jump.
                if (isFlagged) {
                    model.showMistake(ply)
                } else {
                    model.jump(ply)
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Flagged moves stay visibly tinted in the move list; the current ply
 * wins so scrubbing is always legible.
 */
private fun analysisCellBackground(model: AnalysisViewModel, ply: Int, classification: MoveClassification?): Color {
    if (model.currentPly == ply) return Sys.accent.opacity(0.25)
    return when (classification) {
        MoveClassification.blunder -> Sys.red.opacity(0.14)
        MoveClassification.mistake -> Sys.orange.opacity(0.14)
        else -> Color.Transparent
    }
}

private fun analysisGlyphColor(classification: MoveClassification?): Color = when (classification) {
    MoveClassification.blunder -> rgb(0xDF / 255.0, 0x53 / 255.0, 0x53 / 255.0)
    MoveClassification.mistake -> rgb(0xE6 / 255.0, 0x9F / 255.0, 0x00 / 255.0)
    MoveClassification.inaccuracy -> rgb(0x56 / 255.0, 0xB4 / 255.0, 0xE9 / 255.0)
    else -> Sys.primary
}

/** Lichess-style vertical eval bar: white fills from the bottom. */
@Composable
fun EvalBarView(whitePercent: Double, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        (whitePercent / 100).toFloat().coerceIn(0f, 1f),
        tween(200, easing = analysisEaseOut),
        label = "evalBar",
    )
    Box(
        modifier
            .width(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0.15f, 0.15f, 0.15f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(Color(0.95f, 0.95f, 0.95f)))
    }
}

// MARK: - Small pieces of SwiftUI this screen leans on

/** `.onChange(of: value) { }`: runs when the value changes, not on first appearance. */
@Composable
private fun <T> AnalysisOnChange(value: T, block: (T) -> Unit) {
    val current by rememberUpdatedState(block)
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) first[0] = false else current(value)
    }
}

/** `.monospaced()` */
private fun TextStyle.analysisMono(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/** `Label(text, systemImage:)`: the icon, then the text. */
@Composable
private fun AnalysisLabel(text: String, icon: ImageVector, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((style.fontSize.value + 3).dp))
        Text(text, style = style, color = color)
    }
}

/**
 * `.buttonStyle(.bordered)`: a grey filled, rounded button whose label takes
 * the accent color (dimmed when disabled). `large` is `.controlSize(.large)`.
 * The content gets the label color.
 */
@Composable
private fun AnalysisBorderedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val corner: Dp = if (large) 12.dp else 8.dp
    Box(
        modifier
            .defaultMinSize(minHeight = if (large) 50.dp else 34.dp)
            .clip(RoundedCornerShape(corner))
            .background(Sys.tertiaryFill)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = if (large) 20.dp else 12.dp, vertical = if (large) 14.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        content(if (enabled) Sys.accent else Sys.tertiary)
    }
}
