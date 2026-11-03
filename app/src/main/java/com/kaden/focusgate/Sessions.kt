package com.kaden.focusgate

import android.content.Context

/** The intent you're currently in. allApps = the "Just checking" escape. */
data class Session(val name: String, val apps: Set<String>, val allApps: Boolean, val until: Long) {
    val timed get() = until > 0
    fun expired(now: Long = System.currentTimeMillis()) = timed && now >= until
}

/** Active-session state lives in SharedPreferences (fast reads); history goes to [Db]. */
object Sessions {
    const val JUST_CHECKING = "Just checking"
    const val JUST_CHECKING_MINUTES = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        if (!on) end(ctx, "ended")
        prefs(ctx).edit().putBoolean("enabled", on).apply()
    }

    /** The stored session, even if its timer has run out (check [Session.expired]). */
    fun stored(ctx: Context): Session? {
        val p = prefs(ctx)
        val name = p.getString("name", null) ?: return null
        return Session(name, p.getStringSet("apps", emptySet())!!.toSet(), p.getBoolean("all", false), p.getLong("until", 0))
    }

    fun current(ctx: Context): Session? = stored(ctx)?.takeUnless { it.expired() }

    /**
     * Starts a session; [minutes] null means "until the screen locks".
     * [maxMs] caps it (what's left of a daily limit), which always makes it a timed session.
     */
    fun start(ctx: Context, name: String, apps: Collection<String>, allApps: Boolean, minutes: Int?, maxMs: Long? = null, why: String? = null) {
        end(ctx, "switched")
        val now = System.currentTimeMillis()
        val until = listOfNotNull(minutes?.let { now + it * 60_000L }, maxMs?.let { now + it }).minOrNull() ?: 0L
        val planned = if (until > 0) ((until - now + 59_999) / 60_000).toInt() else null
        val row = Db.get(ctx).startSession(name, planned, why)
        prefs(ctx).edit()
            .putString("name", name)
            .putStringSet("apps", apps.toSet())
            .putBoolean("all", allApps)
            .putLong("until", until)
            .putLong("row", row)
            .commit()
        Notifier.update(ctx)
    }

    /** Called after the current session's intent is edited, so the change applies right away. */
    fun updateApps(ctx: Context, apps: Collection<String>) {
        if (stored(ctx) == null) return
        prefs(ctx).edit().putStringSet("apps", apps.toSet()).apply()
    }

    fun renameCurrent(ctx: Context, from: String, to: String) {
        if (stored(ctx)?.name != from) return
        prefs(ctx).edit().putString("name", to).commit()
        Notifier.update(ctx)
    }

    /** How long the screen can be off before an "until I lock" session ends. */
    const val LOCK_GRACE_MS = 30_000L

    /** Screen went off: start the grace period for an "until I lock" session. */
    fun screenOff(ctx: Context, onScreen: String?) {
        val s = stored(ctx) ?: return
        // locking during navigation shouldn't end the trip: the session lasts until you lock from another app
        if (onScreen in Gate.keepSessionWhenLocked) return
        if (!s.timed) prefs(ctx).edit().putLong("locked_at", System.currentTimeMillis()).commit()
    }

    /** Screen came back on (or the grace timer fired): end the session if the grace period ran out. */
    fun checkLock(ctx: Context, screenOn: Boolean) {
        val p = prefs(ctx)
        val lockedAt = p.getLong("locked_at", 0)
        if (lockedAt == 0L) return
        if (System.currentTimeMillis() - lockedAt >= LOCK_GRACE_MS) {
            stored(ctx)?.let { p.edit().putString("last_locked", it.name).commit() }
            end(ctx, "screen_off", lockedAt)
        }
        else if (screenOn) p.edit().remove("locked_at").commit()
    }

    /** The intent that last ended because the screen locked, for "Continue" after you unlock. */
    fun lastLocked(ctx: Context): String? = prefs(ctx).getString("last_locked", null)

    fun end(ctx: Context, reason: String, endedAt: Long? = null) {
        val p = prefs(ctx)
        val s = stored(ctx) ?: return
        // the service can notice a timer late while the phone sleeps; log the real end time
        val at = endedAt ?: if (s.timed) minOf(System.currentTimeMillis(), s.until) else System.currentTimeMillis()
        Db.get(ctx).endSession(p.getLong("row", -1), reason, at)
        p.edit().remove("name").remove("apps").remove("all").remove("until").remove("row").remove("locked_at").commit()
        Notifier.update(ctx)
    }
}
