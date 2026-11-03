package com.kaden.focusgate

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers every saved Place as a geofence with Google Play Services. */
object Geofences {
    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        // geofencing fills in the intent, so it must be mutable
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun hasForeground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    @SuppressLint("MissingPermission")
    fun registerAll(ctx: Context) {
        if (!hasForeground(ctx) || !hasBackground(ctx)) return
        val client = LocationServices.getGeofencingClient(ctx)
        val places = Gate.places(ctx)

        client.removeGeofences(pendingIntent(ctx)).addOnCompleteListener {
            if (places.isEmpty()) return@addOnCompleteListener
            val fences = places.map {
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lng, it.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            // initial triggers report whether we're already inside/outside each place
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofences(fences)
                .build()
            client.addGeofences(request, pendingIntent(ctx))
                .addOnFailureListener { Log.w("FocusGate", "geofence registration failed", it) }
        }
    }
}
