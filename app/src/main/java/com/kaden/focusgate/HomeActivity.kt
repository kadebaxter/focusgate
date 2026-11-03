package com.kaden.focusgate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.os.SystemClock
import android.widget.Chronometer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.Toast
import com.kaden.focusgate.Ui.button
import com.kaden.focusgate.Ui.buttonRow
import com.kaden.focusgate.Ui.field
import com.kaden.focusgate.Ui.label
import com.kaden.focusgate.Ui.line
import com.kaden.focusgate.Ui.pills
import com.kaden.focusgate.Ui.progress
import com.kaden.focusgate.Ui.row

/**
 * The home screen. With no session it lists your intents; pick one and it lists only that
 * intent's apps. The Accessibility service sends you back here when you reach a blocked app.
 */
class HomeActivity : Activity() {
    companion object {
        const val EXTRA_WANTED = "wanted"   // package you were blocked from
        const val EXTRA_NOTE = "note"       // why you were sent home
        const val EXTRA_RESUME = "resume"   // intent you were in before the phone locked
        const val EXTRA_BLOCK_ID = "block"  // blocked_attempts row

        /** Answers to "Why are you here?" when you start an intent, saved as sessions.why. */
        val REASONS = listOf("Bored" to "bored", "Needed something" to "needed", "Notification" to "notification")
        const val ACTION_SWITCH = "com.kaden.focusgate.SWITCH"
    }

    /** Whether an app can be opened right now, and if not, what would let you. */
    private sealed interface Access
    private object Open : Access
    private data class Blocked(val why: String) : Access
    private data class InIntents(val intents: List<IntentDef>) : Access
    private object NotInAny : Access

