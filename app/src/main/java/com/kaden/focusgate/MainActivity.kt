package com.kaden.focusgate

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.kaden.focusgate.Ui.button
import com.kaden.focusgate.Ui.field
import com.kaden.focusgate.Ui.label
import com.kaden.focusgate.Ui.line
import com.kaden.focusgate.Ui.pill
import com.kaden.focusgate.Ui.progress

class MainActivity : Activity() {
    private val apps by lazy { Gate.launchableApps(this) }

    override fun onResume() {
        super.onResume()
        Geofences.registerAll(this)
        render()
    }

    private fun appName(pkg: String?) = apps.firstOrNull { it.second == pkg }?.first ?: pkg

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }

    /** Replaces the home wallpaper (what Recents shows) with a solid [Ui.BG]; the lock screen keeps its own. */
    private fun matchWallpaper() {
        AlertDialog.Builder(this)
            .setTitle("Replace wallpaper?")
            .setMessage("Your home wallpaper will be replaced with FocusGate's dark background. Your lock screen picture stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace") { _, _ ->
                Thread {
                    val m = resources.displayMetrics
                    val bmp = android.graphics.Bitmap.createBitmap(
                        maxOf(m.widthPixels, m.heightPixels), maxOf(m.widthPixels, m.heightPixels),
                        android.graphics.Bitmap.Config.ARGB_8888,
                    ).apply { eraseColor(Ui.BG) }
                    val ok = runCatching {
                        android.app.WallpaperManager.getInstance(this).setBitmap(
                            bmp, null, true,
                            android.app.WallpaperManager.FLAG_SYSTEM,
                        )
                    }.isSuccess
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Wallpaper updated" else "Couldn't set wallpaper", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .show()
    }

    private fun render() {
        val target = Gate.target(this)
        val unlocked = Gate.isUnlocked(this)
        val usedMs = Gate.usedMs(this)
        val reqMs = Gate.requiredMs(this)
        val col = Ui.screen(this, "FocusGate", "Earn your apps, and keep some off-limits at certain places.")

        if (!isDefaultHome()) {
            Ui.card(col, "Home screen", "FocusGate works best as your home app: pick an intent, see only its apps.") {
                button("Set FocusGate as home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            }
        }

        Ui.card(col, "Wallpaper", "Use FocusGate's dark background behind Recents. Set a lock screen photo in your phone's Wallpaper settings.") {
            button("Match wallpaper to FocusGate", primary = false) { matchWallpaper() }
        }

        Ui.card(col, "Today") {
            if (!serviceEnabled()) {
                pill("BLOCKING IS OFF", Ui.BAD)
                line("FocusGate can't block anything until its Accessibility service is on.", Ui.MUTED, 13f)
                button("Turn on in Accessibility settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            } else if (target == null) {
                pill("NO DAILY APP SET", Ui.MUTED)
                line("Pick an app below to start the daily unlock rule.", Ui.MUTED, 13f)
            } else {
                pill(if (unlocked) "UNLOCKED" else "LOCKED", if (unlocked) Ui.GOOD else Ui.BAD)
                line("%.1f of %d min in %s".format(usedMs / 60_000.0, reqMs / 60_000, appName(target)), bold = true)
                progress(usedMs / 1000, reqMs / 1000)
                if (!unlocked) line("Other apps open once you hit the goal. Resets at midnight.", Ui.MUTED, 13f)
            }
        }

        Ui.card(col, "Daily unlock rule", "Other apps stay blocked each day until you've used this app long enough.") {
            label("App you have to use")
            button(appName(target) ?: "Choose an app", primary = false, enabled = unlocked) { chooseTarget() }

            label("Minutes per day")
            val minutes = field("5", (reqMs / 60_000).toString(), number = true, enabled = unlocked)
            button("Save minutes", enabled = unlocked) {
                val m = minutes.text.toString().toIntOrNull()?.coerceIn(1, 240) ?: 5
                Gate.setConfig(this@MainActivity, Gate.target(this@MainActivity), m)
                Toast.makeText(this@MainActivity, "Saved: $m min", Toast.LENGTH_SHORT).show()
                render()
            }
            if (!unlocked) line("You can change this once you're unlocked today.", Ui.MUTED, 13f)
        }

        Ui.card(col, "Intents", "Pick what you're here to do; only those apps open.") {
            val on = Sessions.enabled(this@MainActivity)
            val session = Sessions.current(this@MainActivity)
            if (!on) {
                pill("OFF", Ui.MUTED)
            } else if (session == null) {
                pill("NO INTENT RIGHT NOW", Ui.MUTED)
                line("Pick one from your home screen.", Ui.MUTED, 13f)
            } else {
                pill("IN: ${session.name.uppercase()}", Ui.GOOD)
                val left = if (session.timed) "${(session.until - System.currentTimeMillis() + 59_999) / 60_000} min left" else "Until you lock your phone"
                line(left, Ui.MUTED, 13f)
                button("End session now", primary = false) {
                    Sessions.end(this@MainActivity, "ended")
                    render()
                }
            }
            button("Manage intents", primary = false) {
                startActivity(Intent(this@MainActivity, IntentsActivity::class.java))
            }
            button(if (on) "Turn intents off" else "Turn intents on", primary = false) {
                Sessions.setEnabled(this@MainActivity, !on)
                render()
            }
        }

        Ui.card(col, "Places", "Apps that are always blocked while you're at a location.") {
            val places = Gate.places(this@MainActivity)
            val inside = Gate.insidePlaceIds(this@MainActivity)
            if (places.isEmpty()) line("No places yet.", Ui.MUTED)
            for (p in places) {
                line(p.name + if (p.id in inside) "  •  you're here" else "", if (p.id in inside) Ui.GOOD else Ui.TEXT, bold = true)
                val blocked = p.blocked.map { appName(it) }.joinToString()
                line("Blocks: ${blocked.ifEmpty { "nothing yet" }}", Ui.MUTED, 13f)
            }
            button("Manage places", primary = false) {
                startActivity(Intent(this@MainActivity, PlacesActivity::class.java))
            }
        }
    }

    private fun chooseTarget() {
        val current = apps.indexOfFirst { it.second == Gate.target(this) }
        AlertDialog.Builder(this)
            .setTitle("App you have to use")
            .setSingleChoiceItems(apps.map { it.first }.toTypedArray(), current) { dialog, i ->
                Gate.setConfig(this, apps[i].second, (Gate.requiredMs(this) / 60_000).toInt())
                dialog.dismiss()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
