package com.hensu.chessmaster

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant

/**
 * UNUserNotificationCenter on Android: local notifications delivered by AlarmManager at a
 * given time. Pending requests are keyed by identifier, like iOS, so scheduling the same
 * identifier again replaces it and `cancel` removes it. A tap opens MainActivity with the
 * request's `userInfo`, which NotificationCoordinator reads (`LocalNotifications.userInfo(intent)`).
 */
object LocalNotifications {
    private const val channelID = "chessai"
    private const val prefix = "notif."

    enum class Status { notDetermined, denied, authorized }

    /** UNUserNotificationCenter.current().notificationSettings().authorizationStatus */
    fun status(): Status {
        val enabled = NotificationManagerCompat.from(App.context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) return if (enabled) Status.authorized else Status.denied
        val granted = ContextCompat.checkSelfPermission(App.context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            granted && enabled -> Status.authorized
            Defaults.standard.bool("notifications.asked") -> Status.denied
            else -> Status.notDetermined
        }
    }

    val authorized: Boolean get() = status() == Status.authorized

    /** requestAuthorization(options: [.alert, .sound, .badge]): shows the system prompt once (Android 13+). */
    fun requestAuthorization(onResult: (Boolean) -> Unit = {}) {
        val activity = MainActivity.current
        if (Build.VERSION.SDK_INT < 33 || activity == null || status() != Status.notDetermined) {
            onResult(authorized); return
        }
        Defaults.standard.set("notifications.asked", true)
        activity.activityResultRegistry.register("notif-permission", ActivityResultContracts.RequestPermission()) {
            onResult(it)
        }.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Adds (or replaces) a pending request that fires at `fireAt`. */
    fun schedule(identifier: String, title: String, body: String, fireAt: Instant, userInfo: Map<String, String> = emptyMap()) {
        val context = App.context
        val intent = Intent(context, LocalNotificationReceiver::class.java)
            .setAction(prefix + identifier)
            .putExtra("identifier", identifier).putExtra("title", title).putExtra("body", body)
        userInfo.forEach { (k, v) -> intent.putExtra("userInfo.$k", v) }
        val pending = PendingIntent.getBroadcast(context, identifier.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt.toEpochMilli(), pending)
    }

    /** Shows a notification now (a server push that just arrived). */
    fun deliver(identifier: String, title: String, body: String, userInfo: Map<String, String> = emptyMap()) {
        val intent = Intent().putExtra("identifier", identifier).putExtra("title", title).putExtra("body", body)
        userInfo.forEach { (k, v) -> intent.putExtra("userInfo.$k", v) }
        post(App.context, intent)
    }

    /** removePendingNotificationRequests(withIdentifiers:) + removeDeliveredNotifications. */
    fun cancel(identifiers: List<String>) {
        val context = App.context
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (identifier in identifiers) {
            val intent = Intent(context, LocalNotificationReceiver::class.java).setAction(prefix + identifier)
            val pending = PendingIntent.getBroadcast(context, identifier.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms.cancel(pending); pending.cancel() }
            NotificationManagerCompat.from(context).cancel(identifier.hashCode())
        }
    }

    /** The `userInfo` of the notification that opened the app with this intent, or null. */
    fun userInfo(intent: Intent?): Map<String, String>? {
        val extras = intent?.extras ?: return null
        if (!extras.containsKey("notification.identifier")) return null
        return extras.keySet().filter { it.startsWith("userInfo.") }
            .associate { it.removePrefix("userInfo.") to (extras.getString(it) ?: "") }
    }

    internal fun post(context: Context, intent: Intent) {
        val identifier = intent.getStringExtra("identifier") ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelID, "Chess AI", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java)
            .setAction("notification.$identifier")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("notification.identifier", identifier)
        intent.extras?.keySet()?.filter { it.startsWith("userInfo.") }?.forEach { open.putExtra(it, intent.getStringExtra(it)) }
        val tap = PendingIntent.getActivity(context, identifier.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("body"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("body")))
            .setContentIntent(tap).setAutoCancel(true).build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { manager.notify(identifier.hashCode(), notification) }
        }
    }
}

class LocalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LocalNotifications.post(context, intent)
}
