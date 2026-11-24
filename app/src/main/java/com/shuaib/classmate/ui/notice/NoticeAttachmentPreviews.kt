package com.shuaib.classmate.ui.notice

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuaib.classmate.R
import com.shuaib.classmate.ui.assessment.PlusJakartaBold

@Composable
fun rememberThumbnailBitmap(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    return remember(uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4
                }
                BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
            }
        }.getOrNull()
    }
}

@Composable
fun NoticeAttachmentPreviews(
    attachments: List<NoticeAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onAddMorePhotos: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    if (attachments.isEmpty()) return

    val first = attachments.first()
    if (first.isPdf) {
        // Single PDF Card Pill
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) Color(0xFF131A2A) else Color(0xFFF1F5F9)
            ),
            border = BorderStroke(1.dp, if (isDark) Color(0x30FFFFFF) else Color(0x18000000)),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFEF4444).copy(alpha = if (isDark) 0.22f else 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_picture_as_pdf),
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = first.name,
                        fontSize = 13.sp,
                        fontFamily = PlusJakartaBold,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${first.formattedSize} · PDF Document",
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                }

                IconButton(
                    onClick = { onRemoveAttachment(0) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Remove PDF",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    } else {
        // Multi-Photo Carousel Thumbnails
        LazyRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            itemsIndexed(attachments) { index, item ->
                Box(
                    modifier = Modifier
                        .size(68.dp)
                ) {
                    val bitmap = rememberThumbnailBitmap(item.uri)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(62.dp)
                                .align(Alignment.BottomStart)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .align(Alignment.BottomStart)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gallery_photo),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Delete badge in top right corner
                    Surface(
                        onClick = { onRemoveAttachment(index) },
                        shape = CircleShape,
                        color = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.TopEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Remove photo",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            if (attachments.size < 5) {
                item {
                    Surface(
                        onClick = onAddMorePhotos,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) Color(0xFF141D30) else Color(0xFFF1F5F9),
                        border = BorderStroke(1.dp, if (isDark) Color(0x30FFFFFF) else Color(0x20000000)),
                        modifier = Modifier
                            .size(62.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gallery_photo),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "+ Add",
                                fontSize = 10.sp,
                                fontFamily = PlusJakartaBold,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5)
                            )
                        }
                    }
                }
            }
        }
    }
}
