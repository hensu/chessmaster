// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * "Invite from Contacts": the system contact picker (it runs in the Contacts
 * app and hands back only the one phone-number row the user taps, so no
 * READ_CONTACTS permission and no prompt), then the SMS app's composer
 * addressed to that number.
 */
class ContactInviter private constructor() {
    private var onPick: ((name: String, phone: String) -> Unit)? = null
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun pickPhone(completion: (name: String, phone: String) -> Unit) {
        val activity = MainActivity.current ?: return
        // Picking from the phone-number rows: a contact with several numbers
        // shows each one, and contacts without a number are not listed.
        val picker = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        onPick = completion
        launcher?.unregister()
        val launcher = activity.activityResultRegistry.register(
            "contactInviter.pickPhone", ActivityResultContracts.StartActivityForResult()
        ) { result ->
            this.launcher?.unregister()
            this.launcher = null
            val uri = result.data?.data
            if (result.resultCode != Activity.RESULT_OK || uri == null) {
                onPick = null   // cancelled
                return@register
            }
            val (name, phone) = details(uri) ?: return@register
            picked(name, phone)
        }
        this.launcher = launcher
        try {
            launcher.launch(picker)
        } catch (e: Exception) {
            // No contacts app on this device.
            launcher.unregister()
            this.launcher = null
            onPick = null
        }
    }

    /**
     * Opens the SMS app addressed to `phone` with `body`. Android can't tell
     * whether the text was actually sent, so `completion` gets "opened"
     * (or "failed" when no app can send a text).
     */
    fun sendText(to: String, body: String, completion: ((String) -> Unit)? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to)))
            .putExtra("sms_body", body)
        val context = MainActivity.current ?: App.context.also { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(intent)
            completion?.invoke("opened")
        } catch (e: Exception) {
            completion?.invoke("failed")
        }
    }

    private fun picked(name: String, phone: String) {
        val callback = onPick
        onPick = null
        // The picker has already closed when its result arrives, so the
        // composer can open right away (iOS waits for the dismissal).
        callback?.invoke(name, phone)
    }

    /** Name + number as plain strings, read from the picked phone-number row. */
    private fun details(uri: Uri): Pair<String, String>? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        return runCatching {
            App.context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val phone = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: phone
                name to phone
            }
        }.getOrNull()
    }

    companion object {
        val shared by lazy { ContactInviter() }
    }
}
