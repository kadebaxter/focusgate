package com.kaden.focusgate

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Hands a question to Muse (Meta AI, com.facebook.aura) through its share screen. It's one-way:
 * the answer shows up in Muse, not here. Muse gets a short pass so FocusGate doesn't block it.
 */
object Muse {
    const val PKG = "com.facebook.aura"
    private const val PASS_MINUTES = 15

    fun installed(ctx: Context) = Gate.isLaunchable(ctx, PKG)

    fun ask(ctx: Context, prompt: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(PKG)
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (share.resolveActivity(ctx.packageManager) == null) {
            Toast.makeText(ctx, "Muse isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        Gate.startPass(ctx, PKG, PASS_MINUTES)
        ctx.startActivity(share)
    }

    /** For a "Bored" moment: something worthwhile instead of drifting into [intentName]. */
    fun somethingBetter(intentName: String) =
        "I picked up my phone because I'm bored and was about to open $intentName. " +
            "Based on what you know from my email, Drive and calendar, give me one useful thing " +
            "I could do in the next 15 minutes instead. Keep it to two or three sentences."

    /** Tonight's plan for tomorrow, from today's phone use. Answer only, nothing written anywhere. */
    fun planTomorrow(ctx: Context): String {
        val db = Db.get(ctx)
        val since = Limits.startOfToday()
        val sessions = db.sessionsSince(since)
        val byIntent = sessions.groupBy { it.name }.mapValues { (_, r) -> r.sumOf { it.end - it.start } }
            .entries.sortedByDescending { it.value }
            .joinToString("; ") { "${it.key} ${Limits.describe(it.value)}" }
        val labels = HomeActivity.REASONS.associate { (text, key) -> key to text.lowercase() }
        val why = db.whyCounts(since).joinToString("; ") { (k, n, top) -> "${labels[k] ?: k} $n times (mostly $top)" }
        val limits = db.intents().filter { it.dailyMinutes != null }.joinToString("; ") {
            "${it.name}: ${Limits.describe(Limits.usedTodayMs(ctx, it.name))} of ${it.dailyMinutes} min"
        }
        return buildString {
            appendLine("Here's how I used my phone today:")
            appendLine("- Time by intent: ${byIntent.ifEmpty { "none" }}")
            if (why.isNotEmpty()) appendLine("- Why I picked it up: $why")
            if (limits.isNotEmpty()) appendLine("- Daily limits: $limits")
            appendLine()
            append("Using this and what you know from my email, Drive and calendar, tell me the top 3 things ")
            append("I need to do tomorrow, most important first, each in one line. Just reply here; ")
            append("don't create or edit any documents.")
        }
    }
}
