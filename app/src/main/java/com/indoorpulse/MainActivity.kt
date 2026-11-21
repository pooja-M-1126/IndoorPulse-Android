package com.indoorpulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.indoorpulse.data.local.IndoorPulseDatabase
import com.indoorpulse.data.sensor.SensorDataSourceImpl
import com.indoorpulse.data.sensor.isUsableForDiscovery
import com.indoorpulse.domain.usecase.FindShortestPathUseCase
import com.indoorpulse.presentation.ui.MainScreen
import com.indoorpulse.presentation.viewmodel.MapViewModel
import com.indoorpulse.presentation.viewmodel.TrackingState
import com.indoorpulse.ui.theme.Appearance
import com.indoorpulse.ui.theme.IndoorPulseTheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    private var locationAllowed by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        locationAllowed = hasLocationPermission()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun findLocation(client: FusedLocationProviderClient): Location? {
        if (!hasLocationPermission()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(10_000)
            .setDurationMillis(10_000)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                client.getCurrentLocation(request, cancellation.token).addOnCompleteListener { task ->
                    val fix = if (task.isSuccessful) task.result?.takeIf { it.isUsableForDiscovery() } else null
                    continuation.resume(fix)
                }
            } catch (_: SecurityException) {
                continuation.resume(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var appearance by remember { mutableStateOf(Appearance.fromPreference(preferences.getString("mode", null))) }
            val dark = when (appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            IndoorPulseTheme(darkTheme = dark) {
                var refresh by remember { mutableIntStateOf(0) }
                var location by remember { mutableStateOf<Location?>(null) }
                var locationStatus by remember { mutableStateOf<String?>(null) }
                val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(this@MainActivity) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    locationAllowed = hasLocationPermission()
                    if (!locationAllowed) locationStatus = "Location permission is off. You can still choose a map."
                }

                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        val sensorDataSource = SensorDataSourceImpl(applicationContext, fusedLocationClient)
                        val database = IndoorPulseDatabase.getDatabase(applicationContext)
                        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
                        return MapViewModel(
                            sensorManager,
                            sensorDataSource,
                            FindShortestPathUseCase(),
                            database,
                            includeDemo = true
                        ) as T
                    }
                }

                val viewModel: MapViewModel = viewModel(factory = factory)
                val trackingState by viewModel.trackingState.collectAsState()
                val showingExitRoute by viewModel.isSosActive.collectAsState()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark && !showingExitRoute
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(foreground, locationAllowed, trackingState, refresh) {
                    if (!locationAllowed) {
                        location = null
                        viewModel.updateDiscoveryLocation(null)
                    } else if (foreground && trackingState == TrackingState.OUTDOORS) {
                        locationStatus = "Looking for nearby maps…"
                        val fix = findLocation(fusedLocationClient)
                        location = fix
                        viewModel.updateDiscoveryLocation(fix)
                        locationStatus = if (fix == null) "Location unavailable. Choose a map below or try again." else null
                        if (fix != null) {
                            delay((120_000L - (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0L))
                            location = null
                            viewModel.updateDiscoveryLocation(null)
                        }
                    }
                }
                MainScreen(viewModel, location, locationStatus, onFindNearby = {
                    if (locationAllowed) refresh++ else {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                }, appearance = appearance, onAppearanceChange = { selected ->
                    preferences.edit().putString("mode", selected.name).apply()
                    appearance = selected
                })
            }
        }
    }
}
