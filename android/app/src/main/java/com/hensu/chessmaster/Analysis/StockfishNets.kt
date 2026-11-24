// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The NNUE nets. Stockfish opens them by file path, but on Android they are APK assets, so each
 * is copied once to `filesDir/nnue/<name>`.
 *
 * The properties `big` / `small` are the file paths (null when the asset is missing), cheap to
 * read anywhere. `prepare()` does the copy; `UCIEngine.startIfNeeded` awaits it before handing
 * the paths to the engine, so callers only ever pass the paths along. The suspending `big()` /
 * `small()` return the same paths with the copy already made.
 */
object StockfishNets {
    private const val bigName = "nn-c288c895ea92.nnue"
    private const val smallName = "nn-37f18f62d772.nnue"

    val big: String? by lazy { path(bigName) }
    val small: String? by lazy { path(smallName) }

    suspend fun big(): String? = big?.also { prepare() }
    suspend fun small(): String? = small?.also { prepare() }

    private val directory: File get() = File(App.context.filesDir, "nnue")

    private fun path(name: String): String? =
        if (Assets.exists("NNUE/$name")) File(directory, name).path else null

    private val mutex = Mutex()
    @Volatile private var prepared = false

    /** Copies both nets out of the APK if they are not already there (off the main thread). */
    suspend fun prepare() {
        if (prepared) return
        mutex.withLock {
            if (prepared) return
            withContext(Dispatchers.IO) {
                if (big != null) copy(bigName)
                if (small != null) copy(smallName)
            }
            prepared = true
        }
    }

    /**
     * Copies to a temp file and renames it, so a copy that was killed halfway is never used;
     * a file whose size differs from the asset's (an app update with new nets) is copied again.
     */
    private fun copy(name: String) {
        val asset = "NNUE/$name"
        val target = File(directory, name)
        val length = assetLength(asset)
        if (target.isFile && target.length() == length) return

        directory.mkdirs()
        val temp = File(directory, "$name.tmp")
        App.context.assets.open(asset).use { input ->
            temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
        if (temp.length() != length) {
            temp.delete()
            throw IOException("NNUE copy of $name is incomplete")
        }
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("NNUE copy of $name could not be moved into place")
            }
        }
    }

    /** The asset's size. The nets are stored uncompressed (noCompress), so the APK entry has it. */
    private fun assetLength(asset: String): Long =
        runCatching { App.context.assets.openFd(asset).use { it.length } }.getOrNull()
            ?: App.context.assets.open(asset).use { it.available().toLong() }
}
