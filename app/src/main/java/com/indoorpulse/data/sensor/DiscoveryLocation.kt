package com.indoorpulse.data.sensor

import android.location.Location
import android.os.SystemClock

internal fun Location.isUsableForDiscovery(): Boolean {
    val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
    return ageNanos in 0L..120_000_000_000L && hasAccuracy() && accuracy.isFinite() && accuracy > 0f
}
