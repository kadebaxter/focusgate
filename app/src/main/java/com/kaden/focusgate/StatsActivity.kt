package com.kaden.focusgate

import android.app.Activity
import android.os.Bundle
import com.kaden.focusgate.Ui.bar
import com.kaden.focusgate.Ui.chips
import com.kaden.focusgate.Ui.line
import com.kaden.focusgate.Ui.row
import com.kaden.focusgate.Ui.tiles
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class StatsActivity : Activity() {
    private val ranges = listOf("Today" to 0, "7 days" to 6, "30 days" to 29)
    private var rangeIndex = 1
    private val apps by lazy { Gate.launchableApps(this) }
    private val zone = ZoneId.systemDefault()

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun appName(pkg: String) = apps.firstOrNull { it.second == pkg }?.first ?: pkg

    private fun fmt(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "<1m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    private fun render() {
        val db = Db.get(this)
        val today = LocalDate.now(zone)
        val firstDay = today.minusDays(ranges[rangeIndex].second.toLong())
        val since = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()

        val sessions = db.sessionsSince(since)
        // false alarms from system screens and hidden services (no launcher icon) were blocked
        // before FocusGate ignored them; leave them out
        val blocked = db.topBlocked(since).filter { it.first !in Gate.alwaysAllowed && Gate.isLaunchable(this, it.first) }
        val passes = db.passesSince(since)
        val total = sessions.sumOf { it.end - it.start }
        val checking = sessions.filter { it.name == Sessions.JUST_CHECKING }

        val col = Ui.screen(this, "Stats", "How you've spent your sessions.")

        Ui.card(col, "Range") {
            chips(ranges.map { it.first }, rangeIndex) { rangeIndex = it; render() }
        }

        Ui.card(col, "Overview") {
            tiles(fmt(total) to "in sessions", sessions.size.toString() to "sessions", checking.size.toString() to "just checking")
            tiles(blocked.sumOf { it.second }.toString() to "blocked", passes.sumOf { it.second }.toString() to "link passes", "" to "")
        }

        Ui.card(col, "Time by intent") {
            val byIntent = sessions.groupBy { it.name }
                .mapValues { (_, rows) -> rows.sumOf { it.end - it.start } }
                .entries.sortedByDescending { it.value }
            if (byIntent.isEmpty()) line("No sessions in this range yet.", Ui.MUTED)
            val max = byIntent.maxOfOrNull { it.value } ?: 1
            for ((name, ms) in byIntent) {
                val count = sessions.count { it.name == name }
                bar(name, "${fmt(ms)} · $count×", ms.toFloat() / max)
            }
        }

        val why = db.whyCounts(since)
        if (why.isNotEmpty()) {
            Ui.card(col, "Why you picked up your phone", "What you answered when starting a session.") {
                val labels = HomeActivity.REASONS.associate { (text, key) -> key to text }
                val max = why.maxOf { it.second }.toFloat()
                for ((key, n, top) in why) bar(labels[key] ?: key, "$n× · mostly $top", n / max)
            }
        }

        if (rangeIndex > 0) {
            Ui.card(col, "Time by day", "Counted on the day each session started.") {
                val byDay = sessions.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
                    .mapValues { (_, rows) -> rows.sumOf { it.end - it.start } }
                val days = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
                val max = days.maxOfOrNull { byDay[it] ?: 0 }?.takeIf { it > 0 } ?: 1
                // most recent first so today is at the top
                for (d in days.reversed()) {
                    val ms = byDay[d] ?: 0
                    val label = if (d == today) "Today" else "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.monthValue}/${d.dayOfMonth}"
                    bar(label, fmt(ms), ms.toFloat() / max)
                }
            }
        }

        Ui.card(col, "Most blocked apps", "Times you tried to open them when they weren't allowed.") {
            if (blocked.isEmpty()) line("Nothing blocked in this range.", Ui.MUTED)
            for ((pkg, n) in blocked.take(8)) row(appName(pkg), "$n×")
        }

        if (passes.isNotEmpty()) {
            Ui.card(col, "Link passes") {
                for ((pkg, n) in passes) row(appName(pkg), "$n×")
            }
        }
    }
}
