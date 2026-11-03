package com.kaden.focusgate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("FocusGate", "geofence error ${event.errorCode}")
            return
        }
        val inside = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        event.triggeringGeofences?.forEach {
            Log.i("FocusGate", "${if (inside) "entered" else "left"} ${it.requestId}")
            Gate.setInside(ctx, it.requestId, inside)
        }
    }
}
