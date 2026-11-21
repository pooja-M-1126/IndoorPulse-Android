package com.indoorpulse.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indoorpulse.data.local.entity.NodeEntity
import com.indoorpulse.data.local.entity.label
import com.indoorpulse.data.local.entity.type

@Composable
fun SearchBottomSheetContent(
    allNodes: List<NodeEntity>,
    onPreview: ((NodeEntity) -> Unit)? = null,
    onNodeSelected: (NodeEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // CRITICAL FIX: Strip out Waypoints and filter by search query
    val query = searchQuery.trim()
    val filteredNodes = remember(allNodes, query) {
        allNodes.filter { node ->
            node.type != "Waypoint" && listOf(node.label, node.type, "L${node.floorLevel}").any { it.contains(query, ignoreCase = true) }
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.floorLevel }))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .imePadding(), // Pushes up gracefully with the keyboard
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // We REMOVED the custom Box() drag handle because BottomSheetScaffold provides one natively.
        
        Text("Find a destination", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            label = { Text("Room, place or floor") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (filteredNodes.isEmpty()) {
            Text(
                if (query.isEmpty()) "Add a named destination in survey mode to route here." else "No destinations match “$query”.",
                modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false) // Expands to fit content, up to the screen height
        ) {
            items(filteredNodes, key = { it.nodeId }) { node ->
                ListItem(
                    headlineContent = { Text(node.label, fontWeight = FontWeight.Bold) },
                    // CRITICAL FIX: Show actual type instead of fallback text
                    supportingContent = { Text("${node.type} · L${node.floorLevel}") },
                    leadingContent = { 
                        Icon(
                            imageVector = if (node.type == "Emergency Exit") Icons.Default.Warning else Icons.Default.LocationOn, 
                            contentDescription = null,
                            tint = if (node.type == "Emergency Exit") Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
                    trailingContent = onPreview?.let { preview ->
                        {
                            TextButton(
                                onClick = { preview(node) },
                                modifier = Modifier.semantics { contentDescription = "Preview route to ${node.label}, L${node.floorLevel}" }
                            ) {
                                Icon(Icons.Default.Route, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Preview")
                            }
                        }
                    },
                    modifier = Modifier.clickable { onNodeSelected(node) }
                )
                HorizontalDivider()
            }
        }
    }
}
