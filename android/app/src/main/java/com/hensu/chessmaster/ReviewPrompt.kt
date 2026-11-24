// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.lifecycle.Lifecycle
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asks for a Google Play rating at a happy moment — after a win, after
 * reading an AI coaching report, or after a run of clean puzzle solves.
 * The prompt is unconditional (no "do you like us?" pre-screen) and the
 * system limits how often it really shows, so we ask often and let Play
 * throttle; our gates only keep it to players who have finished a game
 * and space asks a week apart.
 */
object ReviewPrompt {
    private const val lastRequestKey = "review.lastRequestedAt"
    private const val minFinishedGames = 1
    private const val minDaysBetween = 7.0
    private const val puzzleRunTarget = 3
    /** Clean puzzle solves this launch. */
    private var puzzleRun = 0

    /** A puzzle was finished; a run of first-try solves is a happy moment. */
    fun puzzleSolved(firstTry: Boolean, container: DependencyContainer) {
        puzzleRun = if (firstTry) puzzleRun + 1 else 0
        if (puzzleRun < puzzleRunTarget) return
        puzzleRun = 0
        maybeRequest("puzzle_run", container)
    }

    /** Requests a review if every gate passes. `trigger` is for analytics. */
    fun maybeRequest(trigger: String, container: DependencyContainer) {
        if (App.arguments.contains("--uitest")) return
        val defaults = Defaults.standard
        // Never within a week of the last ask.
        if (defaults.has(lastRequestKey) &&
            Instant.now().epochSecond - defaults.double(lastRequestKey) < minDaysBetween * 86_400) return

        MainScope().launch {
            val finished = (try {
                container.games.recentGames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }).count { it.opponentType == "engine" && it.termination != "aborted" }
            if (finished < minFinishedGames) return@launch

            // Let the result UI / navigation settle first.
            delay(1500)
            // Never stack on the notification-permission alert, which is
            // also asked after a win (re-checked after the pause).
            val status = LocalNotifications.status()
            if (status == LocalNotifications.Status.notDetermined && trigger.endsWith("win")) return@launch
            val activity = MainActivity.current ?: return@launch
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            defaults.set(lastRequestKey, Instant.now().epochSecond.toDouble())
            container.sync.track("review_requested", mapOf("trigger" to trigger))
            // Never shows in debug builds (the iOS prompt never shows in TestFlight).
            if (BuildConfig.DEBUG) return@launch
            try {
                val manager = ReviewManagerFactory.create(activity)
                val info = manager.requestReview()
                manager.launchReview(activity, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Play decides whether the card shows; a failure is silent like on iOS.
            }
        }
    }
}
