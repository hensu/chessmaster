// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private val friendLobbyOfferGold = rgb(0.961, 0.769, 0.318)

private val friendLobbyChoices: List<TimeControl> = listOf(
    TimeControl(3, 2),
    TimeControl(5, 0),
    TimeControl(10, 0),
    TimeControl(15, 10),
)

/** The sheet's @State and actions. */
private class FriendLobbyModel(val container: DependencyContainer) {
    var dismiss: () -> Unit = {}

    var timeControl by mutableStateOf(TimeControl(10, 0))
    var colorChoice by mutableStateOf("random")
    var game: FriendGameState? by mutableStateOf(null)
    var busy by mutableStateOf(false)
    var errorMessage: String? by mutableStateOf(null)
    var joinCode by mutableStateOf("")
    private var pollTask: Job? = null
    var showSignIn by mutableStateOf(false)
    /** No name on the account yet — ask so the invite shows who it's from. */
    var askName by mutableStateOf(false)
    /** Who the invite was texted to (Contacts), for the waiting screen. */
    var invitedName: String? by mutableStateOf(null)
    /** People played before: one tap challenges them again. */
    var recent: List<RecentFriend> by mutableStateOf(emptyList())

    private val scope = MainScope()

    // MARK: - Actions

    /**
     * Pick a contact first (no game is created if they cancel), then
     * create the invite and open the SMS app addressed to them.
     */
    fun inviteFromContacts() {
        if (!container.sync.isSignedIn) { showSignIn = true; return }
        container.sync.track("friend_contacts_opened")
        ContactInviter.shared.pickPhone { name, phone ->
            container.sync.track("friend_contact_picked")
            scope.launch {
                val state = create(source = "contacts") ?: return@launch
                invitedName = name
                textInvite(phone, state)
            }
        }
    }

    fun textInvite(phone: String, state: FriendGameState) {
        val sync = container.sync
        ContactInviter.shared.sendText(phone, FriendInvite.message(state)) { result ->
            sync.track("friend_text_sent", mapOf("result" to result))
        }
    }

    /**
     * Challenge someone already played: no link needed, it appears on
     * their Home screen to accept.
     */
    fun challenge(friend: RecentFriend) {
        scope.launch {
            if (create(source = "rematch", opponent = friend.id) != null) {
                invitedName = friend.name
            }
        }
    }

    fun share() {
        scope.launch { create() }
    }

    suspend fun create(source: String = "share", opponent: UUID? = null): FriendGameState? {
        if (!container.sync.isSignedIn) { showSignIn = true; return null }
        busy = true
        errorMessage = null
        try {
            // "Sam joined your game" arrives as a notification when you're not in the app.
            NotificationPermission.requestFullIfNeeded()
            val color = if (colorChoice == "random") (if (Random.nextBoolean()) "white" else "black") else colorChoice
            val state = container.sync.createFriendGame(
                initialSeconds = timeControl.initial.inWholeSeconds.toInt(),
                incrementSeconds = timeControl.increment.inWholeSeconds.toInt(),
                hostColor = color,
                opponent = opponent,
            )
            container.sync.track("friend_invite_created",
                                 mapOf("time_control" to timeControl.label, "source" to source))
            waitFor(state)
            return state
        } catch (e: CancellationException) {
            throw e
        } catch (error: FriendGameError) {
            errorMessage = error.message
        } catch (error: Exception) {
            errorMessage = FriendGameError.Other("").message
        } finally {
            busy = false
        }
        return null
    }

