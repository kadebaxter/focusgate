package com.kaden.focusgate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/** The ongoing "Message · 12 min left" notification with End and Switch buttons. */
object Notifier {
    private const val CHANNEL = "session"
    private const val ID = 1
    const val ACTION_END = "com.kaden.focusgate.END"

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val s = Sessions.current(ctx)
        if (s == null) {
            nm.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Current intent", NotificationManager.IMPORTANCE_LOW))
        val icon = Icon.createWithResource(ctx, R.drawable.ic_session)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, HomeActivity::class.java), flags)
        // Switch has to open an activity directly; Android blocks starting one from a notification's broadcast
        val switch = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, HomeActivity::class.java).setAction(HomeActivity.ACTION_SWITCH), flags)
        val end = PendingIntent.getBroadcast(ctx, 2,
            Intent(ctx, SessionActionReceiver::class.java).setAction(ACTION_END), flags)

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(s.name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Switch", switch).build())
            .addAction(Notification.Action.Builder(icon, "End", end).build())

        if (s.timed) {
            // live countdown, and the notification removes itself when time's up
            b.setWhen(s.until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setContentText("left in this session")
                .setTimeoutAfter((s.until - System.currentTimeMillis()).coerceAtLeast(1))
        } else {
            b.setShowWhen(false).setContentText("Until you lock your phone")
        }
        nm.notify(ID, b.build())
    }
}

class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Notifier.ACTION_END) Sessions.end(ctx, "ended")
    }
}
