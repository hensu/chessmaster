// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private data class LicensesItem(val name: String, val license: String, val url: String) {
    val id: String get() = name
}

private val licensesItems = listOf(
    LicensesItem(name = "Stockfish", license = "GPL-3.0 — chess engine, embedded",
                 url = "https://github.com/official-stockfish/Stockfish"),
    LicensesItem(name = "chesskit-swift", license = "MIT — rules, PGN, FEN",
                 url = "https://github.com/chesskit-app/chesskit-swift"),
    LicensesItem(name = "GRDB.swift", license = "MIT — local persistence",
                 url = "https://github.com/groue/GRDB.swift"),
    LicensesItem(name = "supabase-swift", license = "MIT — auth and sync",
                 url = "https://github.com/supabase/supabase-swift"),
    LicensesItem(name = "cburnett piece set", license = "GPLv2+ / CC BY-SA 3.0 — Colin M.L. Burnett",
                 url = "https://github.com/lichess-org/lila"),
)

@Composable
fun LicensesScreen() {
    ScreenScaffold("Licenses") {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            LicensesSection(null, listOf(
                licensesRow {
                    Text("Chess AI is free software licensed under the GNU General Public License v3. It embeds the Stockfish chess engine. The complete source code of this app is available at the repository below.",
                         style = Sys.footnote, color = Sys.primary)
                },
                licensesRow {
                    LicensesLink("App source code", "https://github.com/chessmaster-app/chessmaster", bold = true)
                },
            ))
            LicensesSection("Third-party components", licensesItems.map { item ->
                licensesRow {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = Sys.subheadline.bold(), color = Sys.primary)
                        Text(item.license, style = Sys.caption, color = Sys.secondary)
                        Text(item.url, style = Sys.caption2, color = Sys.accent,
                             modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                                 Platform.openURL(item.url)
                             })
                    }
                }
            })
            LicensesSection("Sounds", listOf(licensesRow {
                Text("All sound effects and music are original synthesized audio created for Chessmaster.",
                     style = Sys.footnote, color = Sys.primary)
            }))
        }
    }
}

private fun licensesRow(content: @Composable () -> Unit): @Composable () -> Unit = content

/** Link(title, destination:) */
@Composable
private fun LicensesLink(title: String, url: String, bold: Boolean) {
    Text(title, style = if (bold) Sys.footnote.bold() else Sys.footnote, color = Sys.accent,
         modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { Platform.openURL(url) })
}

/** One inset-grouped List section: an optional header and a rounded card of rows split by separators. */
@Composable
private fun LicensesSection(header: String?, rows: List<@Composable () -> Unit>) {
    if (header != null) {
        Text(header.uppercase(), style = Sys.footnote, color = Sys.secondary,
             modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 7.dp))
    } else {
        Spacer(Modifier.height(12.dp))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sys.secondaryGroupedBackground)) {
        rows.forEachIndexed { index, row ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) { row() }
            if (index < rows.lastIndex) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Sys.separator))
            }
        }
    }
}