    fun waitFor(state: FriendGameState) {
        game = state
        // No Realtime on Android: re-read the row every two seconds while
        // the lobby is open.
        pollTask?.cancel()
        pollTask = scope.launch {
            while (isActive) {
                delay(2.seconds)
                val fresh = try {
                    container.sync.friendGame(state.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                game = fresh
                if (fresh.status == "active") {
                    container.startFriendGame(fresh)
                    dismiss()
                    return@launch
                } else if (fresh.isOver) {
                    game = null
                    return@launch
                }
            }
        }
    }

    fun stopWatching() {
        pollTask?.cancel()
    }

    fun cancel() {
        stopWatching()
        val game = game
        if (game != null && game.status == "waiting") {
            val sync = container.sync
            MainScope().launch { runCatching { sync.endFriendGame(game.id, "aborted") } }
        }
        dismiss()
    }
}

/**
 * "Play a friend": pick a time control, send the invite link by text, wait
 * for the friend to open it. Also joins by code. When the friend arrives the
 * game opens app-wide (DependencyContainer.activeFriendGame).
 */
@Composable
fun FriendLobbySheet(
    /** An invite already created (e.g. the host tapped their own link). */
    initial: FriendGameState? = null,
    onDismiss: () -> Unit = {},
) {
    val container = DependencyContainer.shared
    val model = remember { FriendLobbyModel(container) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    model.dismiss = { currentDismiss() }
    val game = model.game

    ScreenScaffold(
        "Play a Friend",
        modifier = Modifier.trackScreen("FriendLobby"),
        leading = { ToolbarButton(if (game == null) "Close" else "Cancel invite") { model.cancel() } },
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (game != null) FriendLobbyWaiting(model, game) else FriendLobbySetup(model)
        }
    }

    LaunchedEffect(Unit) {
        if (initial != null && model.game == null) model.waitFor(initial)
        model.askName = container.sync.isSignedIn && container.sync.displayName == null
    }
    LaunchedEffect(Unit) {
        model.recent = container.sync.recentFriends()
        container.sync.track("friend_lobby_loaded", mapOf("friends" to model.recent.size.toString()))
    }
    DisposableEffect(Unit) { onDispose { model.stopWatching() } }

    if (model.showSignIn) {
        val closed = {
            model.showSignIn = false
            model.askName = container.sync.isSignedIn && container.sync.displayName == null
        }
        Sheet(onDismiss = closed) {
            AccountSheet(sync = container.sync, subtitle = "Sign in so your friend can find you.", onDismiss = closed)
        }
    }
    if (model.askName) {
        Sheet(onDismiss = { model.askName = false }, dismissable = false) {
            NameScreen(source = "lobby", onDone = { model.askName = false })
        }
    }
}

/** `.borderedProminent` green / `.bordered` button, `.controlSize(.large)`, full width. */
@Composable
private fun FriendLobbyButton(
    text: String,
    icon: ImageVector,
    prominent: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (prominent) Color.White else Sys.accent
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (prominent) Sys.green else Sys.tertiaryFill)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = if (prominent) 20.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = if (prominent) Sys.headline else Sys.subheadline.weight(FontWeight.SemiBold), color = tint)
    }
}

// MARK: - Setup

