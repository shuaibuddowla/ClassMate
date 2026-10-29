package com.shuaib.classmate.transit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import okhttp3.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
    val color: Color,
    val hexColor: Int
)

val MBSTU_FLEET_SEED = listOf(
    MbstuBus("SB0001", "গীতাঞ্জলি", "Gitanjali", 24.234833, 89.892358, 0, 171f, "MBSTU Standby", "MD. Mizanur Rahman", "01711234567", color = Color(0xFF6366F1), hexColor = 0xFF6366F1.toInt()),
    MbstuBus("SB0002", "অগ্নিবীণা", "Agnibeena", 24.237800, 89.894200, 28, 175f, "Campus ➔ Nirala (Tangail)", "MD. Rafiqul Islam", "01712345678", isDriving = true, nextStop = "Baby Stand", etaMinutes = 3, color = Color(0xFF0284C7), hexColor = 0xFF0284C7.toInt()),
    MbstuBus("SB0003", "সোনারতরী", "Sonartori", 24.244000, 89.907000, 34, 355f, "Nirala ➔ Campus", "MD. Kamal Hossain", "01713456789", isDriving = true, nextStop = "Campus Gate", etaMinutes = 5, color = Color(0xFF0D9488), hexColor = 0xFF0D9488.toInt()),
    MbstuBus("SB0004", "ধলেশ্বরী", "Dholeshwari", 24.235222, 89.890800, 0, 101f, "MBSTU Standby", "MD. Abdul Alim", "01714567890", color = Color(0xFFD97706), hexColor = 0xFFD97706.toInt()),
    MbstuBus("SB0005", "যমুনা", "Jamuna", 24.235100, 89.891200, 0, 180f, "Campus ➔ New Bus Stand", "MD. Jahangir Alam", "01715678901", color = Color(0xFFEA580C), hexColor = 0xFFEA580C.toInt()),
    MbstuBus("SB0006", "মেঘনা", "Meghna", 24.234900, 89.891900, 0, 0f, "MBSTU Standby", "MD. Sirajul Islam", "01716789012", color = Color(0xFF2563EB), hexColor = 0xFF2563EB.toInt()),
    MbstuBus("SB0007", "সুরমা", "Surma", 24.235300, 89.892100, 0, 90f, "Campus ➔ Railway Station", "MD. Nazrul Islam", "01717890123", color = Color(0xFF7C3AED), hexColor = 0xFF7C3AED.toInt()),
    MbstuBus("SB0008", "কর্ণফুলী", "Karnafuli", 24.235400, 89.891500, 0, 180f, "MBSTU Standby", "MD. Faruk Ahmed", "01718901234", color = Color(0xFFDB2777), hexColor = 0xFFDB2777.toInt()),
    MbstuBus("SB0009", "বনলতা", "Banalata", 24.235050, 89.891750, 0, 270f, "Campus ➔ Nirala", "MD. Habibur Rahman", "01719012345", color = Color(0xFF059669), hexColor = 0xFF059669.toInt()),
    MbstuBus("SB0010", "রক্তকরবী", "Raktakarabi", 24.235150, 89.891650, 0, 0f, "MBSTU Standby", "MD. Anisur Rahman", "01720123456", color = Color(0xFFE11D48), hexColor = 0xFFE11D48.toInt())
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
    var selectedFilter by remember { mutableStateOf("All") }
    var sheetTier by remember { mutableStateOf(1) } // 0: Peek (140dp), 1: Mid (320dp), 2: Full (560dp)
    var isLiveSocketConnected by remember { mutableStateOf(false) }

    var osmMapView by remember { mutableStateOf<MapView?>(null) }

    val selectedBus = buses.firstOrNull { it.id == selectedBusId } ?: buses.first()

    // OkHttp WebSocket live listener for real MBSTU telemetries
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

        // ================= 1. REAL OPENSTREETMAP NATIVE MAPVIEW =================
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                Configuration.getInstance().userAgentValue = ctx.packageName

                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    controller.setZoom(15.5)
                    controller.setCenter(GeoPoint(24.2356, 89.8918)) // MBSTU Campus

                    // Draw Route Polyline from MBSTU Stand to Nirala
                    val routePolyline = Polyline(this).apply {
                        outlinePaint.color = android.graphics.Color.parseColor("#0284C7")
                        outlinePaint.strokeWidth = 12f
                        addPoint(GeoPoint(24.2349, 89.8916)) // MBSTU Stand
                        addPoint(GeoPoint(24.2378, 89.8942)) // Santosh Gate / Bazaar
                        addPoint(GeoPoint(24.2405, 89.9002)) // Baby Stand
                        addPoint(GeoPoint(24.2440, 89.9070)) // Old Bus Stand
                        addPoint(GeoPoint(24.2498, 89.9165)) // Nirala (Tangail)
                    }
                    overlayManager.add(routePolyline)

                    osmMapView = this
                }
            },
            update = { map ->
                // Clear previous vehicle markers, keep the polyline at index 0
                while (map.overlays.size > 1) {
                    map.overlays.removeAt(1)
                }

                // Add real bus markers
                filteredBuses.forEach { bus ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(bus.lat, bus.lon)
                        title = "${bus.nameBn} (${bus.nameEn})"
                        snippet = "${bus.routeName} • ${bus.speed} km/h"
                        icon = createBusMarkerDrawable(context, bus, bus.id == selectedBusId)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ ->
                            selectedBusId = bus.id
                            map.controller.animateTo(position)
                            vibrateTick(context)
                            true
                        }
                    }
                    map.overlays.add(marker)
                }

                map.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // ================= 2. TOP FLOATING GOOGLE MAPS SEARCH & FILTERS =================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF20F172A),
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

                    IconButton(
                        onClick = {
                            osmMapView?.controller?.animateTo(GeoPoint(24.2356, 89.8918), 16.0, 1000L)
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
            color = Color(0xF20F172A),
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

                if (sheetTier >= 1) {
                    Divider(color = Color(0x33334155), modifier = Modifier.padding(vertical = 8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                                StoppageProgressRow(selectedBus = selectedBus)
                            }
                        }

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
                                            osmMapView?.controller?.animateTo(GeoPoint(bus.lat, bus.lon), 16.0, 800L)
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

/**
 * Creates custom Google Maps style directional vehicle puck drawable for OpenStreetMap.
 */
private fun createBusMarkerDrawable(context: Context, bus: MbstuBus, isSelected: Boolean): BitmapDrawable {
    val size = if (isSelected) 84 else 68
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer glow if selected
    if (isSelected) {
        paint.color = bus.hexColor
        paint.alpha = 80
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    }

    // Circular Puck
    paint.color = bus.hexColor
    paint.alpha = 255
    canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)

    // White rim
    paint.style = Paint.Style.STROKE
    paint.color = android.graphics.Color.WHITE
    paint.strokeWidth = 5f
    canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)

    // Center dot / bus glyph indicator
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size * 0.14f, paint)

    return BitmapDrawable(context.resources, bitmap)
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
