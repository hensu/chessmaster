// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/** Bundle.main.url(forResource: "landing-demo", withExtension: "mp4") */
private val landingDemoURL: String? by lazy {
    if (Assets.exists("Onboarding/landing-demo.mp4")) "asset:///Onboarding/landing-demo.mp4" else null
}

/**
 * Cal AI-style landing: a device-framed looping demo video, a big headline,
 * Get Started into the quiz, and a Sign In path for existing users. Shown
 * once, before the quiz, on first launch. The demo clip is bundled as
 * Resources/Onboarding/landing-demo.mp4; until it exists the frame shows a
 * live-looking static board instead.
 *
 * `onSignedIn`: existing user signed in from here — skip the quiz and the paywall.
 */
@Composable
fun LandingScreen(onGetStarted: () -> Unit, onSignedIn: () -> Unit) {
    val container = DependencyContainer.shared

    var showSignIn by remember { mutableStateOf(false) }

    // Google and Apple finish in the browser and come back later, so the session is observed.
    var finished by remember { mutableStateOf(false) }
    val wasSignedIn = remember { container.sync.isSignedIn }
    fun finish() {
        if (finished) return
        finished = true
        showSignIn = false
        onSignedIn()
    }
    val signedIn = container.sync.isSignedIn
    LaunchedEffect(signedIn) {
        if (signedIn && !wasSignedIn && showSignIn) finish()
    }

    LaunchedEffect(Unit) { container.sync.track("landing_viewed") }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))

        PhoneFrame(Modifier.weight(1f).fillMaxWidth())

        Text("Chess coaching\nmade easy", style = Sys.font(34, FontWeight.Bold), color = Sys.primary,
             textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))

        CapsuleCTA(label = "Get Started", enabled = true, modifier = Modifier.padding(top = 18.dp)) {
            container.sync.track("landing_get_started")
            onGetStarted()
        }

        Row(Modifier.padding(bottom = 10.dp).testTag("landingSignIn"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Already have an account?", style = Sys.subheadline, color = Sys.secondary)
            Text("Sign In", style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary,
                 modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                     container.sync.track("landing_signin_tapped")
                     showSignIn = true
                 })
        }
    }
    if (showSignIn) {
        val close = {
            showSignIn = false
            if (container.sync.isSignedIn) finish()
        }
        Sheet(onDismiss = close) { AccountSheet(sync = container.sync, onDismiss = close) }
    }
}

/** Rounded black "device" bezel holding the demo loop (or the board). */
@Composable
private fun PhoneFrame(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.padding(horizontal = 64.dp), contentAlignment = Alignment.Center) {
        // .aspectRatio(9.0 / 17.5, contentMode: .fit) inside the 6pt bezel.
        val ratio = 9.0f / 17.5f
        val availableWidth = (maxWidth - 12.dp).coerceAtLeast(0.dp)
        val availableHeight = (maxHeight - 12.dp).coerceAtLeast(0.dp)
        val width = min(availableWidth, availableHeight * ratio)
        val height = width / ratio
        Box(
            Modifier.size(width + 12.dp, height + 12.dp)
                .background(Color.Black, RoundedCornerShape(40.dp))
                .padding(6.dp)
                .clip(RoundedCornerShape(34.dp)),
        ) {
            val url = landingDemoURL
            if (url != null) {
                LoopingVideoView(url)
            } else {
                PlaceholderBoard()
            }
        }
    }
}

/**
 * Static mid-game position with a coach's suggestion arrow — stands in
 * for the demo video and still reads as "the app, playing".
 */
@Composable
private fun PlaceholderBoard() {
    // Italian Game middlegame — visually busy without looking random.
    val session = remember {
        GameSession(GameConfig(
            startFEN = "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 1",
            opponent = GameConfig.Opponent.HumanLocal))
    }
    Column(
        // Color(.systemGray6)
        Modifier.fillMaxSize().background(if (Sys.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)),
        verticalArrangement = Arrangement.Center,
    ) {
        BoardView(
            pieces = session.boardPieces,
            arrows = listOf(BoardArrow(Square.c1, Square.g5, Sys.green.opacity(0.8))),
            showsCoordinates = false,
            interactive = false,
        )
    }
}

/** Muted, aspect-filling, endlessly looping video (ExoPlayer on a TextureView, so the rounded clip applies). */
@Composable
private fun LoopingVideoView(url: String) {
    val player = remember {
        ExoPlayer.Builder(App.context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Like AVPlayer, stop while the app is in the background.
    LifecycleStartEffect(player) {
        player.play()
        onStopOrDispose { player.pause() }
    }
    AndroidView(
        factory = { context ->
            TextureView(context).also { view ->
                player.setVideoTextureView(view)
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> landingAspectFill(view, player.videoSize) }
            }
        },
        update = { view -> landingAspectFill(view, videoSize) },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

/** videoGravity = .resizeAspectFill: a TextureView stretches the frame to its bounds, so scale it back and crop. */
private fun landingAspectFill(view: TextureView, video: VideoSize) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || video.width <= 0 || video.height <= 0) return
    val videoWidth = video.width * video.pixelWidthHeightRatio
    val videoHeight = video.height.toFloat()
    val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
    val matrix = Matrix()
    matrix.setScale(scale * videoWidth / viewWidth, scale * videoHeight / viewHeight, viewWidth / 2f, viewHeight / 2f)
    view.setTransform(matrix)
}
