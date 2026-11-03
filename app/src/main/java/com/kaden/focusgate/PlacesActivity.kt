package com.kaden.focusgate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kaden.focusgate.Ui.button
import com.kaden.focusgate.Ui.divider
import com.kaden.focusgate.Ui.field
import com.kaden.focusgate.Ui.label
import com.kaden.focusgate.Ui.line
import com.kaden.focusgate.Ui.pill
import java.util.UUID

class PlacesActivity : Activity() {
    private val apps by lazy { Gate.launchableApps(this) }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        // Android stops showing the "all the time" page after a denial, so fall back to App info
        if (code == 2 && !Geofences.hasBackground(this)) openAppSettings()
        Geofences.registerAll(this)
        render()
    }

    private fun appName(pkg: String) = apps.firstOrNull { it.second == pkg }?.first ?: pkg

    private fun render() {
        val places = Gate.places(this)
        val inside = Gate.insidePlaceIds(this)
        val col = Ui.screen(this, "Places", "Apps you pick for a place are blocked whenever you're there.")

        if (!Geofences.hasForeground(this) || !Geofences.hasBackground(this)) {
            Ui.card(col, "Location permission needed", "FocusGate needs \"Allow all the time\" to notice when you arrive somewhere.") {
                pill(if (Geofences.hasForeground(this@PlacesActivity)) "STEP 2 OF 2" else "STEP 1 OF 2", Ui.BAD)
                if (!Geofences.hasForeground(this@PlacesActivity)) {
                    line("Tap below and choose \"While using the app\".", Ui.MUTED, 13f)
                    button("Grant location access") {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
                    }
                } else {
                    line("Tap below and choose \"Allow all the time\".", Ui.MUTED, 13f)
                    button("Allow location all the time") {
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                    }
                }
            }
        }

        Ui.card(col, "Right now") {
            val here = places.filter { it.id in inside }
            if (here.isEmpty()) {
                pill("NOT AT A SAVED PLACE", Ui.MUTED)
            } else {
                pill("AT ${here.joinToString { it.name.uppercase() }}", Ui.GOOD)
                val blocked = here.flatMap { it.blocked }.distinct().map { appName(it) }
                if (blocked.isNotEmpty()) line("Blocked right now: ${blocked.joinToString()}", Ui.MUTED, 13f)
            }
        }

        Ui.card(col, "Your places") {
            if (places.isEmpty()) line("No places yet. Add one below.", Ui.MUTED)
            places.forEachIndexed { i, p ->
                if (i > 0) divider()
                val isHere = p.id in inside
                val lockedHere = isHere && p.blocked.isNotEmpty()

                line(p.name + if (isHere) "  •  you're here" else "", if (isHere) Ui.GOOD else Ui.TEXT, 17f, bold = true)
                line("Within ${p.radius.toInt()} m", Ui.MUTED, 13f)
                val blocked = p.blocked.map { appName(it) }.sorted().joinToString()
                line("Blocks: ${blocked.ifEmpty { "nothing yet" }}")

                button("Choose blocked apps for ${p.name}", primary = false, enabled = !lockedHere) { editBlocked(p) }
                button("Delete ${p.name}", primary = false, enabled = !lockedHere) { confirmDelete(p) }
                if (lockedHere) line("Can't change ${p.name} while you're there.", Ui.MUTED, 13f)
            }
        }

        Ui.card(col, "Add a place", "Go to the place first. Your current location becomes the center.") {
            label("Name")
            val name = field("e.g. Home")
            label("Radius in meters (100 or more works best)")
            val radius = field("150", "150", number = true)
            button("Save my current location") {
                saveCurrentLocation(name.text.toString().trim(), radius.text.toString())
            }
        }
    }

    private fun openAppSettings() {
        toast("Permissions → Location → Allow all the time")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("MissingPermission")
    private fun saveCurrentLocation(placeName: String, radiusText: String) {
        if (placeName.isEmpty()) return toast("Give the place a name first")
        if (!Geofences.hasForeground(this)) return toast("Grant location access first")
        val meters = radiusText.toFloatOrNull()?.coerceIn(100f, 2000f) ?: 150f

        toast("Getting your location…")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) return@addOnSuccessListener toast("Couldn't get a location fix, try again")
                val place = Place(UUID.randomUUID().toString(), placeName, loc.latitude, loc.longitude, meters, emptySet())
                Gate.savePlaces(this, Gate.places(this) + place)
                Geofences.registerAll(this)
                render()
                editBlocked(place)
            }
    }

    private fun editBlocked(place: Place) {
        val checked = BooleanArray(apps.size) { apps[it].second in place.blocked }
        AlertDialog.Builder(this)
            .setTitle("Block at ${place.name}")
            .setMultiChoiceItems(apps.map { it.first }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                val blocked = apps.indices.filter { checked[it] }.map { apps[it].second }.toSet()
                Gate.savePlaces(this, Gate.places(this).map { if (it.id == place.id) it.copy(blocked = blocked) else it })
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(place: Place) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${place.name}?")
            .setMessage("Its blocked apps will work there again.")
            .setPositiveButton("Delete") { _, _ ->
                Gate.savePlaces(this, Gate.places(this).filter { it.id != place.id })
                Geofences.registerAll(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
