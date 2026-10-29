package com.shuaib.classmate.transit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import kotlin.math.*

/**
 * Data model for MBSTU fleet vehicles.
 */
data class MbstuBus(
    val id: String,
    val nameBn: String,
    val nameEn: String,
    var lat: Double,
    var lon: Double,
    var speed: Int,
    var heading: Float,
    val routeName: String,
    val driverName: String,
    val driverPhone: String,
    val capacity: String = "42 Seater",
    var isDriving: Boolean = false,
    var nextStop: String = "Baby Stand",
    var etaMinutes: Int = 4,
    val color: Color
)

/**
 * Real MBSTU Bus Fleet definitions.
 */
val MBSTU_FLEET_SEED = listOf(
    MbstuBus("SB0001", "গীতাঞ্জলি", "Gitanjali", 24.234833, 89.892358, 0, 171f, "MBSTU Standby", "MD. Mizanur Rahman", "01711234567", color = Color(0xFF6366F1)),
    MbstuBus("SB0002", "অগ্নিবীণা", "Agnibeena", 24.237800, 89.894200, 28, 175f, "Campus ➔ Nirala (Tangail)", "MD. Rafiqul Islam", "01712345678", isDriving = true, nextStop = "Baby Stand", etaMinutes = 3, color = Color(0xFF0284C7)),
    MbstuBus("SB0003", "সোনারতরী", "Sonartori", 24.244000, 89.907000, 34, 355f, "Nirala ➔ Campus", "MD. Kamal Hossain", "01713456789", isDriving = true, nextStop = "Campus Gate", etaMinutes = 5, color = Color(0xFF0D9488)),
    MbstuBus("SB0004", "ধলেশ্বরী", "Dholeshwari", 24.235222, 89.890800, 0, 101f, "MBSTU Standby", "MD. Abdul Alim", "01714567890", color = Color(0xFFD97706)),
    MbstuBus("SB0005", "যমুনা", "Jamuna", 24.235100, 89.891200, 0, 180f, "Campus ➔ New Bus Stand", "MD. Jahangir Alam", "01715678901", color = Color(0xFFEA580C)),
    MbstuBus("SB0006", "মেঘনা", "Meghna", 24.234900, 89.891900, 0, 0f, "MBSTU Standby", "MD. Sirajul Islam", "01716789012", color = Color(0xFF2563EB)),
    MbstuBus("SB0007", "সুরমা", "Surma", 24.235300, 89.892100, 0, 90f, "Campus ➔ Railway Station", "MD. Nazrul Islam", "01717890123", color = Color(0xFF7C3AED)),
    MbstuBus("SB0008", "কর্ণফুলী", "Karnafuli", 24.235400, 89.891500, 0, 180f, "MBSTU Standby", "MD. Faruk Ahmed", "01718901234", color = Color(0xFFDB2777)),
    MbstuBus("SB0009", "বনলতা", "Banalata", 24.235050, 89.891750, 0, 270f, "Campus ➔ Nirala", "MD. Habibur Rahman", "01719012345", color = Color(0xFF059669)),
    MbstuBus("SB0010", "রক্তকরবী", "Raktakarabi", 24.235150, 89.891650, 0, 0f, "MBSTU Standby", "MD. Anisur Rahman", "01720123456", color = Color(0xFFE11D48))
)

class ClassMateTransitScreen(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope
) {
    private var cachedRoot: ComposeView? = null

    fun render(host: LinearLayout) {
        cachedRoot?.let { root ->
            (root.parent as? ViewGroup)?.removeView(root)
            host.addView(root, LinearLayout.LayoutParams(-1, -1))
            return
        }

        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ClassMateTransitScreenContent(activity = activity)
            }
        }

        cachedRoot = composeView
        host.addView(composeView, LinearLayout.LayoutParams(-1, -1))
    }
}

