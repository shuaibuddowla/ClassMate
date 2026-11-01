package com.shuaib.classmate.ui

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.bumptech.glide.Glide
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.activities.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object ClassMateHamburgerSheet {

    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        batchId: () -> String,
        batchLabel: () -> String,
        profile: () -> JSONObject
    ) {
        val decorView = activity.window.decorView as? ViewGroup ?: return
        var composeView: ComposeView? = null

        val onDismiss: () -> Unit = {
            composeView?.let { cv ->
                decorView.removeView(cv)
                composeView = null
            }
        }

        composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MaterialTheme {
                    ClassMateHamburgerModalSheet(
                        activity = activity,
                        scope = scope,
                        batchId = batchId,
                        batchLabel = batchLabel,
                        profile = profile,
                        onDismiss = onDismiss
                    )
                }
            }
        }
        decorView.addView(composeView)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassMateHamburgerModalSheet(
    activity: AppCompatActivity,
    scope: CoroutineScope,
    batchId: () -> String,
    batchLabel: () -> String,
    profile: () -> JSONObject,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val p = remember { profile() }
    val fullName = remember(p) { p.optString("full_name").trim().ifBlank { "ClassMate User" } }
    val studentId = remember(p) { p.optString("student_id").trim() }
    val role = remember(p) { p.optString("role") }
    val isServerOwner = remember(p) { p.optBoolean("is_server_owner") }
    val isCr = remember(p) { p.optBoolean("is_cr") }
    val isTeacher = role == "teacher"
    val currentBatch = remember(batchLabel) { batchLabel().ifBlank { "MBSTU" } }
    val avatarUrl = remember(p) { p.optString("avatar_url").takeUnless { it.isBlank() || it.equals("null", true) } }

    val jakartaBold = remember { FontFamily(Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)) }
    val jakartaExtraBold = remember { FontFamily(Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold)) }

    val configuration = LocalConfiguration.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxSheetHeight = (configuration.screenHeightDp.dp - topInset - 16.dp).coerceAtLeast(200.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.statusBarsPadding(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.padding(top = 8.dp)
            )
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        // Status bar icons while the sheet is open: white on dark scrim
        val view = LocalView.current
        var parentView = view.parent
        while (parentView != null && parentView !is DialogWindowProvider) {
            parentView = parentView.parent
        }
        val window = (parentView as? DialogWindowProvider)?.window ?: (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let { win ->
                WindowCompat.getInsetsController(win, view)
                    .isAppearanceLightStatusBars = false // white icons on dark scrim
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
        ) {
            // ~12dp gap between drag handle and "classmate." header row
            Spacer(modifier = Modifier.height(12.dp))

            // Fixed Header Row: Brand Title + Close Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "classmate",
                        fontFamily = jakartaExtraBold,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colorResource(R.color.cm_text_primary)
                    )
                    Text(
                        text = ".",
                        fontFamily = jakartaExtraBold,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colorResource(R.color.cm_primary)
                    )
                }

                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        coroutineScope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Close drawer",
                        tint = colorResource(R.color.cm_text_secondary),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fixed Profile Card
            DrawerProfileCard(
                fullName = fullName,
                studentId = studentId,
                currentBatch = currentBatch,
                isServerOwner = isServerOwner,
                isTeacher = isTeacher,
                isCr = isCr,
                avatarUrl = avatarUrl,
                jakartaBold = jakartaBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Menu List
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                fun dismissAndRun(action: () -> Unit) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    coroutineScope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            onDismiss()
                            action()
                        }
                    }
                }

                // SECTION 1: CAMPUS & ACADEMIC
                Text(
                    text = "CAMPUS & ACADEMIC",
                    fontFamily = jakartaBold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colorResource(R.color.cm_text_muted),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                DrawerMenuItem(
                    title = "MBSTU Bus Transit",
                    subtitle = "Live bus radar, schedule & routes",
                    iconRes = R.drawable.ic_bus_filled,
                    iconTint = Color(0xFF2563EB),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            val intent = Intent(activity, ClassMateTransitActivity::class.java).apply {
                                putExtra("EXTRA_BATCH_ID", batchId())
                                putExtra("EXTRA_ROLE", role)
                            }
                            activity.startActivity(intent)
                        }
                    }
                )

                DrawerMenuItem(
                    title = "Teachers Directory",
                    subtitle = "Faculty contacts & assigned courses",
                    iconRes = R.drawable.ic_academic_cap,
                    iconTint = Color(0xFF7C3AED),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            ClassMateTeachersDialog.show(
                                context = activity,
                                scope = scope,
                                batchId = batchId(),
                                batchLabel = batchLabel(),
                                profile = profile()
                            )
                        }
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    thickness = 1.dp,
                    color = colorResource(R.color.cm_border).copy(alpha = 0.4f)
                )

                // SECTION 2: BATCH HUB
                Text(
                    text = "BATCH HUB",
                    fontFamily = jakartaBold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colorResource(R.color.cm_text_muted),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                DrawerMenuItem(
                    title = "Batchmates",
                    subtitle = "Student roster, rolls & contact info",
                    iconRes = R.drawable.ic_batch_friends,
                    iconTint = Color(0xFF0D9488),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            val intent = Intent(activity, ClassMateBatchActivity::class.java).apply {
                                putExtra("EXTRA_BATCH_ID", batchId())
                                putExtra("EXTRA_BATCH_LABEL", batchLabel())
                                putExtra("EXTRA_ROLE", role)
                            }
                            activity.startActivity(intent)
                        }
                    }
                )

                DrawerMenuItem(
                    title = "Batch Fund",
                    subtitle = "Treasury balance, collections & expenses",
                    iconRes = R.drawable.ic_fund_wallet,
                    iconTint = Color(0xFFEA580C),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            val intent = Intent(activity, ClassMateBatchFundActivity::class.java).apply {
                                putExtra("batch_id", batchId())
                                putExtra("batch_label", batchLabel())
                            }
                            activity.startActivity(intent)
                        }
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    thickness = 1.dp,
                    color = colorResource(R.color.cm_border).copy(alpha = 0.4f)
                )

                // SECTION 3: CAMPUS UTILITIES
                Text(
                    text = "CAMPUS UTILITIES",
                    fontFamily = jakartaBold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colorResource(R.color.cm_text_muted),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )

                DrawerMenuItem(
                    title = "Export Class Routine",
                    subtitle = "Share weekly routine via WhatsApp",
                    iconRes = R.drawable.ic_share_outline,
                    iconTint = Color(0xFF4338CA),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            ClassMateRoutineExporter.exportRoutine(
                                context = activity,
                                accountId = p.optString("id"),
                                batchId = batchId(),
                                batchLabel = batchLabel()
                            )
                        }
                    }
                )

                DrawerMenuItem(
                    title = "Blood Donor Directory",
                    subtitle = "Campus emergency blood donors",
                    iconRes = R.drawable.ic_blood_drop,
                    iconTint = Color(0xFFDC2626),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            val intent = Intent(activity, ClassMateBloodActivity::class.java).apply {
                                putExtra("batch_id", batchId())
                            }
                            activity.startActivity(intent)
                        }
                    }
                )

                DrawerMenuItem(
                    title = "Emergency Hotlines",
                    subtitle = "Proctor, Medical Center & Ambulance",
                    iconRes = R.drawable.ic_phone,
                    iconTint = Color(0xFFD97706),
                    jakartaBold = jakartaBold,
                    onClick = {
                        dismissAndRun {
                            ClassMateEmergencyDialog.show(activity)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Footer Version
                Text(
                    text = "ClassMate v${BuildConfig.VERSION_NAME} · MBSTU Student Companion",
                    fontSize = 11.5.sp,
                    color = colorResource(R.color.cm_text_muted),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.navigationBarsPadding().height(16.dp))
            }
        }
    }
}

