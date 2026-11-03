package com.kaden.focusgate

import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast

/** Intent-editing dialogs shared by the home screen and Manage intents. [onChange] redraws the caller. */
class IntentEditor(private val activity: Activity, private val onChange: () -> Unit) {
    private val db = Db.get(activity)
    private val apps = Gate.launchableApps(activity)

    fun appName(pkg: String) = apps.firstOrNull { it.second == pkg }?.first ?: pkg

    /** Keeps a running session in step with edits to its intent. */
    private fun syncSession(name: String) {
        val def = db.intents().firstOrNull { it.name == name } ?: return
        if (Sessions.current(activity)?.name == name) Sessions.updateApps(activity, def.apps)
    }

    fun pickApps(title: String, current: Set<String>, save: (Set<String>) -> Unit) {
        val checked = BooleanArray(apps.size) { apps[it].second in current }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMultiChoiceItems(apps.map { it.first }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                // keep packages that aren't launchable (not shown in the list) instead of dropping them
                val hidden = current.filter { pkg -> apps.none { it.second == pkg } }
                save(apps.indices.filter { checked[it] }.map { apps[it].second }.toSet() + hidden)
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun editApps(def: IntentDef) = pickApps(def.name, def.apps.toSet()) {
        db.setIntentApps(def.id, it)
        syncSession(def.name)
    }

    /** Tap an app to move it up one spot; Save keeps the new order. */
    fun reorder(def: IntentDef) {
        val order = def.apps.filter { pkg -> apps.any { it.second == pkg } }.toMutableList()
        val hidden = def.apps - order.toSet()   // not launchable right now; keep them at the end
        if (order.size < 2) return toast("Add at least two apps first")

        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, order.map(::appName).toMutableList())
        val dialog = AlertDialog.Builder(activity)
            .setTitle("${def.name}: tap to move up")
            .setAdapter(adapter, null)
            .setPositiveButton("Save") { _, _ -> db.setIntentOrder(def.id, order + hidden); onChange() }
            .setNegativeButton("Cancel", null)
            .show()
        // replace the default click handler so the dialog stays open between taps
        dialog.listView.setOnItemClickListener { _, _, i, _ ->
            if (i == 0) return@setOnItemClickListener
            order.add(i - 1, order.removeAt(i))
            adapter.clear()
            adapter.addAll(order.map(::appName))
        }
    }

    fun rename(def: IntentDef) {
        val input = EditText(activity).apply { setText(def.name) }
        AlertDialog.Builder(activity)
            .setTitle("Rename ${def.name}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && n != def.name) {
                    try {
                        db.renameIntent(def.id, n)
                        Sessions.renameCurrent(activity, def.name, n)
                    } catch (e: Exception) {
                        toast("That name is taken")
                    }
                }
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Total time allowed in [def] per day, across all its sessions. */
    fun chooseDailyLimit(def: IntentDef) {
        val options = listOf("No limit" to null, "15 min" to 15, "30 min" to 30, "45 min" to 45,
            "1 hour" to 60, "1.5 hours" to 90, "2 hours" to 120)
        AlertDialog.Builder(activity)
            .setTitle("${def.name}: daily limit")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), options.indexOfFirst { it.second == def.dailyMinutes }) { d, i ->
                db.setDailyLimit(def.id, options[i].second)
                d.dismiss()
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The app a tap on [def]'s tile opens, after starting the intent. */
    fun choosePinned(def: IntentDef) {
        val options = def.apps.filter { pkg -> apps.any { it.second == pkg } }
        if (options.isEmpty()) return toast("Add apps to ${def.name} first")
        val labels = listOf("None") + options.map(::appName)
        AlertDialog.Builder(activity)
            .setTitle("${def.name}: open on tap")
            .setSingleChoiceItems(labels.toTypedArray(), def.pinned?.let { options.indexOf(it) + 1 } ?: 0) { d, i ->
                db.setPinned(def.id, if (i == 0) null else options[i - 1])
                d.dismiss()
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirmDelete(def: IntentDef) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${def.name}?")
            .setMessage("Past sessions stay in your history.")
            .setPositiveButton("Delete") { _, _ -> db.deleteIntent(def.id); onChange() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun addToIntent(pkg: String) {
        val options = db.intents().filter { pkg !in it.apps }
        if (options.isEmpty()) return toast("${appName(pkg)} is already in every intent")
        AlertDialog.Builder(activity)
            .setTitle("Add ${appName(pkg)} to…")
            .setItems(options.map { it.name }.toTypedArray()) { _, i ->
                db.addAppToIntent(options[i].id, pkg)
                syncSession(options[i].name)
                toast("Added to ${options[i].name}")
                onChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun removeFromIntent(def: IntentDef, pkg: String) {
        db.removeAppFromIntent(def.id, pkg)
        syncSession(def.name)
        toast("Removed from ${def.name}")
        onChange()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
