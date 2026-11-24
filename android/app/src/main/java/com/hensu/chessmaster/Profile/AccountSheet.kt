// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Account sign-in: email + password (with sign-up and reset), Google, or Apple.
 * Signed-in players get every finished game saved to their account.
 *
 * `subtitle`: optional line under the title (e.g. why sign-in is needed right now).
 * `onDismiss`: closes the sheet this is shown in (iOS: `dismiss()`).
 */
@Composable
fun AccountSheet(sync: SyncService, subtitle: String? = null, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    var dismissed by remember { mutableStateOf(false) }
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        onDismiss()
    }

    // Google and Apple finish in the browser and come back later (the action itself returns
    // before the session exists), so the session is observed to dismiss on success.
    val wasSignedIn = remember { sync.isSignedIn }
    val signedIn = sync.isSignedIn
    LaunchedEffect(signedIn) {
        if (signedIn && !wasSignedIn) dismiss()
    }

    /** Runs an auth action; dismisses on success (signed-in state). */
    fun run(action: suspend () -> Unit) {
        busy = true
        errorMessage = null
        MainScope().launch {
            try {
                action()
            } finally {
                busy = false
            }
            if (sync.isSignedIn) dismiss()
        }
    }

    Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Brand header
            Image(painterResource(R.drawable.launch_logo), contentDescription = null, contentScale = ContentScale.Fit,
                  modifier = Modifier.padding(top = 28.dp).size(56.dp).clip(RoundedCornerShape(13.dp)))
            Text("Chess AI", style = Sys.title2.bold().copy(fontFamily = FontFamily.Serif), color = Sys.primary,
                 modifier = Modifier.padding(top = 10.dp))

            Text(if (isSignUp) "Create account" else "Sign in", style = Sys.title3.weight(FontWeight.SemiBold),
                 color = Sys.primary, modifier = Modifier.padding(top = 26.dp))
            Box(Modifier.padding(top = 8.dp).size(64.dp, 3.dp).clip(CircleShape).background(Sys.green.opacity(0.8)))

            if (subtitle != null) {
                Text(subtitle, style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center,
                     modifier = Modifier.padding(horizontal = 24.dp).padding(top = 12.dp))
            }

            // Email + password / code
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountField(
                    value = email, onValueChange = { email = it }, placeholder = "Email",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                    ),
                )

                AccountField(
                    value = password, onValueChange = { password = it }, placeholder = "Password", secure = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                AccountPrimaryButton(if (isSignUp) "Sign Up with Email" else "Sign In with Email",
                                     enabled = email.contains("@") && password.length >= 6, busy = busy) {
                    run {
                        if (isSignUp) {
                            when (sync.signUpWithPassword(email, password)) {
                                SyncService.SignUpResult.signedIn -> {}
                                SyncService.SignUpResult.confirmEmail -> {
                                    infoMessage = "Almost there — confirm the email we just sent, then sign in."
                                    isSignUp = false
                                }
                                SyncService.SignUpResult.failed ->
                                    errorMessage = "Couldn't create the account — that email may already be registered."
                            }
                        } else {
                            sync.signInWithPassword(email, password)
                            if (!sync.isSignedIn) {
                                errorMessage = "That email and password didn't match."
                            }
                        }
                    }
                }
            }

            errorMessage?.let {
                Text(it, style = Sys.footnote, color = Sys.red, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
            infoMessage?.let {
                Text(it, style = Sys.footnote, color = Sys.green, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }

            // Providers
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                        .border(1.2.dp, Sys.secondary.opacity(0.45), CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            run { GoogleSignInHelper.signIn(sync) }
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = Sys.primary, modifier = Modifier.size(20.dp))
                    Text("Sign In with Google", style = Sys.body.weight(FontWeight.SemiBold), color = Sys.primary)
                }

                AppleSignInButton(sync = sync, onSignedIn = { dismiss() },
                                  modifier = Modifier.height(52.dp).clip(CircleShape))
            }

            if (!isSignUp) {
                Text("Forgot your password?", style = Sys.footnote, color = Sys.secondary,
                     modifier = Modifier.padding(top = 22.dp).clickable(remember { MutableInteractionSource() }, indication = null) {
                         run {
                             if (!email.contains("@")) {
                                 errorMessage = "Enter your email above first."
                                 return@run
                             }
                             if (sync.sendPasswordReset(email)) {
                                 infoMessage = "Password reset email sent — check your inbox."
                             } else {
                                 errorMessage = "Couldn't send the reset email."
                             }
                         }
                     })
            }

            Row(Modifier.padding(top = 12.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (isSignUp) "Already have an account?" else "Don't have an account?",
                     style = Sys.footnote, color = Sys.secondary)
                Text(if (isSignUp) "Sign In" else "Sign Up Here", style = Sys.footnote.weight(FontWeight.SemiBold), color = Sys.green,
                     modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                         isSignUp = !isSignUp
                         errorMessage = null
                         infoMessage = null
                     })
            }
        }

        if (busy) {
            Box(
                Modifier.matchParentSize().background(Color.Black.opacity(0.25))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.background(Sys.secondaryBackground, RoundedCornerShape(14.dp)).padding(18.dp)) {
                    CircularProgressIndicator(color = Sys.secondary, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                }
            }
        }

        Box(
            Modifier.align(Alignment.TopEnd).padding(14.dp).size(32.dp).clip(CircleShape)
                .background(Sys.secondary.opacity(0.15)).clickable { dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = Sys.secondary, modifier = Modifier.size(16.dp))
        }
    }
}

// MARK: - Pieces

@Composable
private fun AccountField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    secure: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Sys.body.copy(color = Sys.primary),
        cursorBrush = SolidColor(Sys.accent),
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth().height(52.dp)
            .background(Sys.secondary.opacity(0.08), shape)
            .border(1.2.dp, Sys.secondary.opacity(0.35), shape)
            .padding(horizontal = 16.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = Sys.body, color = Sys.tertiary)
                inner()
            }
        },
    )
}

@Composable
private fun AccountPrimaryButton(title: String, enabled: Boolean, busy: Boolean, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
            .background(if (enabled) Sys.green.opacity(0.85) else Sys.secondary.opacity(0.3))
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled && !busy) { action() },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Sys.headline, color = Color.White)
    }
}
