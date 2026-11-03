package com.kaden.focusgate

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class GateService : AccessibilityService() {
    companion object {
        /** How soon after a shade tap an app has to open to count as a notification tap. */
        const val NOTIFICATION_TAP_WINDOW_MS = 4_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var enteredAt = 0L
    private var lastNagAt = 0L
    private var lastPkg: String? = null

    // Fires when a timed intent session runs out.
    private val expiryTick = Runnable {
        val s = Sessions.stored(this) ?: return@Runnable
        if (!s.expired()) return@Runnable
        Sessions.end(this, "timer")
        val screenOn = !locked()
        val onBlockable = lastPkg != null && (lastPkg !in Gate.alwaysAllowed || lastPkg == packageName)
        if (screenOn && onBlockable) goHome(null, "Time's up for ${s.name}.")
    }

    // Fires when a link pass runs out; kicks you out if you're still in that app.
    private val passTick = Runnable {
        val pkg = lastPkg ?: return@Runnable
        val sessionAllows = Sessions.current(this)?.let { it.allApps || pkg in it.apps } == true
        if (Gate.activePass(this) == null && !sessionAllows && pkg !in Gate.alwaysAllowed && pkg !in Db.get(this).alwaysAllowed()) {
            goHome(null, "Your link pass ended.")
        }
    }

    // Ends an "until I lock" session once the screen has been off for the whole grace period.
    // The handler can run late in deep sleep, so screen-on checks the elapsed time too.
    private val lockGraceTick = Runnable { Sessions.checkLock(this, screenOn = false) }

    // "Until I lock" sessions end 30s after the screen turns off; timed ones keep running.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pkgAtScreenOff = lastPkg
                    closeUsage()
                    Sessions.screenOff(ctx, lastPkg)
                    handler.removeCallbacks(lockGraceTick)
                    handler.postDelayed(lockGraceTick, Sessions.LOCK_GRACE_MS)
                }
                Intent.ACTION_SCREEN_ON -> {
                    resumePending = true
                    handler.removeCallbacks(lockGraceTick)
                    Sessions.checkLock(ctx, screenOn = true)
                }
            }
        }
    }

    private var receiverRegistered = false

    override fun onServiceConnected() {
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        receiverRegistered = true
    }

    // Fires when the required time is reached while still inside the target app.
    private val unlockTick = Runnable {
        bankTime()
        Toast.makeText(this, "FocusGate: unlocked for today", Toast.LENGTH_LONG).show()
    }

    // The app on screen when it locked; if it's the first thing back after the screen comes on,
    // Android just resumed it: you didn't open it, so it isn't logged as a block.
    private var pkgAtScreenOff: String? = null
    private var resumePending = false

    // When you last tapped something in the notification shade or on the lock screen.
    private var shadeTapAt = 0L

    // The app on screen during a session, for the per-app time on the session ring.
    private var usagePkg: String? = null
    private var usageIntent = ""
    private var usageStart = 0L

    private fun closeUsage() {
        val pkg = usagePkg ?: return
        val now = System.currentTimeMillis()
        if (now - usageStart >= 1_000) Db.get(this).logAppUsage(pkg, usageIntent, usageStart, now)
        usagePkg = null
    }

    private fun beginUsage(pkg: String, intentName: String) {
        if (usagePkg == pkg && usageIntent == intentName) return
        closeUsage()
        if (pkg in Gate.alwaysAllowed) return
        usagePkg = pkg
        usageIntent = intentName
        usageStart = System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (e.packageName == "com.android.systemui") shadeTapAt = System.currentTimeMillis()
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        // Hidden system services (e.g. Pixel Display Services) flash invisible windows; you can't
        // open an app with no launcher icon, so ignore them entirely instead of blocking them.
        if (pkg !in Gate.alwaysAllowed && !Gate.isLaunchable(this, pkg)) return
        val target = Gate.target(this)
        lastPkg = pkg
        scheduleExpiry()
        // Never block while the phone is locked: lock-screen views like Maps' black-and-white
        // navigation belong there. Blocking starts once you've unlocked.
        if (locked()) return
        // the keyboard, shade and other popups don't count as leaving the app you're in
        if (pkg != usagePkg && !(pkg in Gate.alwaysAllowed && pkg !in Gate.homeScreens)) closeUsage()
        val resumed = resumePending && pkg == pkgAtScreenOff && pkg !in Gate.alwaysAllowed
        if (!(pkg in Gate.alwaysAllowed && pkg !in Gate.homeScreens)) resumePending = false

        if (target != null && pkg == target) {
            if (enteredAt == 0L) {
                enteredAt = System.currentTimeMillis()
                val left = Gate.requiredMs(this) - Gate.usedMs(this)
                if (left > 0) handler.postDelayed(unlockTick, left)
            }
            return
        }

        // Popups like the keyboard or notification shade don't count as leaving the target app.
        if (pkg in Gate.alwaysAllowed && pkg !in Gate.homeScreens) return

        if (enteredAt != 0L) {
            handler.removeCallbacks(unlockTick)
            bankTime()
            enteredAt = 0L
        }

        if (pkg in Gate.alwaysAllowed) return
        val db = Db.get(this)
        if (pkg in db.alwaysAllowed()) return
        if (Gate.activePass(this)?.first == pkg) return

        Gate.placeBlocking(this, pkg)?.let { place ->
            db.logBlocked(pkg, "place:${place.name}")
            goHome(pkg, "${appName(pkg)} is blocked at ${place.name}.")
            return
        }

        if (target != null && !Gate.isUnlocked(this)) {
            val intent = packageManager.getLaunchIntentForPackage(target) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            val minsLeft = (Gate.requiredMs(this) - Gate.usedMs(this) + 59_999) / 60_000
            nag("FocusGate: $minsLeft more min first")
            db.logBlocked(pkg, "daily_rule")
            return
        }

        if (!Sessions.enabled(this)) return
        Sessions.stored(this)?.takeIf { it.expired() }?.let { Sessions.end(this, "timer") }
        val session = Sessions.current(this)
        if (session != null && session.allApps && pkg in Limits.exhaustedApps(this)) {
            goHome(pkg, "${appName(pkg)} is out of time for today.", blockId = db.logBlocked(pkg, "limit_used"))
            return
        }
        if (session != null && (session.allApps || pkg in session.apps)) return beginUsage(pkg, session.name)
        if (startFromNotification(pkg)) return beginUsage(pkg, Sessions.current(this)?.name ?: return)

        if (resumed && session == null) {
            db.logBlocked(pkg, Db.RESUMED_AFTER_UNLOCK)
            goHome(pkg, "Welcome back. Pick up where you left off?", resume = Sessions.lastLocked(this))
            return
        }
        val blockId = db.logBlocked(pkg, session?.let { "not_in:${it.name}" } ?: "no_intent")
        goHome(pkg, if (session != null) "${appName(pkg)} isn't part of ${session.name}." else "Pick an intent to open ${appName(pkg)}.", blockId = blockId)
    }

    /**
     * If [pkg] opened right after a tap in the notification shade, you tapped its notification:
     * start (or switch to) the intent it belongs to instead of blocking it. Picks the intent you
     * usually use at this time, skipping any whose daily limit is used up.
     */
    private fun startFromNotification(pkg: String): Boolean {
        if (System.currentTimeMillis() - shadeTapAt > NOTIFICATION_TAP_WINDOW_MS) return false
        shadeTapAt = 0L
        val def = Db.get(this).intentsByUsage().firstOrNull { pkg in it.apps && Limits.remainingMs(this, it) != 0L }
            ?: return false
        Sessions.start(this, def.name, def.apps, false, null, maxMs = Limits.remainingMs(this, def), why = "notification")
        Toast.makeText(this, "Started ${def.name}", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun locked() =
        getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    private fun appName(pkg: String) =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun goHome(wanted: String?, note: String?, resume: String? = null, blockId: Long = -1) {
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(HomeActivity.EXTRA_BLOCK_ID, blockId)
            .putExtra(HomeActivity.EXTRA_RESUME, resume)
            .putExtra(HomeActivity.EXTRA_WANTED, wanted)
            .putExtra(HomeActivity.EXTRA_NOTE, note))
    }

    private fun scheduleExpiry() {
        handler.removeCallbacks(expiryTick)
        handler.removeCallbacks(passTick)
        Sessions.stored(this)?.takeIf { it.timed }?.let {
            handler.postDelayed(expiryTick, (it.until - System.currentTimeMillis()).coerceAtLeast(0))
        }
        Gate.activePass(this)?.let { (_, until) ->
            handler.postDelayed(passTick, (until - System.currentTimeMillis()).coerceAtLeast(0))
        }
    }

    private fun nag(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastNagAt < 3_000) return
        lastNagAt = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun bankTime() {
        if (enteredAt == 0L) return
        val now = System.currentTimeMillis()
        Gate.addUsed(this, now - enteredAt)
        enteredAt = now
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(unlockTick)
        handler.removeCallbacks(expiryTick)
        handler.removeCallbacks(passTick)
        handler.removeCallbacks(lockGraceTick)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        closeUsage()
        bankTime()
        super.onDestroy()
    }
}