@Composable
private fun FriendLobbySetup(model: FriendLobbyModel) {
    val container = model.container
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Send a link by text — your friend taps it and the game starts. Friend games are unrated, and you both get the free game review afterwards.",
             style = Sys.subheadline, color = Sys.secondary)

        Row(
            Modifier.fillMaxWidth().background(friendLobbyOfferGold.opacity(0.12), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.CardGiftcard, contentDescription = null, tint = friendLobbyOfferGold,
                 modifier = Modifier.size(18.dp))
            Text("Finish a game together and you each get a free month of Diamond.",
                 style = Sys.subheadline.weight(FontWeight.SemiBold), color = friendLobbyOfferGold)
        }

        Text("Time control", style = Sys.headline, color = Sys.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tc in friendLobbyChoices) {
                val selected = model.timeControl == tc
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Sys.green.opacity(0.25) else Sys.secondaryGroupedBackground)
                        .border(2.dp, if (selected) Sys.green else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { model.timeControl = tc },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Text(tc.label, style = Sys.headline.copy(fontFeatureSettings = "tnum"), color = Sys.primary)
                    Text(tc.category.rawValue.replaceFirstChar { it.uppercase() }, style = Sys.caption2,
                         color = Sys.primary)
                }
            }
        }

        if (model.recent.isNotEmpty()) {
            Text("Play again", style = Sys.headline, color = Sys.primary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (friend in model.recent) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Sys.secondaryGroupedBackground)
                            .clickable(enabled = !model.busy) { model.challenge(friend) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = Sys.green,
                             modifier = Modifier.size(28.dp))
                        Column {
                            Text(friend.name, style = Sys.headline, color = Sys.primary)
                            Text(if (friend.games == 1) "1 game" else "${friend.games} games",
                                 style = Sys.caption, color = Sys.secondary)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Challenge", style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.green)
                    }
                }
            }
        }

        Text("You play", style = Sys.headline, color = Sys.primary)
        FriendLobbySegmented(
            options = listOf("random" to "Random", "white" to "White", "black" to "Black"),
            selection = model.colorChoice,
            onSelect = { model.colorChoice = it },
        )

        FriendLobbyButton("Invite from Contacts", Icons.Rounded.PersonAdd, prominent = true,
                          enabled = !model.busy, busy = model.busy,
                          modifier = Modifier.testTag("inviteFromContacts")) {
            model.inviteFromContacts()
        }

        FriendLobbyButton("Share invite link instead", Icons.Rounded.IosShare, prominent = false,
                          enabled = !model.busy) {
            model.share()
        }

        model.errorMessage?.let { Text(it, style = Sys.footnote, color = Sys.red) }

        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.5.dp).background(Sys.separator))

        Text("Got a code from a friend?", style = Sys.headline, color = Sys.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val codeStyle = Sys.title3.copy(fontFamily = FontFamily.Monospace)
            BasicTextField(
                value = model.joinCode,
                onValueChange = { model.joinCode = it },
                singleLine = true,
                textStyle = codeStyle.copy(color = Sys.primary),
                cursorBrush = SolidColor(Sys.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                                                  autoCorrectEnabled = false),
                modifier = Modifier.weight(1f)
                    .background(Sys.secondaryGroupedBackground, RoundedCornerShape(10.dp)).padding(10.dp),
                decorationBox = { inner ->
                    Box {
                        if (model.joinCode.isEmpty()) Text("ABC123", style = codeStyle, color = Sys.tertiary)
                        inner()
                    }
                },
            )
            val canJoin = model.joinCode.trim(' ').length >= 6
            Text("Join", style = Sys.body, color = Sys.accent,
                 modifier = Modifier.alpha(if (canJoin) 1f else 0.5f).clip(RoundedCornerShape(8.dp))
                     .background(Sys.tertiaryFill)
                     .clickable(enabled = canJoin) {
                         container.pendingInviteCode = model.joinCode.uppercase()
                         model.dismiss()
                     }
                     .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** Picker(.segmented). */
@Composable
private fun FriendLobbySegmented(options: List<Pair<String, String>>, selection: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Sys.tertiaryFill, RoundedCornerShape(9.dp)).padding(2.dp)) {
        for ((tag, title) in options) {
            val selected = tag == selection
            Box(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (selected) (if (Sys.dark) hex(0x636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(tag) },
                contentAlignment = Alignment.Center,
            ) {
                Text(title, style = Sys.footnote.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium),
                     color = Sys.primary)
            }
        }
    }
}

// MARK: - Waiting for the friend

@Composable
private fun FriendLobbyWaiting(model: FriendLobbyModel, game: FriendGameState) {
    val container = model.container
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CircularProgressIndicator(Modifier.padding(top = 24.dp).size(36.dp), color = Sys.secondary, strokeWidth = 3.dp)
        Text(model.invitedName?.let { "Waiting for $it…" } ?: game.guestName?.let { "Waiting for $it…" }
                 ?: "Waiting for your friend…",
             style = Sys.title3.bold(), color = Sys.primary, textAlign = TextAlign.Center)
        Text(if (game.guestID != null) "They'll see your challenge on their Home screen. Keep this screen open."
             else "The game starts as soon as they open the link. Keep this screen open.",
             style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center)

        Text(game.code,
             style = Sys.font(34, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
             color = Sys.primary,
             modifier = Modifier.background(Sys.secondaryGroupedBackground, RoundedCornerShape(12.dp))
                 .padding(vertical = 8.dp, horizontal = 20.dp))

        FriendLobbyButton("Send invite", Icons.Rounded.IosShare, prominent = true) {
            Platform.shareText(FriendInvite.message(game))
        }

        FriendLobbyButton("Text someone from Contacts", Icons.Rounded.PersonAdd, prominent = false) {
            container.sync.track("friend_contacts_opened")
            ContactInviter.shared.pickPhone { name, phone ->
                container.sync.track("friend_contact_picked")
                model.invitedName = name
                model.textInvite(phone, game)
            }
        }

        Text("🎁 Finish a game together and you each get a free month of Diamond (claim within 30 days).",
             style = Sys.footnote, color = Sys.secondary, textAlign = TextAlign.Center)
    }
}