@Composable
fun ClassMateTransitScreenContent(activity: AppCompatActivity) {
    val context = LocalContext.current
    var buses by remember { mutableStateOf(MBSTU_FLEET_SEED) }
    var selectedBusId by remember { mutableStateOf("SB0002") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Moving", "Standby"
    var mapZoom by remember { mutableStateOf(1f) }
    var mapOffset by remember { mutableStateOf(Offset.Zero) }
    var sheetTier by remember { mutableStateOf(1) } // 0: Peek (120dp), 1: Half (280dp), 2: Full (540dp)
    var isLiveSocketConnected by remember { mutableStateOf(false) }

    val selectedBus = buses.firstOrNull { it.id == selectedBusId } ?: buses.first()

    // OkHttp WebSocket live listener
    DisposableEffect(Unit) {
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url("wss://transport.mbstu.ac.bd/socket.io/?EIO=4&transport=websocket")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("40")
                isLiveSocketConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "2") {
                    webSocket.send("3")
                    return
                }
                if (text.startsWith("42[\"flutter-bus-data\"")) {
                    try {
                        val payload = text.removePrefix("42").trim()
                        val array = org.json.JSONArray(payload)
                        val data = array.optJSONObject(1)
                        if (data != null) {
                            val id = data.optString("id")
                            val lat = data.optDouble("lat", 0.0)
                            val lon = data.optDouble("lon", 0.0)
                            val speed = data.optInt("speed", 0)
                            val heading = data.optDouble("heading", 0.0).toFloat()
                            val isDriving = data.optBoolean("isDriving", speed > 5)

                            if (id.isNotBlank() && lat > 0) {
                                buses = buses.map { b ->
                                    if (b.id == id) {
                                        b.copy(
                                            lat = lat,
                                            lon = lon,
                                            speed = speed,
                                            heading = heading,
                                            isDriving = isDriving
                                        )
                                    } else b
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isLiveSocketConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isLiveSocketConnected = false
            }
        })

        onDispose {
            ws.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }

    // Filter buses
    val filteredBuses = remember(buses, searchQuery, selectedFilter) {
        buses.filter { bus ->
            val matchesSearch = searchQuery.isBlank() ||
                    bus.nameBn.contains(searchQuery, ignoreCase = true) ||
                    bus.nameEn.contains(searchQuery, ignoreCase = true) ||
                    bus.id.contains(searchQuery, ignoreCase = true) ||
                    bus.routeName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "Moving" -> bus.isDriving || bus.speed > 0
                "Standby" -> bus.speed == 0 && !bus.isDriving
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {

        // ================= 1. NATIVE INTERACTIVE VECTOR MAP =================
        InteractiveVectorMap(
            modifier = Modifier.fillMaxSize(),
            buses = filteredBuses,
            selectedBusId = selectedBusId,
            zoom = mapZoom,
            offset = mapOffset,
            onTransform = { panChange, zoomChange ->
                mapOffset += panChange
                mapZoom = (mapZoom * zoomChange).coerceIn(0.6f, 3.5f)
            },
            onBusClick = { busId ->
                selectedBusId = busId
                vibrateTick(context)
            }
        )

        // ================= 2. TOP FLOATING GOOGLE MAPS SEARCH & FILTERS =================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Glassmorphic Search Capsule
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xE60F172A),
                border = BorderStroke(1.dp, Color(0x6638BDF8)),
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(20.dp)
                    )

                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Search bus, route, or stoppage...",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    // Target / Recenter Button
                    IconButton(
                        onClick = {
                            mapZoom = 1f
                            mapOffset = Offset.Zero
                            vibrateTick(context)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Center Campus",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Quick Route & Status Filter Capsules
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterCapsule(
                    label = "All Fleet (${buses.size})",
                    selected = selectedFilter == "All",
                    onClick = { selectedFilter = "All" }
                )
                FilterCapsule(
                    label = "⚡ On Road (${buses.count { it.isDriving || it.speed > 0 }})",
                    selected = selectedFilter == "Moving",
                    activeColor = Color(0xFF10B981),
                    onClick = { selectedFilter = "Moving" }
                )
                FilterCapsule(
                    label = "🏫 At Standby (${buses.count { !it.isDriving && it.speed == 0 }})",
                    selected = selectedFilter == "Standby",
                    activeColor = Color(0xFF64748B),
                    onClick = { selectedFilter = "Standby" }
                )
            }
        }

        // Live Socket Pill (Top Right)
        Surface(
            shape = RoundedCornerShape(9999.dp),
            color = Color(0xE60F172A),
            border = BorderStroke(1.dp, if (isLiveSocketConnected) Color(0xFF10B981) else Color(0xFFF59E0B)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 96.dp, end = 14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isLiveSocketConnected) Color(0xFF10B981) else Color(0xFFF59E0B), CircleShape)
                )
                Text(
                    text = if (isLiveSocketConnected) "MBSTU LIVE" else "RADAR READY",
                    color = if (isLiveSocketConnected) Color(0xFF34D399) else Color(0xFFFBBF24),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ================= 3. NATIVE GOOGLE MAPS 3-TIER EXPANDABLE BOTTOM SHEET =================
        val sheetHeight by animateDpAsState(
            targetValue = when (sheetTier) {
                0 -> 140.dp
                1 -> 310.dp
                else -> 560.dp
            },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
            label = "SheetHeight"
        )

        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color(0xF20F172A),
            border = BorderStroke(1.dp, Color(0x66334155)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(sheetHeight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 76.dp) // bottom nav bar clearance
            ) {
                // Drag handle bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            sheetTier = (sheetTier + 1) % 3
                            vibrateTick(context)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(4.5.dp)
                            .background(Color(0xFF64748B), RoundedCornerShape(9999.dp))
                    )
                }

                // Tier 1: Glanceable Header (Always Visible)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = selectedBus.color.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, selectedBus.color.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = selectedBus.id,
                                    color = selectedBus.color,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                )
                            }
                            Text(
                                text = "${selectedBus.nameBn} (${selectedBus.nameEn})",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        Text(
                            text = selectedBus.routeName,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${selectedBus.speed} km/h",
                            color = if (selectedBus.speed > 0) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = if (selectedBus.speed > 0) "ETA: ${selectedBus.etaMinutes}m" else "Standby",
                            color = if (selectedBus.speed > 0) Color(0xFF10B981) else Color(0xFF64748B),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Tier 2 & 3: Stoppage Timeline, Driver Call & Fleet Overview
                if (sheetTier >= 1) {
                    Divider(color = Color(0x33334155), modifier = Modifier.padding(vertical = 8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Stoppage Headway Timeline
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0x801E293B),
                            border = BorderStroke(1.dp, Color(0x4D334155))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "ROUTE HEADWAY TIMELINE",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Next: ${selectedBus.nextStop}",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Progress Line & Nodes
                                StoppageProgressRow(selectedBus = selectedBus)
                            }
                        }

                        // Driver Profile Card with Direct Call Button
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0x801E293B),
                            border = BorderStroke(1.dp, Color(0x4D334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(Color(0xFF334155), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = "👨‍✈️", fontSize = 18.sp)
                                    }
                                    Column {
                                        Text(
                                            text = selectedBus.driverName,
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "MBSTU Transport • ${selectedBus.capacity}",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_DIAL).apply {
                                            data = Uri.parse("tel:${selectedBus.driverPhone}")
                                        }
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x3310B981)),
                                    border = BorderStroke(1.dp, Color(0x6610B981)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = "Call",
                                        tint = Color(0xFF34D399),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Call", color = Color(0xFF34D399), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Campus Departure Alert Switch
                        var alertEnabled by remember {
                            mutableStateOf(ClassMateTransitManager.isAlertEnabled(context, selectedBus.id))
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0x661E293B),
                            border = BorderStroke(1.dp, Color(0x40334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = "Alert",
                                        tint = Color(0xFFF59E0B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Campus Departure Alert",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Notify when bus leaves Santosh campus",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Switch(
                                    checked = alertEnabled,
                                    onCheckedChange = {
                                        alertEnabled = it
                                        ClassMateTransitManager.setAlertEnabled(context, selectedBus.id, it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF0284C7)
                                    )
                                )
                            }
                        }

                        // Fleet Horizontal Switcher Strip
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                text = "ALL MBSTU BUSES",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(buses) { bus ->
                                    val isCurrent = bus.id == selectedBusId
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isCurrent) bus.color.copy(alpha = 0.25f) else Color(0x661E293B),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isCurrent) bus.color else Color(0x40334155)
                                        ),
                                        modifier = Modifier.clickable {
                                            selectedBusId = bus.id
                                            vibrateTick(context)
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = bus.nameBn,
                                                color = if (isCurrent) Color.White else Color(0xFFCBD5E1),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = if (bus.speed > 0) "${bus.speed} km/h" else "Standby",
                                                color = if (bus.speed > 0) Color(0xFF34D399) else Color(0xFF64748B),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterCapsule(
    label: String,
    selected: Boolean,
    activeColor: Color = Color(0xFF0284C7),
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9999.dp),
        color = if (selected) activeColor else Color(0xD90F172A),
        border = BorderStroke(1.dp, if (selected) activeColor else Color(0x66334155)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFCBD5E1),
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun StoppageProgressRow(selectedBus: MbstuBus) {
    val stops = listOf("MBSTU", "Gate", "Baby Std", "Old Std", "Nirala")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEachIndexed { index, stopName ->
            val isPassed = index == 0 || (selectedBus.speed > 0 && index == 1)
            val isCurrent = selectedBus.speed > 0 && index == 2
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            when {
                                isPassed -> Color(0xFF10B981)
                                isCurrent -> Color(0xFF38BDF8)
                                else -> Color(0xFF334155)
                            },
                            CircleShape
                        )
                )
                Text(
                    text = stopName,
                    color = if (isCurrent) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/**
 * Native Vector Map Canvas with Hardware Acceleration, Roads, Geofence, and Directional Vehicle Pucks.
 */
@Composable
fun InteractiveVectorMap(
    modifier: Modifier = Modifier,
    buses: List<MbstuBus>,
    selectedBusId: String,
    zoom: Float,
    offset: Offset,
    onTransform: (Offset, Float) -> Unit,
    onBusClick: (String) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAlpha"
    )

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    onTransform(pan, gestureZoom)
                }
            }
    ) {
        val w = size.width
        val h = size.height

        // Dark map canvas base
        drawRect(Color(0xFF0F172A))

        // Center map projection around MBSTU (24.2356 N, 89.8918 E)
        val center = Offset(w * 0.5f, h * 0.42f) + offset

        // 1. Campus Grounds (Lush Green Zone)
        val campusPath = Path().apply {
            moveTo(center.x - 180f * zoom, center.y - 120f * zoom)
            lineTo(center.x + 120f * zoom, center.y - 100f * zoom)
            lineTo(center.x + 140f * zoom, center.y + 160f * zoom)
            lineTo(center.x - 160f * zoom, center.y + 180f * zoom)
            close()
        }
        drawPath(campusPath, Color(0xFF142820))
        drawPath(campusPath, Color(0xFF1A382C), style = Stroke(width = 1.5f * zoom))

        // 2. Santosh Lake
        val lakePath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    center.x + 40f * zoom,
                    center.y - 40f * zoom,
                    center.x + 110f * zoom,
                    center.y + 30f * zoom
                )
            )
        }
        drawPath(lakePath, Color(0xFF0F2D4A))
        drawPath(lakePath, Color(0xFF1E4E7A), style = Stroke(width = 1.5f * zoom))

        // 3. Grid Streets
        for (i in -4..4) {
            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(center.x - 500f * zoom, center.y + i * 90f * zoom),
                end = Offset(center.x + 500f * zoom, center.y + i * 90f * zoom),
                strokeWidth = 3f * zoom
            )
            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(center.x + i * 90f * zoom, center.y - 500f * zoom),
                end = Offset(center.x + i * 90f * zoom, center.y + 500f * zoom),
                strokeWidth = 3f * zoom
            )
        }

        // 4. Main Arterial Highway: MBSTU Stand ➔ Gate ➔ Baby Stand ➔ Old Stand ➔ Nirala
        val routePath = Path().apply {
            moveTo(center.x - 10f * zoom, center.y - 60f * zoom) // Stand
            quadraticBezierTo(
                center.x + 20f * zoom, center.y + 50f * zoom,
                center.x + 50f * zoom, center.y + 140f * zoom // Gate
            )
            quadraticBezierTo(
                center.x + 80f * zoom, center.y + 240f * zoom,
                center.x + 110f * zoom, center.y + 350f * zoom // Baby Stand
            )
            lineTo(center.x + 140f * zoom, center.y + 500f * zoom) // Nirala
        }

        // Highway Glow Casing
        drawPath(
            routePath,
            color = Color(0x330284C7),
            style = Stroke(width = 14f * zoom, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Electric Transit Line
        drawPath(
            routePath,
            color = Color(0xFF38BDF8),
            style = Stroke(width = 4.5f * zoom, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 5. Campus 300m Geofence
        drawCircle(
            color = Color(0x1A10B981),
            radius = 110f * zoom,
            center = Offset(center.x - 10f * zoom, center.y - 60f * zoom)
        )
        drawCircle(
            color = Color(0xFF10B981),
            radius = 110f * zoom,
            center = Offset(center.x - 10f * zoom, center.y - 60f * zoom),
            style = Stroke(width = 1.5f * zoom, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        )

        // 6. Student Location Blue Puck with Accuracy Aura
        val userPos = Offset(center.x + 48f * zoom, center.y + 135f * zoom)
        drawCircle(
            color = Color(0x333B82F6),
            radius = 24f * zoom * pulseScale,
            center = userPos,
            alpha = pulseAlpha
        )
        drawCircle(
            color = Color(0xFF3B82F6),
            radius = 8f * zoom,
            center = userPos
        )
        drawCircle(
            color = Color.White,
            radius = 8f * zoom,
            center = userPos,
            style = Stroke(width = 2.5f)
        )

        // 7. Directional Vehicle Pucks
        buses.forEach { bus ->
            // Map GPS coords to canvas space relative to MBSTU stand
            val latDiff = (bus.lat - 24.2350) * 11000f
            val lonDiff = (bus.lon - 89.8916) * 11000f
            val busPos = Offset(
                center.x + lonDiff.toFloat() * zoom,
                center.y - latDiff.toFloat() * zoom
            )

            val isSelected = bus.id == selectedBusId

            // Heading Cone Beam
            if (bus.speed > 0) {
                rotate(bus.heading, pivot = busPos) {
                    val conePath = Path().apply {
                        moveTo(busPos.x, busPos.y)
                        lineTo(busPos.x - 22f * zoom, busPos.y - 48f * zoom)
                        lineTo(busPos.x + 22f * zoom, busPos.y - 48f * zoom)
                        close()
                    }
                    drawPath(
                        conePath,
                        brush = Brush.radialGradient(
                            colors = listOf(bus.color.copy(alpha = 0.5f), Color.Transparent),
                            center = busPos,
                            radius = 50f * zoom
                        )
                    )
                }
            }

            // Selection Glow
            if (isSelected) {
                drawCircle(
                    color = bus.color.copy(alpha = 0.35f),
                    radius = 26f * zoom,
                    center = busPos
                )
            }

            // Circular Vehicle Puck
            drawCircle(
                color = bus.color,
                radius = 16f * zoom,
                center = busPos
            )
            drawCircle(
                color = Color.White,
                radius = 16f * zoom,
                center = busPos,
                style = Stroke(width = 2.5f * zoom)
            )

            // Bus Icon in center
            drawCircle(
                color = Color.White,
                radius = 5f * zoom,
                center = busPos
            )
        }
    }
}

private fun vibrateTick(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(12)
            }
        }
    } catch (_: Exception) {}
}
