// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Asks for the player's name when sign-in didn't give us one (email
 * sign-up, Apple after the first authorization). Friends see it in
 * invites, challenges and pushes ("Evan invited you").
 */
@Composable
fun NameScreen(source: String, onDone: () -> Unit) {
    val container = DependencyContainer.shared
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    val trimmed = name.trim()

    fun save() {
        if (trimmed.isEmpty() || saving) return
        saving = true
        scope.launch {
            container.sync.setDisplayName(trimmed)
            container.sync.track("name_saved", mapOf("source" to source))
            saving = false
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        container.sync.track("name_prompt_shown", mapOf("source" to source))
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(Sys.background).systemBarsPadding().imePadding()) {
        Text("Name", style = Sys.font(34, FontWeight.Bold), color = Sys.primary,
             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 60.dp))
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = Sys.title3.copy(color = Sys.primary),
            cursorBrush = SolidColor(Sys.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,   // .submitLabel(.continue)
            ),
            keyboardActions = KeyboardActions(onAny = { save() }),
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 28.dp).fillMaxWidth()
                .background(Sys.secondaryBackground, RoundedCornerShape(16.dp)).padding(18.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) Text("Your name", style = Sys.title3, color = Sys.tertiary)
                    inner()
                }
            },
        )
        Spacer(Modifier.weight(1f))
        CapsuleCTA(label = "Continue", enabled = trimmed.isNotEmpty() && !saving, action = { save() })
    }
}
