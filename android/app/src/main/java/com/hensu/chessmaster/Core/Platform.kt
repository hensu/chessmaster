package com.hensu.chessmaster

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Small platform calls the Swift code makes through UIApplication / ShareLink / UIActivityViewController. */
object Platform {
    /** UIApplication.shared.open(url) / Link(destination:) */
    fun openURL(url: String) {
        runCatching {
            App.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** ShareLink(item: text) */
    fun shareText(text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        App.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** UIActivityViewController with an image (and optional text). */
    fun shareImage(bitmap: Bitmap, text: String? = null, fileName: String = "chess-ai.png") {
        val dir = File(App.context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(App.context, "com.hensu.chessmaster.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text != null) send.putExtra(Intent.EXTRA_TEXT, text)
        App.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Bundle.main CFBundleShortVersionString / CFBundleVersion */
    val version: String get() = BuildConfig.VERSION_NAME
    val build: String get() = BuildConfig.VERSION_CODE.toString()

    /** Google Play listing (the iOS App Store page / write-review page). */
    const val storeURL = "https://play.google.com/store/apps/details?id=com.hensu.chessmaster"
    /** Google Play's manage-subscriptions page (iOS: manageSubscriptionsSheet). */
    const val manageSubscriptionsURL = "https://play.google.com/store/account/subscriptions?package=com.hensu.chessmaster"
}
