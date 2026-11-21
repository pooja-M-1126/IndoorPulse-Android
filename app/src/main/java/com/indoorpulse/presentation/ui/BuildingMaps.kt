package com.indoorpulse.presentation.ui

import android.location.Location
import com.indoorpulse.data.local.DEMO_MAP_ID
import com.indoorpulse.data.sensor.isUsableForDiscovery
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.indoorpulse.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.indoorpulse.data.local.dao.BuildingMapSummary
import com.indoorpulse.data.local.entity.BuildingEntity
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.label

@Composable
internal fun BuildingLibrary(
    maps: List<BuildingMapSummary>,
    onOpen: (BuildingEntity) -> Unit,
    onCreate: () -> Unit,
    location: Location? = null,
    locationStatus: String? = null,
    onFindNearby: (() -> Unit)? = null
) {
    val nearby = remember(maps, location) { nearbyMaps(maps, location) }
    val demo = maps.firstOrNull { it.building.buildingId == DEMO_MAP_ID }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Building maps", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (onFindNearby != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby mapped buildings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onFindNearby) { Text("Use my location") }
                }
                if (nearby.isEmpty()) Text(
                    locationStatus ?: if (location == null) "Choose a map below or use your location."
                        else "No nearby maps could be matched. Choose a map below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(nearby, key = { "nearby_${it.building.buildingId}" }) { map ->
            BuildingCard(map.building.name, map.coverageLabel(), BuildingCardStyle.NEARBY) { onOpen(map.building) }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("All maps", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Create map")
                }
            }
            if (demo != null) BuildingCard("Try demo map", "Demo Learning Centre · 2 floors", BuildingCardStyle.DEMO) { onOpen(demo.building) }
        }
        items(maps.filter { it.building.buildingId != DEMO_MAP_ID }, key = { it.building.buildingId }) { map ->
            BuildingCard(map.building.name, map.coverageLabel()) { onOpen(map.building) }
        }
    }
}

private fun BuildingMapSummary.coverageLabel(): String {
    if (pointCount == 0) return "Ready for its first survey"
    val floors = if (floorCount == 1) "floor" else "floors"
    val points = if (pointCount == 1) "point" else "points"
    return "$floorCount $floors · $pointCount mapped $points"
}

private enum class BuildingCardStyle { SAVED, NEARBY, DEMO }

@Composable
private fun BuildingCard(
    title: String,
    subtitle: String,
    style: BuildingCardStyle = BuildingCardStyle.SAVED,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground, detail) = when (style) {
        BuildingCardStyle.DEMO -> Triple(Color(0xFF104D42), Color(0xFFF2FFF9), Color(0xFFB4DFCF))
        BuildingCardStyle.NEARBY -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.onPrimaryContainer)
        BuildingCardStyle.SAVED -> Triple(colors.surfaceContainerLow, colors.onSurface, colors.onSurfaceVariant)
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = background,
        contentColor = foreground,
        border = if (style == BuildingCardStyle.SAVED) androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant) else null
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = if (style == BuildingCardStyle.DEMO) Color(0xFF183735) else colors.surface,
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (style == BuildingCardStyle.DEMO) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(42.dp))
                    } else {
                        Icon(
                            if (style == BuildingCardStyle.NEARBY) Icons.Default.NearMe else Icons.Default.LocationCity,
                            null, Modifier.size(22.dp), tint = colors.primary
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = detail)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

internal fun nearbyMaps(maps: List<BuildingMapSummary>, location: Location?): List<BuildingMapSummary> {
    val fix = location?.takeIf { it.isUsableForDiscovery() } ?: return emptyList()
    val distance = FloatArray(1)
    return maps.mapNotNull { map ->
        val building = map.building
        if (map.pointCount == 0 || building.buildingId == DEMO_MAP_ID ||
            (building.centerLat == 0.0 && building.centerLng == 0.0) ||
            building.centerLat !in -90.0..90.0 || building.centerLng !in -180.0..180.0 ||
            !building.geofenceRadius.isFinite() || building.geofenceRadius < fix.accuracy
        ) return@mapNotNull null
        Location.distanceBetween(fix.latitude, fix.longitude, building.centerLat, building.centerLng, distance)
        if (distance[0] <= building.geofenceRadius) map to distance[0] else null
    }.sortedBy { it.second }.map { it.first }
}

@Composable
internal fun CreateMapDialog(onCreate: (String, Float) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf("100") }
    val metres = radius.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create building map") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Building name") }, singleLine = true)
                OutlinedTextField(radius, { radius = it }, label = { Text("Detection radius (m)") },
                    singleLine = true, isError = metres == null,
                    supportingText = { if (metres == null) Text("Enter a distance greater than zero.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            Button(onClick = { metres?.let { onCreate(name.trim(), it) } }, enabled = name.isNotBlank() && metres != null) {
                Text("Start survey")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun BuildingEntryDialog(
    building: BuildingEntity,
    loadEntrances: suspend (String) -> List<NodeEntity>,
    onEnter: (NodeEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val entrances by produceState<List<NodeEntity>?>(null, building.buildingId) {
        value = loadEntrances(building.buildingId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(building.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val entries = entrances
                when {
                    entries == null -> CircularProgressIndicator()
                    entries.isEmpty() -> Text("Start at the map origin on floor L0. You can set your position at a mapped point in survey mode.")
                    else -> {
                        Text("Choose the entrance where you are standing.")
                        entries.forEach { node ->
                            OutlinedButton(onClick = { onEnter(node) }, modifier = Modifier.fillMaxWidth()) {
                                Text(node.label, Modifier.weight(1f))
                                Text("L${node.floorLevel}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (entrances?.isEmpty() == true) Button(onClick = {
                onEnter(NodeEntity("entry_${building.buildingId}", "f0", 0f, 0f, "Building Entry", "Map origin", buildingId = building.buildingId))
            }) { Text("Enter at map origin") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
