package com.example.presentation.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/** Bitmap-free header artwork drawn at runtime with Compose Canvas. */
@Composable
internal fun HeaderBackdropArtwork(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFF070A12), Color(0xFF111A2B), Color(0xFF090B13))
            )
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                color = Color(0x24FF1E27),
                radius = h * 1.15f,
                center = Offset(w * 0.93f, h * 0.20f)
            )
            val gridColor = Color(0x12DDE6FF)
            for (i in 0..8) {
                val x = w * 0.32f + i * h * 0.31f
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x - h * 0.68f, h),
                    strokeWidth = 0.7.dp.toPx()
                )
            }
            for (ring in 0..4) {
                val radius = h * (0.27f + ring * 0.22f)
                drawArc(
                    color = Color(0x28FF3440),
                    startAngle = 202f,
                    sweepAngle = 132f,
                    useCenter = false,
                    topLeft = Offset(w * 0.94f - radius, h * 0.48f - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            for (node in 0..5) {
                drawCircle(
                    color = Color(0x40FF3440),
                    radius = if (node == 2) 1.7.dp.toPx() else 1.dp.toPx(),
                    center = Offset(w * 0.62f + node * 14.dp.toPx(), 10.dp.toPx())
                )
            }
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0xBBFF1E27), Color.Transparent)
                ),
                start = Offset(0f, h - 1.dp.toPx()),
                end = Offset(w, h - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/** Native home header with a Kotlin-drawn technical background and localized brand text. */
@Composable
fun HomeHeader(
    onDrawerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            HeaderBackdropArtwork(Modifier.fillMaxSize())

            IconButton(
                onClick = onDrawerClick,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu),
                    contentDescription = stringResource(R.string.menu_drawer),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, start = 60.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                Text(
                    text = stringResource(R.string.menu_home_sub),
                    color = Color(0xFFBCC8DC),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/** Native sub-page header with dynamic title and subtitle. */
@Composable
fun PagesHeader(
    title: String,
    subtitle: String,
    isRoot: Boolean = false,
    onNavClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            HeaderBackdropArtwork(Modifier.fillMaxSize())

            IconButton(
                onClick = onNavClick,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    painter = painterResource(id = if (isRoot) R.drawable.ic_menu else R.drawable.ic_back),
                    contentDescription = if (isRoot) stringResource(R.string.menu_drawer) else stringResource(R.string.btn_back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, start = 60.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xD9140306))
                        .border(1.dp, NeonRed.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}