@Composable
private fun DrawerProfileCard(
    fullName: String,
    studentId: String,
    currentBatch: String,
    isServerOwner: Boolean,
    isTeacher: Boolean,
    isCr: Boolean,
    avatarUrl: String?,
    jakartaBold: FontFamily
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorResource(R.color.cm_background)),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorResource(R.color.cm_border).copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (!avatarUrl.isNullOrBlank()) {
                            Glide.with(ctx)
                                .load(avatarUrl)
                                .circleCrop()
                                .placeholder(R.drawable.bg_circle_avatar_placeholder)
                                .into(this)
                        } else {
                            setImageResource(R.drawable.bg_circle_avatar_placeholder)
                        }
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = fullName,
                    fontFamily = jakartaBold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(R.color.cm_text_primary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (studentId.isNotBlank()) "$studentId · $currentBatch" else currentBatch,
                    fontSize = 12.sp,
                    color = colorResource(R.color.cm_text_secondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            val (badgeText, badgeColor, badgeBg) = when {
                isServerOwner -> Triple("Admin", Color(0xFFEA580C), Color(0x1AEA580C))
                isTeacher -> Triple("Faculty", Color(0xFF2563EB), Color(0x1A2563EB))
                isCr -> Triple("CR", Color(0xFF7C3AED), Color(0x1A7C3AED))
                else -> Triple("Student", Color(0xFF16A34A), Color(0x1A16A34A))
            }

            Box(
                modifier = Modifier
                    .wrapContentWidth()
                    .background(color = badgeBg, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badgeText,
                    fontFamily = jakartaBold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
            }
        }
    }
}

@Composable
private fun DrawerMenuItem(
    title: String,
    subtitle: String,
    iconRes: Int,
    iconTint: Color,
    jakartaBold: FontFamily,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    color = iconTint.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = title,
                fontFamily = jakartaBold,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.cm_text_primary)
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = colorResource(R.color.cm_text_secondary),
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = colorResource(R.color.cm_text_muted),
            modifier = Modifier.size(16.dp)
        )
    }
}
