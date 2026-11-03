package com.kaden.focusgate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Starter intents, matched against installed apps the first time the database is created.
 * Each app lists known package names and/or labels; anything not installed is skipped.
 */
object Seeds {
    private class App(val labels: List<String>, val packages: List<String> = emptyList())

    private fun app(vararg labels: String, pkg: String? = null) = App(labels.toList(), listOfNotNull(pkg))

    private val messenger = app("Messenger", pkg = "com.facebook.orca")
    private val messages = app("Messages", pkg = "com.google.android.apps.messaging")
    private val gmail = app("Gmail", pkg = "com.google.android.gm")
    private val gospelLiving = app("Gospel Living", "Living")
    private val teams = app("Teams", "Microsoft Teams", pkg = "com.microsoft.teams")
    private val slack = app("Slack", pkg = "com.Slack")
    private val chrome = app("Chrome", pkg = "com.android.chrome")
    private val drive = app("Drive", "Google Drive", pkg = "com.google.android.apps.docs")
    private val notebookLm = app("NotebookLM", pkg = "com.google.android.apps.labs.language.tailwind")
    private val x = app("X", "Twitter", pkg = "com.twitter.android")

    private val starter: List<Pair<String, List<App>>> = listOf(
        "Message" to listOf(
            messenger, messages, app("Marco Polo", pkg = "co.happybits.marcopolo"), gmail,
            app("Discord", pkg = "com.discord"), gospelLiving, teams,
            app("WhatsApp", pkg = "com.whatsapp"), slack, app("GroupMe", pkg = "com.groupme.android"),
        ),
        "Money" to listOf(
            app("Mercury"), app("U.S. Bank", "US Bank", "USBank"), app("Venmo", pkg = "com.venmo"),
            app("Wallet", "Google Wallet", pkg = "com.google.android.apps.walletnfcrel"),
            app("UPCU", "UPCU Mobile", "UPCU Mobile Banking"), app("PayPal", pkg = "com.paypal.android.p2pmobile"),
            app("GPay", "Google Pay", pkg = "com.google.android.apps.nbu.paisa.user"), app("Chase", pkg = "com.chase.sig.android"),
        ),
        "Church" to listOf(
            app("Gospel Library", "Library", pkg = "org.lds.ldssa"), app("Member Tools", "LDS Tools"),
            gospelLiving, app("FamilyTree", "Family Tree", pkg = "org.familysearch.mobile"),
        ),
        "Notes & writing" to listOf(
            drive, app("Docs", "Google Docs", pkg = "com.google.android.apps.docs.editors.docs"),
            app("Sheets", "Google Sheets", pkg = "com.google.android.apps.docs.editors.sheets"),
            app("Keep Notes", "Keep", pkg = "com.google.android.keep"), app("Notebook"),
            app("Adobe Scan", pkg = "com.adobe.scan.android"),
        ),
        "AI" to listOf(app("Gemini", pkg = "com.google.android.apps.bard"), notebookLm, app("Muse"), x),
        "News" to listOf(
            x, app("ESPN", pkg = "com.espn.score_center"), app("Weather"),
            app("News", "Google News", pkg = "com.google.android.apps.magazines"), chrome,
        ),
        "Food" to listOf(app("Wendy's", "Wendys"), app("Tucanos", "Tucanos Brazilian Grill")),
        "Travel" to listOf(
            app("Airbnb", pkg = "com.airbnb.android"), app("Alaska", "Alaska Airlines", "Alaska Hawaiian"),
            app("Fly Delta", "Delta", pkg = "com.delta.mobile.android"),
        ),
        "Shopping" to listOf(
            app("Amazon Shopping", "Amazon", pkg = "com.amazon.mShop.android.shopping"),
            app("BuyersApp", "Public Surplus"), app("Walmart", pkg = "com.walmart.android"),
        ),
        "Work" to listOf(
            app("GitHub", pkg = "com.github.android"), chrome, gmail,
            app("Google", pkg = "com.google.android.googlequicksearchbox"),
            app("Meet", "Google Meet", pkg = "com.google.android.apps.tachyon"), notebookLm, slack,
            app("Tailscale", pkg = "com.tailscale.ipn"), app("Termux", pkg = "com.termux"), teams, drive,
        ),
    )

    private val alwaysAllowed = listOf(
        app("Duo Mobile", pkg = "com.duosecurity.duomobile"),
        app("Authenticator", "Microsoft Authenticator", pkg = "com.azure.authenticator"),
        app("Google Authenticator", pkg = "com.google.android.apps.authenticator2"),
        app("Camera", pkg = "com.google.android.GoogleCamera"),
        app("Clock", pkg = "com.google.android.deskclock"),
        app("Spotify", pkg = "com.spotify.music"),
        app("Photos", "Google Photos", pkg = "com.google.android.apps.photos"),
    )

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun populate(ctx: Context, db: SQLiteDatabase) {
        val installed = Gate.launchableApps(ctx)
        val packages = installed.map { it.second }.toSet()
        val byLabel = installed.associate { norm(it.first) to it.second }

        fun resolve(a: App): String? =
            a.packages.firstOrNull { it in packages } ?: a.labels.firstNotNullOfOrNull { byLabel[norm(it)] }

        starter.forEachIndexed { i, (name, apps) ->
            val id = db.insert("intents", null, ContentValues().apply {
                put("name", name)
                put("position", i)
            })
            for (pkg in apps.mapNotNull(::resolve).toSet()) {
                db.insert("intent_apps", null, ContentValues().apply {
                    put("intent_id", id)
                    put("package", pkg)
                })
            }
        }
        for (pkg in alwaysAllowed.mapNotNull(::resolve).toSet()) {
            db.insert("always_allowed", null, ContentValues().apply { put("package", pkg) })
        }
    }
}
