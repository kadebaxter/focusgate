package com.kaden.focusgate

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Daily time limits on intents, e.g. 30 min of Games a day across all its sessions. */
object Limits {
    fun startOfToday() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Time spent in [name] sessions today, including one that's running now. */
    fun usedTodayMs(ctx: Context, name: String): Long {
        // close a timer that ran out unnoticed, so it doesn't keep counting
        Sessions.stored(ctx)?.takeIf { it.expired() }?.let { Sessions.end(ctx, "timer") }
        return Db.get(ctx).sessionsSince(startOfToday()).filter { it.name == name }.sumOf { it.end - it.start }
    }

    /** Today's session time per intent name. */
    fun usedTodayByName(ctx: Context): Map<String, Long> {
        Sessions.stored(ctx)?.takeIf { it.expired() }?.let { Sessions.end(ctx, "timer") }
        return Db.get(ctx).sessionsSince(startOfToday()).groupBy { it.name }.mapValues { (_, rows) -> rows.sumOf { it.end - it.start } }
    }

    /** What's left of [def]'s limit today; null if it has no limit. */
    fun remainingMs(ctx: Context, def: IntentDef): Long? {
        val limit = def.dailyMinutes ?: return null
        return (limit * 60_000L - usedTodayMs(ctx, def.name)).coerceAtLeast(0)
    }

    /** Apps in an intent whose limit is used up today, so "Just checking" can't open them. */
    fun exhaustedApps(ctx: Context, intents: List<IntentDef> = Db.get(ctx).intents()): Set<String> =
        intents.filter { remainingMs(ctx, it) == 0L }.flatMap { it.apps }.toSet() - Db.get(ctx).alwaysAllowed()

    fun describe(ms: Long): String {
        val m = (ms + 59_999) / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m".replace(" 0m", "") else "$m min"
    }
}
