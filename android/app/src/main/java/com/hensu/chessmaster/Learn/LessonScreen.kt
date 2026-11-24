// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A guided lesson: one instruction per position; any accepted move
 * advances, anything else resets the step.
 */
@Composable
fun LessonScreen(lesson: Lesson) {
    val container = DependencyContainer.shared
    val progress = container.learnProgress
    val nav = LocalNav.current
    val model = remember { LessonViewModel(lesson) }

    DisposableEffect(Unit) {
        model.attach(container, progress)
        model.begin()
        onDispose { model.stop() }
    }

    ScreenScaffold(lesson.title, modifier = Modifier.trackScreen("Lesson"), background = Sys.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ProgressView(value:total:)
            val fraction = if (lesson.steps.isEmpty()) 0f else model.stepIndex.toFloat() / lesson.steps.size
            Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Sys.tertiaryFill)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Sys.accent))
            }

            LessonBanner(model)

            BoardView(
                pieces = model.session.boardPieces,
                orientation = Piece.Color.white,
                lastMove = model.session.lastMove,
                checkSquare = model.session.checkedKingSquare,
                canSelect = { square ->
                    val piece = if (model.phase != LessonViewModel.Phase.finished) model.session.piece(square) else null
                    piece != null && piece.color == model.session.sideToMove
                },
                legalTargets = { model.session.legalTargets(it) },
                onMove = { from, to ->
                    model.session.attemptUserMove(from, to)
                },
            )

            Spacer(Modifier.weight(1f))

            if (model.phase == LessonViewModel.Phase.finished) {
                LearnProminentButton("Done", modifier = Modifier.padding(bottom = 4.dp)) { nav.pop() }
            }
        }
    }
}

@Composable
private fun LessonBanner(model: LessonViewModel) {
    Column(
        Modifier.fillMaxWidth().background(Sys.secondary.opacity(0.08), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (model.phase) {
            LessonViewModel.Phase.prompt ->
                LearnLabel(model.currentPrompt, Icons.Rounded.School, Sys.subheadline, Sys.primary)
            LessonViewModel.Phase.wrong ->
                LearnLabel("Not quite — try again. ${model.currentPrompt}", Icons.AutoMirrored.Rounded.Undo,
                           Sys.subheadline, Sys.orange)
            LessonViewModel.Phase.success ->
                LearnLabel(model.successText, Icons.Rounded.CheckCircle,
                           Sys.subheadline.weight(FontWeight.Medium), Sys.green)
            LessonViewModel.Phase.finished ->
                LearnLabel("Lesson complete! 🎓", Icons.Rounded.Verified, Sys.headline, Sys.green, iconSize = 22.dp)
        }
    }
}

class LessonViewModel(val lesson: Lesson) {
    enum class Phase { prompt, wrong, success, finished }

    var session: GameSession by mutableStateOf(GameSession(GameConfig()))
        private set
    var stepIndex: Int by mutableIntStateOf(0)
        private set
    var phase: Phase by mutableStateOf(Phase.prompt)
        private set
    var successText: String by mutableStateOf("")
        private set

    private var container: DependencyContainer? = null
    private var progress: LearnProgress? = null
    private var eventTask: Job? = null
    private val scope = MainScope()

    val currentPrompt: String
        get() = if (stepIndex < lesson.steps.size) lesson.steps[stepIndex].prompt else ""

    fun attach(container: DependencyContainer, progress: LearnProgress) {
        this.container = container
        this.progress = progress
    }

    fun begin() {
        container?.sync?.track("lesson_started", mapOf("id" to lesson.id))
        loadStep()
    }

    fun stop() { eventTask?.cancel() }

    private fun loadStep() {
        if (stepIndex >= lesson.steps.size) {
            phase = Phase.finished
            progress?.markCompleted(lesson.id)
            container?.sync?.track("lesson_completed", mapOf("id" to lesson.id))
            return
        }
        eventTask?.cancel()
        val step = lesson.steps[stepIndex]
        val newSession = GameSession(GameConfig(startFEN = step.fen, opponent = GameConfig.Opponent.HumanLocal))
        session = newSession
        newSession.start()
        phase = if (phase == Phase.wrong) Phase.wrong else Phase.prompt
        eventTask = scope.launch {
            newSession.events.collect { event ->
                if (session !== newSession) return@collect
                if (event is GameEvent.MovePlayed) handleMovePlayed()
            }
        }
    }

    private fun handleMovePlayed() {
        if (stepIndex >= lesson.steps.size) return
        val played = session.moveHistory.lastOrNull()?.uci ?: return
        val step = lesson.steps[stepIndex]
        if (step.expected.contains(played.take(4))) {
            successText = step.success
            phase = Phase.success
            stepIndex += 1
            scope.launch {
                delay(1400)
                if (phase != Phase.success) return@launch
                phase = Phase.prompt
                loadStep()
            }
        } else {
            phase = Phase.wrong
            scope.launch {
                delay(700)
                loadStep()
            }
        }
    }
}
