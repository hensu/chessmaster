// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.rounded.Looks3
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A tactics puzzle from the bundled lichess CC0 set. `moves` are UCI:
 * moves[0] is the opponent's setup move (played automatically), then the
 * solver and the opponent alternate.
 */
@Serializable
data class Puzzle(
    val id: String,
    val fen: String,
    val moves: List<String>,
    val rating: Int,
    val category: String,
)

enum class PuzzleCategory(val rawValue: String) {
    mateIn1("mate_in_1"),
    mateIn2("mate_in_2"),
    mateIn3("mate_in_3"),
    fork("fork"),
    pin("pin"),
    skewer("skewer"),
    discoveredAttack("discovered_attack"),
    doubleCheck("double_check"),
    deflection("deflection"),
    hangingPiece("hanging_piece"),
    endgame("endgame");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            mateIn1 -> "Mate in 1"
            mateIn2 -> "Mate in 2"
            mateIn3 -> "Mate in 3"
            fork -> "Forks"
            pin -> "Pins"
            skewer -> "Skewers"
            discoveredAttack -> "Discovered Attacks"
            doubleCheck -> "Double Checks"
            deflection -> "Deflection"
            hangingPiece -> "Hanging Pieces"
            endgame -> "Endgames"
        }

    /** The SF Symbol of the Swift enum, as the nearest Material icon. */
    val icon: ImageVector
        get() = when (this) {
            mateIn1 -> Icons.Rounded.WorkspacePremium        // crown.fill
            mateIn2 -> Icons.Outlined.WorkspacePremium       // crown
            mateIn3 -> Icons.Rounded.Looks3                  // 3.circle.fill
            fork -> Icons.AutoMirrored.Rounded.CallSplit     // arrow.triangle.branch
            pin -> Icons.Rounded.PushPin                     // pin.fill
            skewer -> Icons.Rounded.SyncAlt                  // arrow.left.and.right
            discoveredAttack -> Icons.Rounded.VisibilityOff  // eye.slash.fill
            doubleCheck -> Icons.Rounded.PriorityHigh        // exclamationmark.2
            deflection -> Icons.Rounded.UTurnRight           // arrow.uturn.right
            hangingPiece -> Icons.Rounded.Warning            // exclamationmark.triangle.fill
            endgame -> Icons.Rounded.SportsScore             // flag.checkered
        }

    companion object {
        fun fromRaw(raw: String): PuzzleCategory? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A guided lesson: each step is a position, an instruction, and the
 * accepted moves.
 */
@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val subtitle: String,
    val steps: List<Step>,
) {
    @Serializable
    data class Step(
        val fen: String,
        val prompt: String,
        val expected: List<String>,
        val success: String,
    )
}

@Serializable
private class LearnModelsPuzzleFile(val puzzles: List<Puzzle> = emptyList())

@Serializable
private class LearnModelsLessonFile(val lessons: List<Lesson> = emptyList())

/**
 * Bundled content, loaded once. The 1369-puzzle file is parsed on a background thread at
 * launch (`preload`, started when LearnProgress is created) so the Learn tab opens instantly;
 * a read before that finishes simply waits for it.
 */
object LearnContent {
    val puzzles: List<Puzzle> by lazy {
        val text = Assets.text("Learn/puzzles.json") ?: return@lazy emptyList()
        runCatching { AppJson.decodeFromString(LearnModelsPuzzleFile.serializer(), text).puzzles }
            .getOrDefault(emptyList())
    }
    val lessons: List<Lesson> by lazy {
        val text = Assets.text("Learn/lessons.json") ?: return@lazy emptyList()
        runCatching { AppJson.decodeFromString(LearnModelsLessonFile.serializer(), text).lessons }
            .getOrDefault(emptyList())
    }

    /** Each category's puzzles, easiest first (computed once). */
    private val byCategory: Map<String, List<Puzzle>> by lazy {
        puzzles.groupBy { it.category }.mapValues { (_, list) -> list.sortedBy { it.rating } }
    }

    /** Swift `puzzles(in:)`. */
    fun puzzles(category: PuzzleCategory): List<Puzzle> = byCategory[category.rawValue] ?: emptyList()

    /** True once the content is parsed (Compose state, so the Learn tab can wait for it). */
    var loaded: Boolean by mutableStateOf(false)
        private set
    private var preloading = false

    /** Starts parsing in the background. Safe to call more than once. */
    fun preload() {
        if (loaded || preloading) return
        preloading = true
        MainScope().launch { awaitLoaded() }
    }

    /** Suspends until the content is parsed, without blocking the main thread. */
    suspend fun awaitLoaded() {
        if (loaded) return
        withContext(Dispatchers.Default) { puzzles; lessons; byCategory }
        loaded = true
    }
}

/** Solve/complete progress, device-local. */
class LearnProgress {
    var solvedPuzzles: Set<String> by mutableStateOf(Defaults.standard.stringArray(puzzleKey)?.toSet() ?: emptySet())
        private set
    var completedLessons: Set<String> by mutableStateOf(Defaults.standard.stringArray(lessonKey)?.toSet() ?: emptySet())
        private set

    init {
        LearnContent.preload()
    }

    fun markSolved(puzzleID: String) {
        solvedPuzzles = solvedPuzzles + puzzleID
        Defaults.standard.set(puzzleKey, solvedPuzzles.toList())
    }

    fun markCompleted(lessonID: String) {
        completedLessons = completedLessons + lessonID
        Defaults.standard.set(lessonKey, completedLessons.toList())
    }

    /** Swift `solvedCount(in:)`. */
    fun solvedCount(category: PuzzleCategory): Int =
        LearnContent.puzzles(category).count { solvedPuzzles.contains(it.id) }

    private companion object {
        const val puzzleKey = "learn.solvedPuzzles"
        const val lessonKey = "learn.completedLessons"
    }
}
