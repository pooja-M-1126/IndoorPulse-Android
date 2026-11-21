package com.indoorpulse.data.sensor

import kotlin.math.abs
import kotlin.math.atan2

internal fun bearingDegrees(east: Float, north: Float): Float =
    (Math.toDegrees(atan2(east, north).toDouble()).toFloat() + 360f) % 360f

// Android's matrix columns are device right, top and screen-out in world ENU.
// Near upright the top edge has little horizontal projection. Blend toward
// the rear camera axis as the top edge rises, with no discrete posture switch.
internal fun portraitHeading(matrix: FloatArray): Float? {
    val tilt = matrix[7]
    val blend = ((abs(tilt) - 0.5f) / 0.4f).coerceIn(0f, 1f)
    val cameraWeight = if (tilt >= 0f) -blend else blend
    val east = (1f - blend) * matrix[1] + cameraWeight * matrix[2]
    val north = (1f - blend) * matrix[4] + cameraWeight * matrix[5]
    return if (east * east + north * north > 0.01f) bearingDegrees(east, north) else null
}