    private var apps: List<Pair<String, String>> = emptyList()
    private var intents: List<IntentDef> = emptyList()
    private val db by lazy { Db.get(this) }
    private lateinit var editor: IntentEditor
    private val dialer = "com.google.android.dialer"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.BG)
    }

    override fun onResume() {
        super.onResume()
        apps = Gate.launchableApps(this)
        editor = IntentEditor(this) { render() }
        askForNotifications()
        handleSwitch()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSwitch()
        render()
    }

    override fun onPause() {
        super.onPause()
        // a block message is only for the moment you were sent here
        intent.removeExtra(EXTRA_WANTED)
        intent.removeExtra(EXTRA_NOTE)
        intent.removeExtra(EXTRA_RESUME)
        intent.removeExtra(EXTRA_BLOCK_ID)
    }


    /** "Switch" from the notification ends the session and shows the intent list. */
    private fun handleSwitch() {
        if (intent.action != ACTION_SWITCH) return
        Sessions.end(this, "switched")
        intent.action = null
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = Notifier.update(this)

    private fun appName(pkg: String) = editor.appName(pkg)
    private fun installed(pkg: String) = apps.any { it.second == pkg }
    private val settings = "com.android.settings"
    private fun alwaysAllowed() = db.alwaysAllowed() + dialer + settings
    private fun visible(pkgs: Collection<String>, sort: Boolean = true) =
        pkgs.filter { installed(it) && Gate.placeBlocking(this, it) == null }
            .let { list -> if (sort) list.sortedBy { appName(it).lowercase() } else list }

    private fun access(pkg: String): Access {
        Gate.placeBlocking(this, pkg)?.let { return Blocked("Blocked at ${it.name}") }
        if (pkg in alwaysAllowed() || Gate.activePass(this)?.first == pkg) return Open
        val target = Gate.target(this)
        if (pkg == target) return Open
        if (target != null && !Gate.isUnlocked(this)) return Blocked("Opens after ${appName(target)}")
        if (!Sessions.enabled(this)) return Open
        val s = Sessions.current(this)
        if (s != null && s.allApps && pkg in Limits.exhaustedApps(this, intents)) return Blocked("Out of time for today")
        if (s != null && (s.allApps || pkg in s.apps)) return Open
        val matches = intents.filter { pkg in it.apps }
        return if (matches.isEmpty()) NotInAny else InIntents(matches)
    }

    /** Opens [pkg], starting whichever intent it needs first. */
    private fun openOrStart(pkg: String) {
        when (val a = access(pkg)) {
            Open -> launch(pkg)
            is InIntents -> start(a.intents.first(), open = pkg)
            NotInAny -> notInAnyIntent(pkg)
            is Blocked -> toast(a.why)
        }
    }

    // --- swipe right anywhere on the home screen to open the Phone app

    private var dockView: View? = null
    private var swipeBlocked = false
    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (dx < Ui.dp(this@HomeActivity, 100) || Math.abs(dx) < 2 * Math.abs(dy) || vx < 800) return false
                openPhone()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // swipes on the side-scrolling dock scroll it instead
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = android.graphics.Rect()
            swipeBlocked = dockView?.let { it.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt()) } ?: false
        }
        if (!swipeBlocked) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openPhone() {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { launch(dialer) }
    }

    private fun render() {
        dockView = null
        intents = db.intentsByUsage()
        val wanted = intent.getStringExtra(EXTRA_WANTED)
        val note = intent.getStringExtra(EXTRA_NOTE)
        val resume = intent.getStringExtra(EXTRA_RESUME)
        val target = Gate.target(this)
        val dailyLocked = target != null && !Gate.isUnlocked(this)
        val session = Sessions.current(this)

        val col = Ui.screen(this) { header(it, session) }

        // --- why you were sent here, with one-tap ways forward
        if (note != null) {
            Ui.card(col, note) {
                if (wanted != null) {
                    when (val a = access(wanted)) {
                        is InIntents -> for (def in a.intents.sortedByDescending { it.name == resume }.take(3)) {
                            val verb = if (session != null) "Switch to" else "Open ${appName(wanted)} in"
                            val label = when {
                                def.name == resume -> "Continue ${def.name}"
                                session != null -> "$verb ${def.name} and open ${appName(wanted)}"
                                else -> "$verb ${def.name}"
                            }
                            button(label, primary = def.name == resume || resume == null) { start(def, open = wanted, ask = def.name != resume) }
                        }
                        NotInAny -> button("Add ${appName(wanted)} to an intent", primary = false) { editor.addToIntent(wanted) }
                        else -> {}
                    }
                    if (!dailyLocked && Sessions.enabled(this@HomeActivity)) {
                        button("${Sessions.JUST_CHECKING} (${Sessions.JUST_CHECKING_MINUTES} min)", primary = false) { justChecking(wanted) }
                    }
                    line("Opening a link? Long-press it → Share → Open with FocusGate.", Ui.MUTED, 13f)
                }
            }
        }

        // --- the main list
        when {
            dailyLocked -> {
                val left = (Gate.requiredMs(this) - Gate.usedMs(this) + 59_999) / 60_000
                Ui.card(col, "${appName(target!!)} first", "$left min left before other apps open today.") {
                    progress(Gate.usedMs(this@HomeActivity) / 1000, Gate.requiredMs(this@HomeActivity) / 1000)
                    row(appName(target), onLong = { appOptions(target) }) { launch(target) }
                }
            }
            !Sessions.enabled(this) -> Ui.card(col, "All apps") {
                for (pkg in visible(apps.map { it.second })) row(appName(pkg), onLong = { appOptions(pkg) }) { launch(pkg) }
            }
            session == null -> tiles(col)
            else -> sessionView(col, session)
        }

        searchCard(col)

        val always = visible(if (session != null && !session.allApps) alwaysAllowed() - session.apps else alwaysAllowed())
        if (always.isNotEmpty() && !(session?.allApps ?: false) && Sessions.enabled(this)) dock(col, always)

        Ui.card(col, "More") {
            buttonRow(
                "Stats" to { startActivity(Intent(this@HomeActivity, StatsActivity::class.java)) },
                "Settings" to { openSettings() },
            )
        }
    }

    // --- tiles (no session): the usual intent for now as a big tile, the rest in a 2-column grid

    private var showAllTiles = false

    private fun tiles(col: LinearLayout) {
        val used = Limits.usedTodayByName(this)
        fun leftMs(def: IntentDef) = def.dailyMinutes?.let { (it * 60_000L - (used[def.name] ?: 0L)).coerceAtLeast(0) }
        fun preview(def: IntentDef) = visible(def.apps, sort = false).take(3).joinToString(" · ") { appName(it) }.ifEmpty { "No apps yet" }
        fun sub(def: IntentDef): String {
            val usedMs = used[def.name] ?: 0L
            return when (val left = leftMs(def)) {
                0L -> "Done for today"
                null -> if (usedMs >= 60_000) "${preview(def)} · ${Limits.describe(usedMs)} today" else preview(def)
                else -> "${Limits.describe(left)} left today"
            }
        }

        val hero = intents.firstOrNull { leftMs(it) != 0L }
        if (hero != null) {
            val tile = tile(hero.name, sub(hero), Ui.ACCENT, Ui.BG, 120, eyebrow = "USUAL FOR THIS TIME",
                pinned = pinnedApp(hero), onPinned = { start(hero, open = pinnedApp(hero)) },
                onLong = { intentOptions(hero) }) { start(hero) }
            col.addView(tile, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@HomeActivity, 20) })
        }

        val rest = intents.filter { it != hero }.sortedBy { leftMs(it) == 0L }   // used-up limits go last
        val collapse = !showAllTiles && rest.size > 6
        val shown = if (collapse) rest.take(5) else rest
        val cells = shown.map { def ->
            val left = leftMs(def)
            val limitMs = def.dailyMinutes?.let { it * 60_000L }
            tile(def.name, sub(def), Ui.CARD, if (left == 0L) Ui.MUTED else Ui.TEXT, 104,
                subColor = if (left != null && left > 0) Ui.GOOD else Ui.MUTED,
                ring = if (limitMs != null && left != null) left.toFloat() / limitMs else null,
                pinned = pinnedApp(def), onPinned = { start(def, open = pinnedApp(def)) },
                onLong = { intentOptions(def) }) { start(def) }
        }.toMutableList()
        if (collapse) {
            val hidden = rest.drop(5)
            val names = hidden.take(3).joinToString(" · ") { it.name } + if (hidden.size > 3) " · +${hidden.size - 3}" else ""
            cells.add(tile("More", names, Ui.CARD, Ui.TEXT, 104) { showAllTiles = true; render() })
        }
        for (pair in cells.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = Ui.dp(this@HomeActivity, 12) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = Ui.dp(this@HomeActivity, 12) })
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Ui.dp(this@HomeActivity, 12) })
        }

        col.buttonRow("${Sessions.JUST_CHECKING} · ${Sessions.JUST_CHECKING_MINUTES} min" to { justChecking(null) })
    }

    /** The pinned app, if it's still installed and in the intent. */
    private fun pinnedApp(def: IntentDef) = def.pinned?.takeIf { it in def.apps && installed(it) }

    /** One intent tile. [ring] (0–1) draws a small time-left ring in the corner. */
    private fun tile(
        title: String, sub: String, bg: Int, fg: Int, heightDp: Int,
        eyebrow: String? = null, subColor: Int? = null, ring: Float? = null,
        pinned: String? = null, onPinned: (() -> Unit)? = null,
        onLong: (() -> Unit)? = null, onClick: () -> Unit,
    ): View {
        val d = { v: Int -> Ui.dp(this, v) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(bg, 20, context)
            setPadding(d(16), d(14), d(16), d(14))
            minimumHeight = d(heightDp)
            isClickable = true
            setOnClickListener { onClick() }
            onLong?.let { f -> setOnLongClickListener { f(); true } }
            eyebrow?.let { addView(Ui.text(context, it, 11f, fg, bold = true).apply { letterSpacing = 0.08f }) }
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(Ui.text(context, title, if (eyebrow != null) 28f else 18f, fg).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            ring?.let { frac ->
                top.addView(Ring(context, 4).apply { segments = listOf(Ui.GOOD to frac) }, LinearLayout.LayoutParams(d(28), d(28)))
            }
            // the pinned app is its own button: the tile opens the whole intent, the icon jumps into the app
            pinned?.let { pkg ->
                val size = if (eyebrow != null) 44 else 36
                top.addView(ImageView(context).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
                    contentDescription = "Open ${appName(pkg)}"
                    setPadding(d(2), d(2), d(2), d(2))
                    background = Ui.rounded(if (bg == Ui.ACCENT) 0x33111317 else Ui.FIELD, 999, context)
                    isClickable = true
                    setOnClickListener { onPinned?.invoke() }
                }, LinearLayout.LayoutParams(d(size), d(size)).apply { marginStart = d(8) })
            }
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (eyebrow != null) d(6) else 0 })
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(Ui.text(context, sub, 12f, subColor ?: if (bg == Ui.ACCENT) Ui.BG else Ui.MUTED).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (bg == Ui.ACCENT) alpha = 0.75f
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
        }
    }

    // --- in a session: a ring of today's time per app, the session's apps as icons, switch/end

    private fun sessionView(col: LinearLayout, session: Session) {
        val d = { v: Int -> Ui.dp(this, v) }
        val def = intents.firstOrNull { it.name == session.name }
        val now = System.currentTimeMillis()
        val usage = db.appUsageSince(session.name, Limits.startOfToday()).filter { it.second >= 30_000 }
        val usedMs = usage.sumOf { it.second }
        val limitMs = def?.dailyMinutes?.let { it * 60_000L }
        val leftMs = if (session.timed) (session.until - now).coerceAtLeast(0) else 0L
        val totalMs = (limitMs ?: (usedMs + leftMs)).coerceAtLeast(usedMs)

        val box = FrameLayout(this)
        box.addView(Ring(this).apply {
            segments = if (totalMs > 0) usage.map { (pkg, ms) -> AppColors.of(context, pkg) to ms.toFloat() / totalMs } else emptyList()
        }, FrameLayout.LayoutParams(d(220), d(220), Gravity.CENTER))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        if (session.timed) countdown(center, session.until)
        else center.addView(Ui.text(this, Limits.describe(usedMs).takeIf { usedMs > 0 } ?: "0 min", 40f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        val limitLeftMs = def?.let { Limits.remainingMs(this, it) }
        // the timer can end before the daily limit does (a 30 min timer with 54 min left today)
        val timerFirst = session.timed && limitLeftMs != null && leftMs < limitLeftMs - 60_000
        center.addView(Ui.text(this, when {
            !session.timed -> "used today"
            limitLeftMs != null && !timerFirst -> "left today"
            else -> "left in session"
        }, 13f, Ui.MUTED))
        if (!session.timed) center.addView(Ui.text(this, "Ends when you lock", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(this@HomeActivity, 4), 0, 0) })
        box.addView(center, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        col.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, d(220)).apply { topMargin = d(24) })

        // legend: each app's slice, then what's left
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val items = usage.map { (pkg, ms) -> AppColors.of(this, pkg) to "${appName(pkg)} ${Limits.describe(ms)}" } +
            listOfNotNull(if (limitMs != null) Ui.FIELD to "${Limits.describe(limitLeftMs ?: 0)} left today" else null)
        for (chunk in items.chunked(3)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for ((color, label) in chunk) {
                line.addView(View(this).apply { background = Ui.rounded(color, 5, context) }, LinearLayout.LayoutParams(d(10), d(10)).apply { marginStart = d(12) })
                line.addView(Ui.text(this, label, 13f, Ui.TEXT).apply { setPadding(d(6), 0, 0, 0) })
            }
            legend.addView(line, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = d(6) })
        }
        if (limitMs != null) legend.addView(Ui.text(this, "Daily limit: ${def.dailyMinutes} min", 13f, Ui.MUTED).apply { setPadding(0, d(6), 0, 0) })
        col.addView(legend, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(12) })

        // the session's apps as an icon grid, in the intent's saved order
        val ordered = def?.apps.orEmpty().filter { it in session.apps }
        val shown = if (session.allApps) visible(apps.map { it.second })
            else visible(ordered, sort = false) + visible(session.apps - ordered.toSet())
        if (shown.isEmpty()) col.addView(Ui.text(this, "No apps here yet. Long-press an app in search to add it.", 13f, Ui.MUTED).apply { setPadding(0, d(20), 0, 0) })
        for (chunk in shown.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (pkg in chunk) row.addView(appIcon(pkg), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            repeat(4 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(20) })
        }

        col.buttonRow(
            "Switch intent" to { Sessions.end(this, "switched"); render() },
            "End session" to { Sessions.end(this, "ended"); render() },
        )
    }

    /** Always-allowed apps as one compact, side-scrolling row of icons. */
    private fun dock(col: LinearLayout, pkgs: List<String>) {
        val d = { v: Int -> Ui.dp(this, v) }
        col.addView(Ui.text(this, "NO INTENT NEEDED", 11f, Ui.MUTED, bold = true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = d(20); marginStart = d(4) })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d(4), d(10), d(4), d(10))
        }
        for (pkg in pkgs) row.addView(appIcon(pkg, iconDp = 40, labelSp = 11f), LinearLayout.LayoutParams(d(64), WRAP_CONTENT))
        col.addView(android.widget.HorizontalScrollView(this).apply {
            dockView = this
            isHorizontalScrollBarEnabled = false
            background = Ui.rounded(Ui.CARD, 20, context)
            addView(row)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = d(8) })
    }

    private fun appIcon(pkg: String, iconDp: Int = 56, labelSp: Float = 12f): View {
        val d = { v: Int -> Ui.dp(this, v) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { launch(pkg) }
            setOnLongClickListener { appOptions(pkg); true }
            addView(ImageView(context).apply {
                runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            }, LinearLayout.LayoutParams(d(iconDp), d(iconDp)))
            addView(Ui.text(context, appName(pkg), labelSp).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(d(2), d(6), d(2), 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** A live mm:ss countdown to [until]; redraws the screen when it reaches zero. */
    private fun countdown(parent: LinearLayout, until: Long) {
        parent.addView(Chronometer(this).apply {
            isCountDown = true
            base = SystemClock.elapsedRealtime() + (until - System.currentTimeMillis())
            textSize = 44f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            var done = false
            setOnChronometerTickListener {
                if (!done && System.currentTimeMillis() >= until) {
                    done = true
                    stop()
                    render()
                }
            }
            start()
        })
    }

    private fun header(col: LinearLayout, session: Session?) {
        col.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"
            textSize = 48f
            setTextColor(Ui.TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setOnLongClickListener { openSettings(); true }
        })
        col.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMM d"; format24Hour = "EEEE, MMM d"
            textSize = 15f
            setTextColor(Ui.MUTED)
        })

        val inside = Gate.insidePlaceIds(this)
        val here = Gate.places(this).filter { it.id in inside }
        val pills = here.map { "AT ${it.name.uppercase()}" to Ui.GOOD } +
            listOfNotNull(session?.let { "IN: ${it.name.uppercase()}" to Ui.ACCENT })
        if (pills.isNotEmpty()) col.pills(*pills.toTypedArray())
    }

    private fun searchCard(col: LinearLayout) {
        Ui.card(col, "Find an app") {
            val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val search = field("Type to search")
            addView(results)

            fun matches(q: String) = apps
                .filter { it.first.contains(q, ignoreCase = true) }
                .sortedByDescending { it.first.startsWith(q, ignoreCase = true) }
                .take(8)

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    results.removeAllViews()
                    val q = s.toString().trim()
                    if (q.isEmpty()) return
                    for ((label, pkg) in matches(q)) {
                        val a = access(pkg)
                        val sub = when (a) {
                            Open -> null
                            is Blocked -> a.why
                            is InIntents -> "Starts ${a.intents.first().name}"
                            NotInAny -> "Not in any intent"
                        }
                        results.row(label, sub, if (a is Blocked) Ui.MUTED else Ui.TEXT, onLong = { appOptions(pkg) }) { openOrStart(pkg) }
                    }
                    if (results.childCount == 0) results.row("No apps match \"$q\"", color = Ui.MUTED)
                }
            })
            // Enter opens the top match
            search.imeOptions = EditorInfo.IME_ACTION_GO
            search.setOnEditorActionListener { _, _, _ ->
                matches(search.text.toString().trim()).firstOrNull()?.let { openOrStart(it.second) }
                true
            }
        }
    }

    private fun intentOptions(def: IntentDef) {
        val options = listOf<Pair<String, () -> Unit>>(
            "Start with a timer" to { chooseDuration(def) },
            "Choose apps" to { editor.editApps(def) },
            "Order apps" to { editor.reorder(def) },
            "Rename" to { editor.rename(def) },
            "Daily limit" to { editor.chooseDailyLimit(def) },
            "Pin an app" to { editor.choosePinned(def) },
        )
        AlertDialog.Builder(this)
            .setTitle(def.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun appOptions(pkg: String) {
        val session = Sessions.current(this)
        val sessionDef = session?.let { s -> intents.firstOrNull { it.name == s.name } }
        val options = buildList<Pair<String, () -> Unit>> {
            add("App info" to {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            })
            add("Uninstall" to { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) })
            if (sessionDef != null && pkg in sessionDef.apps) add("Remove from ${sessionDef.name}" to { editor.removeFromIntent(sessionDef, pkg) })
            add("Add to an intent…" to { editor.addToIntent(pkg) })
        }
        AlertDialog.Builder(this)
            .setTitle(appName(pkg))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun notInAnyIntent(pkg: String) {
        AlertDialog.Builder(this)
            .setTitle("${appName(pkg)} isn't in any intent")
            .setItems(arrayOf("${Sessions.JUST_CHECKING} (${Sessions.JUST_CHECKING_MINUTES} min)", "Add to an intent…")) { _, i ->
                if (i == 0) justChecking(pkg) else editor.addToIntent(pkg)
            }
            .show()
    }

    private fun chooseDuration(def: IntentDef) {
        val options = listOf("5 min" to 5, "15 min" to 15, "30 min" to 30, "60 min" to 60)
        AlertDialog.Builder(this)
            .setTitle("${def.name} for…")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> start(def, minutes = options[i].second) }
            .show()
    }

    /** Starts [def] after asking why; [ask] false skips the question (e.g. Continue after unlocking). */
    private fun start(def: IntentDef, minutes: Int? = null, open: String? = null, ask: Boolean = true) {
        val left = Limits.remainingMs(this, def)
        if (left == 0L) return toast("${def.name} is done for today (${def.dailyMinutes} min limit)")
        val go = { why: String? ->
            Sessions.start(this, def.name, def.apps, false, minutes, maxMs = left, why = why)
            if (open != null && open in def.apps) launch(open) else render()
        }
        if (ask) askWhy(def.name, go) else go(null)
    }

    private fun justChecking(open: String?) = askWhy(Sessions.JUST_CHECKING) { why ->
        Sessions.start(this, Sessions.JUST_CHECKING, emptySet(), true, Sessions.JUST_CHECKING_MINUTES, why = why)
        if (open != null) launch(open) else render()
    }

    /** "Why are you here?" before a session starts. The pause is the point; "Bored" asks once more. */
    private fun askWhy(name: String, then: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Why are you here?")
            .setItems(REASONS.map { it.first }.toTypedArray()) { _, i ->
                val key = REASONS[i].second
                if (key != "bored") return@setItems then(key)
                AlertDialog.Builder(this)
                    .setTitle("Fair.")
                    .setMessage("Still want to start $name, or put the phone down for a bit?")
                    .setPositiveButton("Start anyway") { _, _ -> then(key) }
                    .setNegativeButton("Not now", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
