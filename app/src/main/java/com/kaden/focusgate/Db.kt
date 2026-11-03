package com.kaden.focusgate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.LocalDate

/** [apps] is in display order; [dailyMinutes] null = no daily limit. */
data class IntentDef(
    val id: Long,
    val name: String,
    val apps: List<String>,
    val dailyMinutes: Int? = null,
    val pinned: String? = null,   // app a tap on its tile opens
)

/** A session clipped to a time range; [end] is "now" for one that's still running. */
data class SessionRow(val name: String, val start: Long, val end: Long)

/**
 * SQLite storage for intents and history. Timestamps are epoch milliseconds.
 * Export with [exportToDownloads] to get a copy you can open on a computer.
 */
class Db private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx, "focusgate.db", null, 8) {

    companion object {
        /** An app Android put back on screen when you unlocked; not something you tried to open. */
        const val RESUMED_AFTER_UNLOCK = "resumed_after_unlock"

        @Volatile private var instance: Db? = null
        fun get(ctx: Context) = instance ?: synchronized(this) {
            instance ?: Db(ctx.applicationContext).also { instance = it }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE intents (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            position INTEGER NOT NULL DEFAULT 0,
            daily_minutes INTEGER,            -- NULL = no daily limit
            pinned_package TEXT)              -- opened when you tap its tile""")
        db.execSQL("""CREATE TABLE intent_apps (
            intent_id INTEGER NOT NULL REFERENCES intents(id) ON DELETE CASCADE,
            package TEXT NOT NULL,
            position INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (intent_id, package))""")
        db.execSQL("CREATE TABLE always_allowed (package TEXT PRIMARY KEY)")
        // intent_name is copied (not a foreign key) so history survives deleting an intent
        db.execSQL("""CREATE TABLE sessions (
            id INTEGER PRIMARY KEY,
            intent_name TEXT NOT NULL,
            planned_minutes INTEGER,          -- NULL = until the screen locks
            started_at INTEGER NOT NULL,
            ended_at INTEGER,
            end_reason TEXT,                  -- screen_off, timer, switched, ended
            why TEXT)                         -- bored, needed, notification (asked when you start)""")
        db.execSQL("""CREATE TABLE blocked_attempts (
            id INTEGER PRIMARY KEY,
            package TEXT NOT NULL,
            reason TEXT NOT NULL,
            at INTEGER NOT NULL,
            why TEXT)                         -- bored, needed, notification""")
        createLinkPasses(db)
        createAppUsage(db)
        Seeds.populate(ctx, db)
        assignDefaultOrder(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createLinkPasses(db)
            for (pkg in listOf("com.spotify.music", "com.google.android.apps.photos")) {
                db.insertWithOnConflict("always_allowed", null, ContentValues().apply { put("package", pkg) },
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE intent_apps ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            assignDefaultOrder(db)
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE intents ADD COLUMN daily_minutes INTEGER")
        if (oldVersion < 5) createAppUsage(db)
        if (oldVersion < 6) relabelResumes(db)
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE intents ADD COLUMN pinned_package TEXT")
            db.execSQL("ALTER TABLE blocked_attempts ADD COLUMN why TEXT")
        }
        if (oldVersion < 8) db.execSQL("ALTER TABLE sessions ADD COLUMN why TEXT")
    }

    /**
     * Before v6, unlocking back into an app logged a block. Relabel the likely ones: the first block
     * after a session ended from the screen locking, before the next session, for an app in that intent.
     */
    private fun relabelResumes(db: SQLiteDatabase) {
        val appsByIntent = mutableMapOf<String, MutableSet<String>>()
        db.rawQuery("SELECT i.name, a.package FROM intents i JOIN intent_apps a ON a.intent_id = i.id", null).use { c ->
            while (c.moveToNext()) appsByIntent.getOrPut(c.getString(0)) { mutableSetOf() }.add(c.getString(1))
        }
        val sessions = mutableListOf<Triple<String, Long, String?>>()   // name, ended_at, end_reason
        val starts = mutableListOf<Long>()
        db.rawQuery("SELECT intent_name, started_at, ended_at, end_reason FROM sessions ORDER BY started_at", null).use { c ->
            while (c.moveToNext()) {
                starts.add(c.getLong(1))
                if (!c.isNull(2)) sessions.add(Triple(c.getString(0), c.getLong(2), c.getString(3)))
            }
        }
        for ((name, endedAt, reason) in sessions) {
            if (reason != "screen_off") continue
            val nextStart = starts.firstOrNull { it > endedAt } ?: Long.MAX_VALUE
            db.rawQuery(
                "SELECT id, package, reason FROM blocked_attempts WHERE at > CAST(? AS INTEGER) AND at < CAST(? AS INTEGER) ORDER BY at LIMIT 1",
                arrayOf(endedAt.toString(), nextStart.toString()),
            ).use { c ->
                if (c.moveToFirst() && c.getString(2) == "no_intent" && c.getString(1) in appsByIntent[name].orEmpty()) {
                    db.update("blocked_attempts", ContentValues().apply { put("reason", RESUMED_AFTER_UNLOCK) },
                        "id = ?", arrayOf(c.getLong(0).toString()))
                }
            }
        }
    }

    // Apps listed here go first in that intent, in this order; the rest are alphabetical.
    private val pinnedFirst = mapOf(
        "Message" to listOf("com.google.android.apps.messaging", "com.google.android.gm"),
    )

    private fun labels() = Gate.launchableApps(ctx).associate { (label, pkg) -> pkg to label.lowercase() }

    private fun assignDefaultOrder(db: SQLiteDatabase) {
        val labels = labels()
        val byIntent = mutableMapOf<Pair<Long, String>, MutableList<String>>()
        db.rawQuery("SELECT i.id, i.name, a.package FROM intents i JOIN intent_apps a ON a.intent_id = i.id", null).use { c ->
            while (c.moveToNext()) byIntent.getOrPut(c.getLong(0) to c.getString(1)) { mutableListOf() }.add(c.getString(2))
        }
        for ((key, pkgs) in byIntent) {
            val pinned = pinnedFirst[key.second].orEmpty().filter { it in pkgs }
            writeOrder(db, key.first, pinned + (pkgs - pinned.toSet()).sortedBy { labels[it] ?: it })
        }
    }

    private fun writeOrder(db: SQLiteDatabase, intentId: Long, ordered: List<String>) {
        ordered.forEachIndexed { i, pkg ->
            db.update("intent_apps", ContentValues().apply { put("position", i) },
                "intent_id = ? AND package = ?", arrayOf(intentId.toString(), pkg))
        }
    }

    // one row per stretch of time an app was on screen during a session
    private fun createAppUsage(db: SQLiteDatabase) =
        db.execSQL("""CREATE TABLE app_usage (
            id INTEGER PRIMARY KEY,
            package TEXT NOT NULL,
            intent_name TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            ended_at INTEGER NOT NULL)""")

    private fun createLinkPasses(db: SQLiteDatabase) =
        db.execSQL("""CREATE TABLE link_passes (
            id INTEGER PRIMARY KEY,
            url TEXT NOT NULL,
            package TEXT NOT NULL,
            minutes INTEGER NOT NULL,
            at INTEGER NOT NULL)""")

    // --- intents

    fun intents(): List<IntentDef> {
        val apps = mutableMapOf<Long, MutableList<String>>()
        readableDatabase.rawQuery("SELECT intent_id, package FROM intent_apps ORDER BY position, package", null).use { c ->
            while (c.moveToNext()) apps.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
        }
        val out = mutableListOf<IntentDef>()
        readableDatabase.rawQuery("SELECT id, name, daily_minutes, pinned_package FROM intents ORDER BY position, id", null).use { c ->
            while (c.moveToNext()) out.add(IntentDef(c.getLong(0), c.getString(1), apps[c.getLong(0)].orEmpty(),
                if (c.isNull(2)) null else c.getInt(2), c.getString(3)))
        }
        return out
    }

    /**
     * Intents ordered by your habits over the last 30 days. A session started within an hour of
     * the current time counts much more, and more again on the same kind of day (weekday/weekend),
     * so the list leads with what you usually pick right now.
     */
    fun intentsByUsage(now: Long = System.currentTimeMillis()): List<IntentDef> {
        val zone = java.time.ZoneId.systemDefault()
        fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        fun weekend(d: java.time.DayOfWeek) = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY
        val at = local(now)
        val nowMin = at.hour * 60 + at.minute
        val scores = mutableMapOf<String, Double>()
        readableDatabase.rawQuery(
            "SELECT intent_name, started_at FROM sessions WHERE started_at >= ?",
            arrayOf((now - 30L * 24 * 60 * 60 * 1000).toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val t = local(c.getLong(1))
                val diff = Math.abs(t.hour * 60 + t.minute - nowMin).let { minOf(it, 24 * 60 - it) }
                var w = 1.0
                if (diff <= 60) w += if (weekend(t.dayOfWeek) == weekend(at.dayOfWeek)) 6.0 else 3.0
                else if (diff <= 120) w += 1.0
                scores.merge(c.getString(0), w, Double::plus)
            }
        }
        return intents().sortedByDescending { scores[it.name] ?: 0.0 }
    }

    fun addIntent(name: String): Long =
        writableDatabase.insert("intents", null, ContentValues().apply {
            put("name", name)
            put("position", intents().size)
        })

    fun renameIntent(id: Long, name: String) =
        writableDatabase.update("intents", ContentValues().apply { put("name", name) }, "id = ?", arrayOf(id.toString()))

    fun setDailyLimit(id: Long, minutes: Int?) =
        writableDatabase.update("intents", ContentValues().apply {
            if (minutes != null) put("daily_minutes", minutes) else putNull("daily_minutes")
        }, "id = ?", arrayOf(id.toString()))

    fun setPinned(id: Long, pkg: String?) =
        writableDatabase.update("intents", ContentValues().apply {
            if (pkg != null) put("pinned_package", pkg) else putNull("pinned_package")
        }, "id = ?", arrayOf(id.toString()))

    fun deleteIntent(id: Long) = writableDatabase.delete("intents", "id = ?", arrayOf(id.toString()))

    /** Replaces an intent's apps; apps it already had keep their order, new ones go at the end. */
    fun setIntentApps(id: Long, packages: Set<String>) {
        val existing = intents().firstOrNull { it.id == id }?.apps.orEmpty()
        val labels = labels()
        val ordered = existing.filter { it in packages } + (packages - existing.toSet()).sortedBy { labels[it] ?: it }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("intent_apps", "intent_id = ?", arrayOf(id.toString()))
            ordered.forEachIndexed { i, p ->
                db.insert("intent_apps", null, ContentValues().apply {
                    put("intent_id", id)
                    put("package", p)
                    put("position", i)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setIntentOrder(id: Long, ordered: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            writeOrder(db, id, ordered)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addAppToIntent(id: Long, pkg: String) {
        val count = intents().firstOrNull { it.id == id }?.apps?.size ?: 0
        writableDatabase.insertWithOnConflict("intent_apps", null, ContentValues().apply {
            put("intent_id", id)
            put("package", pkg)
            put("position", count)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeAppFromIntent(id: Long, pkg: String) =
        writableDatabase.delete("intent_apps", "intent_id = ? AND package = ?", arrayOf(id.toString(), pkg))

    // --- always allowed

    fun alwaysAllowed(): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT package FROM always_allowed", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun setAlwaysAllowed(packages: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("always_allowed", null, null)
            for (p in packages) db.insert("always_allowed", null, ContentValues().apply { put("package", p) })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- history

    fun startSession(name: String, minutes: Int?, why: String? = null): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("intent_name", name)
            if (why != null) put("why", why)
            if (minutes != null) put("planned_minutes", minutes) else putNull("planned_minutes")
            put("started_at", System.currentTimeMillis())
        })

    fun endSession(rowId: Long, reason: String, at: Long = System.currentTimeMillis()) =
        writableDatabase.update("sessions", ContentValues().apply {
            put("ended_at", at)
            put("end_reason", reason)
        }, "id = ? AND ended_at IS NULL", arrayOf(rowId.toString()))

    fun logAppUsage(pkg: String, intentName: String, start: Long, end: Long) =
        writableDatabase.insert("app_usage", null, ContentValues().apply {
            put("package", pkg)
            put("intent_name", intentName)
            put("started_at", start)
            put("ended_at", end)
        })

    /** Time per app in [intentName] sessions since [since], in the order you first opened them. */
    fun appUsageSince(intentName: String, since: Long): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        readableDatabase.rawQuery(
            // Android binds every argument as TEXT; inside MAX() and COALESCE() SQLite won't convert it,
            // and TEXT always sorts above numbers, so cast it back
            """SELECT package, SUM(ended_at - MAX(started_at, CAST(? AS INTEGER))) FROM app_usage
               WHERE intent_name = ? AND ended_at > CAST(? AS INTEGER) GROUP BY package ORDER BY MIN(started_at)""",
            arrayOf(since.toString(), intentName, since.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1)) }
        return out
    }

    fun setBlockWhy(id: Long, why: String) =
        writableDatabase.update("blocked_attempts", ContentValues().apply { put("why", why) }, "id = ?", arrayOf(id.toString()))

    /** Why you started sessions since [since]: (reason, count, the intent you picked most for it). */
    fun whyCounts(since: Long): List<Triple<String, Int, String>> {
        val byReason = mutableMapOf<String, MutableMap<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT why, intent_name, COUNT(*) FROM sessions WHERE started_at >= ? AND why IS NOT NULL GROUP BY why, intent_name",
            arrayOf(since.toString()),
        ).use { c -> while (c.moveToNext()) byReason.getOrPut(c.getString(0)) { mutableMapOf() }[c.getString(1)] = c.getInt(2) }
        return byReason.map { (why, intents) -> Triple(why, intents.values.sum(), intents.maxBy { it.value }.key) }
            .sortedByDescending { it.second }
    }

    fun logBlocked(pkg: String, reason: String): Long =
        writableDatabase.insert("blocked_attempts", null, ContentValues().apply {
            put("package", pkg)
            put("reason", reason)
            put("at", System.currentTimeMillis())
        })

    fun logPass(url: String, pkg: String, minutes: Int) =
        writableDatabase.insert("link_passes", null, ContentValues().apply {
            put("url", url)
            put("package", pkg)
            put("minutes", minutes)
            put("at", System.currentTimeMillis())
        })

    // --- stats

    fun sessionsSince(since: Long): List<SessionRow> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<SessionRow>()
        readableDatabase.rawQuery(
            "SELECT intent_name, started_at, COALESCE(ended_at, CAST(? AS INTEGER)) FROM sessions " +
                "WHERE COALESCE(ended_at, CAST(? AS INTEGER)) >= CAST(? AS INTEGER)",
            arrayOf(now.toString(), now.toString(), since.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(SessionRow(c.getString(0), maxOf(c.getLong(1), since), c.getLong(2)))
        }
        return out
    }

    fun topBlocked(since: Long): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT package, COUNT(*) FROM blocked_attempts WHERE at >= ? AND reason != '$RESUMED_AFTER_UNLOCK' " +
                "GROUP BY package ORDER BY 2 DESC",
            arrayOf(since.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1)) }
        return out
    }

    fun passesSince(since: Long): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT package, COUNT(*) FROM link_passes WHERE at >= ? GROUP BY package ORDER BY 2 DESC",
            arrayOf(since.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1)) }
        return out
    }

    fun sessionCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sessions", null).use { it.moveToFirst(); it.getLong(0) }

    /** Writes a consistent snapshot to Downloads and returns its file name. */
    fun exportToDownloads(): String {
        val name = "focusgate-${LocalDate.now()}.db"
        val snapshot = File(ctx.cacheDir, name).apply { delete() }
        // VACUUM INTO includes anything still sitting in the write-ahead log
        writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.sqlite3")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("couldn't create file in Downloads")
            resolver.openOutputStream(uri)!!.use { out -> snapshot.inputStream().use { it.copyTo(out) } }
        } else {
            snapshot.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name), true)
        }
        snapshot.delete()
        return name
    }
}
