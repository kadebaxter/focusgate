package com.kaden.focusgate

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A saved location; [blocked] apps can't be opened while you're inside it. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Float,
    val blocked: Set<String>,
)

/** Shared settings and today's progress, stored in SharedPreferences. */
object Gate {
    // Apps that are never blocked, so the phone stays usable (and calls always work).
    val alwaysAllowed = setOf(
        "com.kaden.focusgate",
        "app.olauncher",                          // Olauncher
        "com.android.systemui",                   // status bar, notification shade, recents
        "com.google.android.dialer",              // phone + in-call screen
        "com.android.emergency",
        "com.google.android.inputmethod.latin",   // Gboard
        "com.google.android.permissioncontroller",
        "android",                                // system dialogs
        "com.android.intentresolver",             // share sheet
        "com.android.documentsui",                // file picker
        "com.google.android.documentsui",
        "com.google.android.providers.media.module", // photo picker (older)
        "com.google.android.photopicker",         // photo picker
        "com.google.android.apps.nexuslauncher",  // Pixel's Recents screen, even with another home app
        "com.google.android.googlequicksearchbox",// search bar, Assistant and Circle to Search overlays
        "com.google.android.as",                  // Android System Intelligence
        "com.google.android.as.oss",              // Private Compute Services
        "com.google.android.gms",                 // Play Services sign-in and account dialogs
        "com.android.settings",                   // Settings always works
        "com.google.android.settings.intelligence", // Settings search
        "com.google.android.packageinstaller",    // Uninstall confirmation
        "com.android.packageinstaller",
    )

    // Locking the phone while one of these is on screen doesn't end an "until I lock" session.
    val keepSessionWhenLocked = setOf("com.google.android.apps.maps")

    private val launchable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Whether [pkg] has a launcher icon, i.e. it's an app you can open yourself. Cached per package. */
    fun isLaunchable(ctx: Context, pkg: String): Boolean = launchable.getOrPut(pkg) {
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    }

    // Switching to one of these counts as leaving the daily target app.
    val homeScreens = setOf("com.kaden.focusgate", "app.olauncher", "com.google.android.apps.nexuslauncher")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gate", Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toString()

    fun target(ctx: Context): String? = prefs(ctx).getString("target", null)
    fun requiredMs(ctx: Context): Long = prefs(ctx).getInt("minutes", 5) * 60_000L

    fun setConfig(ctx: Context, target: String?, minutes: Int) =
        prefs(ctx).edit().putString("target", target).putInt("minutes", minutes).apply()

    fun usedMs(ctx: Context): Long {
        val p = prefs(ctx)
        return if (p.getString("day", "") == today()) p.getLong("used", 0) else 0L
    }

    fun addUsed(ctx: Context, ms: Long) =
        prefs(ctx).edit().putString("day", today()).putLong("used", usedMs(ctx) + ms).apply()

    fun isUnlocked(ctx: Context) = target(ctx) == null || usedMs(ctx) >= requiredMs(ctx)

    /** Launchable apps as (label, package), sorted by label. */
    fun launchableApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != ctx.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    fun places(ctx: Context): List<Place> {
        val arr = JSONArray(prefs(ctx).getString("places", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val blocked = o.getJSONArray("blocked")
            Place(
                o.getString("id"), o.getString("name"),
                o.getDouble("lat"), o.getDouble("lng"), o.getDouble("radius").toFloat(),
                (0 until blocked.length()).map { blocked.getString(it) }.toSet(),
            )
        }
    }

    fun savePlaces(ctx: Context, places: List<Place>) {
        val arr = JSONArray()
        for (p in places) {
            arr.put(JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("lat", p.lat).put("lng", p.lng).put("radius", p.radius.toDouble())
                .put("blocked", JSONArray(p.blocked.toList())))
        }
        // forget "inside" state for places that no longer exist
        val ids = places.map { it.id }.toSet()
        prefs(ctx).edit()
            .putString("places", arr.toString())
            .putStringSet("inside", insidePlaceIds(ctx).filter { it in ids }.toSet())
            .apply()
    }

    fun insidePlaceIds(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("inside", emptySet())!!.toSet()

    fun setInside(ctx: Context, placeId: String, inside: Boolean) {
        val ids = insidePlaceIds(ctx).toMutableSet()
        if (inside) ids.add(placeId) else ids.remove(placeId)
        prefs(ctx).edit().putStringSet("inside", ids).apply()
    }

    /** The place you're currently at that blocks [pkg], if any. */
    fun placeBlocking(ctx: Context, pkg: String): Place? {
        val inside = insidePlaceIds(ctx)
        return places(ctx).firstOrNull { it.id in inside && pkg in it.blocked }
    }

    // --- link passes: one app allowed for a few minutes, from the share sheet

    fun startPass(ctx: Context, pkg: String, minutes: Int) =
        prefs(ctx).edit().putString("pass_pkg", pkg).putLong("pass_until", System.currentTimeMillis() + minutes * 60_000L).apply()

    /** The app with an active link pass and when it ends, or null. */
    fun activePass(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val pkg = p.getString("pass_pkg", null) ?: return null
        val until = p.getLong("pass_until", 0)
        return if (System.currentTimeMillis() < until) pkg to until else null
    }
}
