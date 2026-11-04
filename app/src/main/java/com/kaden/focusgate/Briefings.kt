package com.kaden.focusgate

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One notification, trimmed to what a briefing shows. Kept in memory only, never saved. */
data class BriefItem(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val at: Long,
    val open: PendingIntent?,
    val reply: Notification.Action?,      // the app's own reply box, e.g. Messages or WhatsApp
    val markRead: Notification.Action?,   // the app's "Mark as read" button, if it has one
)

/**
 * Your current notifications grouped by app, so intent tiles and the session screen can show
 * what's waiting before you open anything. Filled by [BriefingService].
 */
object Briefings {
    private val items = java.util.concurrent.ConcurrentHashMap<String, BriefItem>()
    @Volatile internal var service: BriefingService? = null

    // Notifications you just replied to. Apps re-post them right after a reply ("You: ok"),
    // so for a few seconds those re-posts are cleared too.
    private val clearedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REPOST_WINDOW_MS = 10_000L

    /** Called (on any thread) when notifications change, e.g. to redraw the home screen. */
    @Volatile var onChange: (() -> Unit)? = null

    fun granted(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }

    /** Notifications from [apps], newest first. */
    fun forApps(apps: Collection<String>): List<BriefItem> =
        items.values.filter { it.pkg in apps }.sortedByDescending { it.at }

    /** One line for a tile, e.g. "Mom: dinner at 6? · +3 more". */
    fun summary(apps: Collection<String>): String? {
        val list = forApps(apps)
        val first = list.firstOrNull() ?: return null
        val line = if (first.text.isNotEmpty()) "${first.title}: ${first.text}" else first.title
        return if (list.size > 1) "$line · +${list.size - 1} more" else line
    }

    /** Sends [message] through the app's own reply action, as if you typed it in the notification. */
    fun reply(ctx: Context, item: BriefItem, message: String): Boolean {
        val action = item.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = android.os.Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        val fill = android.content.Intent()
        android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
        val sent = runCatching { action.actionIntent.send(ctx, 0, fill) }.isSuccess
        if (sent) {
            // you've answered it: mark it read in the app if possible, then clear it
            markRead(item)
            clearedAt[item.key] = System.currentTimeMillis()
            dismiss(item)
        }
        return sent
    }

    /** Taps the app's "Mark as read"; the app then clears the notification itself. */
    fun markRead(item: BriefItem): Boolean {
        val action = item.markRead ?: return false
        return runCatching { action.actionIntent.send() }.isSuccess
    }

    /** Clears the notification, like swiping it away in the shade. */
    fun dismiss(item: BriefItem) {
        service?.let { s -> runCatching { s.cancelNotification(item.key) } }
        remove(item.key)
    }

    private fun findReply(n: Notification) = n.actions?.firstOrNull { a ->
        val semantic = android.os.Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        semantic || a.remoteInputs?.any { it.allowFreeFormInput } == true
    }

    private fun findMarkRead(n: Notification) = n.actions?.firstOrNull { a ->
        val semantic = android.os.Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
        semantic || a.title?.toString()?.contains("read", ignoreCase = true) == true
    }

    internal fun replaceAll(sbns: Array<StatusBarNotification>?) {
        items.clear()
        sbns?.forEach(::put)
        onChange?.invoke()
    }

    internal fun add(sbn: StatusBarNotification) {
        val cleared = clearedAt[sbn.key]
        if (cleared != null) {
            if (System.currentTimeMillis() - cleared < REPOST_WINDOW_MS) {
                service?.let { s -> runCatching { s.cancelNotification(sbn.key) } }
                return
            }
            clearedAt.remove(sbn.key)
        }
        put(sbn)
        onChange?.invoke()
    }

    private fun put(sbn: StatusBarNotification) {
        val n = sbn.notification
        // skip ongoing ones (music, navigation, FocusGate's own) and group headers like "3 new messages"
        if (sbn.packageName == "com.kaden.focusgate") return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        items[sbn.key] = BriefItem(sbn.key, sbn.packageName, title.ifEmpty { text }, if (title.isEmpty()) "" else text,
            sbn.postTime, n.contentIntent, findReply(n), findMarkRead(n))
    }

    internal fun remove(key: String) {
        if (items.remove(key) != null) onChange?.invoke()
    }
}

/** Reads notifications (with your permission) to fill [Briefings]. Nothing leaves the phone. */
class BriefingService : NotificationListenerService() {
    override fun onListenerConnected() {
        Briefings.service = this
        Briefings.replaceAll(runCatching { activeNotifications }.getOrNull())
    }

    override fun onListenerDisconnected() {
        Briefings.service = null
        Briefings.replaceAll(null)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) = Briefings.add(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = Briefings.remove(sbn.key)
}
