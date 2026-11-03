package com.kaden.focusgate

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.kaden.focusgate.Ui.button
import com.kaden.focusgate.Ui.chips
import com.kaden.focusgate.Ui.line

/** Share a link here to open it in its app (e.g. YouTube) for a few minutes, even if that app is blocked. */
class LinkPassActivity : Activity() {
    private val durations = listOf(5, 15, 30)
    private var durationIndex = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun sharedUrl(): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }

    /** The app that opens this link, falling back to Chrome when Android would ask. */
    private fun handlerFor(url: String): String {
        val info = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)), PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = info?.activityInfo?.packageName
        return if (pkg == null || pkg == "android") "com.android.chrome" else pkg
    }

    private fun render() {
        val url = sharedUrl()
        val col = Ui.screen(this, "Open link", url ?: "No link found in what you shared.")
        if (url == null) {
            Ui.card(col, "Nothing to open") { button("Close", primary = false) { finish() } }
            return
        }

        val pkg = handlerFor(url)
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        Ui.card(col, "Open in $label for", "Only $label is unlocked, then it's blocked again. This gets logged.") {
            chips(durations.map { "${it}m" }, durationIndex) { durationIndex = it; render() }
            button("Open in $label") {
                val minutes = durations[durationIndex]
                Gate.startPass(this@LinkPassActivity, pkg, minutes)
                Db.get(this@LinkPassActivity).logPass(url, pkg, minutes)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            button("Cancel", primary = false) { finish() }
            line("", Ui.MUTED, 4f)
        }
    }
}
