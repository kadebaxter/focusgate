package com.kaden.focusgate

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.kaden.focusgate.Ui.button
import com.kaden.focusgate.Ui.buttonRow
import com.kaden.focusgate.Ui.divider
import com.kaden.focusgate.Ui.field
import com.kaden.focusgate.Ui.label
import com.kaden.focusgate.Ui.line

class IntentsActivity : Activity() {
    private val apps by lazy { Gate.launchableApps(this) }
    private val db by lazy { Db.get(this) }
    private val editor by lazy { IntentEditor(this) { render() } }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun appName(pkg: String) = apps.firstOrNull { it.second == pkg }?.first ?: pkg

    private fun names(pkgs: Collection<String>, sort: Boolean = true) =
        pkgs.map(::appName).let { if (sort) it.sortedBy { n -> n.lowercase() } else it }.joinToString()

    private fun render() {
        val col = Ui.screen(this, "Intents", "Each intent is a list of apps. Everything else stays blocked during that session.")

        Ui.card(col, "Always allowed", "Open in every session, even before you pick an intent.") {
            line(names(db.alwaysAllowed()).ifEmpty { "Nothing yet" })
            button("Choose always-allowed apps", primary = false) {
                editor.pickApps("Always allowed", db.alwaysAllowed()) { db.setAlwaysAllowed(it) }
            }
        }

        Ui.card(col, "Your intents") {
            val intents = db.intents()
            if (intents.isEmpty()) line("No intents yet. Add one below.", Ui.MUTED)
            intents.forEachIndexed { i, def ->
                if (i > 0) divider()
                line(def.name, size = 17f, bold = true)
                line(names(def.apps, sort = false).ifEmpty { "No apps yet" }, Ui.MUTED, 13f)
                def.dailyMinutes?.let { limit ->
                    val left = Limits.remainingMs(this@IntentsActivity, def) ?: 0
                    line("Limit: $limit min a day · ${if (left == 0L) "done for today" else "${Limits.describe(left)} left"}", Ui.ACCENT, 13f)
                }
                buttonRow(
                    "Apps" to { editor.editApps(def) },
                    "Order" to { editor.reorder(def) },
                    "Limit" to { editor.chooseDailyLimit(def) },
                    "Rename" to { editor.rename(def) },
                )
                buttonRow("Delete" to { editor.confirmDelete(def) })
            }
        }

        Ui.card(col, "Add an intent") {
            label("Name")
            val name = field("e.g. Gym")
            button("Add intent") {
                val n = name.text.toString().trim()
                if (n.isEmpty() || db.intents().any { it.name.equals(n, ignoreCase = true) }) {
                    toast("Pick a new, unique name")
                    return@button
                }
                db.addIntent(n)
                render()
                db.intents().firstOrNull { it.name == n }?.let { editor.editApps(it) }
            }
        }

        Ui.card(col, "Your data", "Sessions and blocked attempts are saved in a SQLite database.") {
            line("${db.sessionCount()} sessions logged", Ui.MUTED, 13f)
            button("View stats", primary = false) { startActivity(Intent(this@IntentsActivity, StatsActivity::class.java)) }
            button("Export database to Downloads", primary = false) {
                try {
                    toast("Saved Download/${db.exportToDownloads()}")
                } catch (e: Exception) {
                    toast("Export failed: ${e.message}")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
