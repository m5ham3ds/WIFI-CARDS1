package com.example.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Drawer navigation menu with a native, bitmap-free brand header.
 */
@Composable
fun DrawerMenuCompose(
    activeItem: String = "home",
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(Color(0xFF07090F))
    ) {
        // Native, bitmap-free drawer banner composed from Canvas artwork and vector icons.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
        ) {
            HeaderBackdropArtwork(Modifier.fillMaxSize())
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x33FF1E27))
                        .border(1.dp, NeonRed.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi_large),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.menu_home_sub),
                        color = Color(0xFFBCC8DC),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x3314B8A6))
                            .border(1.dp, Color(0x6653E3D1), CircleShape)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "HOTSPOT TOOLS",
                            color = Color(0xFFC2FFF5),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        // 2. Navigation Items (Scrollable Column)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. الرئيسية
            DrawerNavRowItem(
                title = stringResource(R.string.menu_home),
                subtitle = stringResource(R.string.menu_home_sub),
                iconRes = R.drawable.ic_nav_home,
                isActive = activeItem == "home",
                activeColor = NeonRed,
                onClick = { onItemClick("home") }
            )

            // 2. الاختبار
            DrawerNavRowItem(
                title = stringResource(R.string.menu_test),
                subtitle = stringResource(R.string.menu_test_sub),
                iconRes = R.drawable.ic_nav_test,
                isActive = activeItem == "test",
                onClick = { onItemClick("test") }
            )

            // 3. السجلات
            DrawerNavRowItem(
                title = stringResource(R.string.menu_history),
                subtitle = stringResource(R.string.menu_history_sub),
                iconRes = R.drawable.ic_nav_history,
                isActive = activeItem == "history",
                onClick = { onItemClick("history") }
            )

            // 4. إدارة أجهزة الراوتر
            DrawerNavRowItem(
                title = stringResource(R.string.menu_router_manager),
                subtitle = stringResource(R.string.menu_routers_sub),
                iconRes = R.drawable.ic_nav_router,
                isActive = activeItem == "routers",
                onClick = { onItemClick("routers") }
            )

            // 5. الإعدادات
            DrawerNavRowItem(
                title = stringResource(R.string.menu_settings),
                subtitle = stringResource(R.string.menu_settings_sub),
                iconRes = R.drawable.ic_nav_settings,
                isActive = activeItem == "settings",
                onClick = { onItemClick("settings") }
            )

            // Section Divider: خيارات إضافية
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.menu_additional_options),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp, top = 4.dp, bottom = 4.dp)
            )

            // 6. حول التطبيق
            DrawerNavRowItem(
                title = stringResource(R.string.menu_about),
                subtitle = stringResource(R.string.about_title),
                iconRes = R.drawable.ic_info,
                isActive = activeItem == "about",
                onClick = { onItemClick("about") }
            )

            // 7. الخروج
            DrawerNavRowItem(
                title = stringResource(R.string.menu_exit),
                subtitle = stringResource(R.string.main_confirm_exit_title),
                iconRes = R.drawable.ic_nav_exit,
                isActive = false,
                isExit = true,
                onClick = { onItemClick("exit") }
            )

            Spacer(modifier = Modifier.height(10.dp))
        }

        // 3. Footer with antenna tower and version badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFF140205))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            // Radio Tower Icon on the right
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_antenna_tower),
                    contentDescription = "Tower",
                    tint = NeonRed.copy(alpha = 0.35f),
                    modifier = Modifier.size(54.dp)
                )
            }

            // Version Pill on the bottom start
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF0F1522))
                    .border(1.dp, Color(0xFF1E283C), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(NeonRed)
                    )
                    Text(
                        text = "v1.0.0-Stable",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun DrawerNavRowItem(
    title: String,
    subtitle: String,
    iconRes: Int,
    isActive: Boolean,
    isExit: Boolean = false,
    activeColor: Color = NeonRed,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isActive) Color(0xFF1A0307)
                else Color(0xFF0E131E)
            )
            .border(
                1.5.dp,
                if (isActive) NeonRed else Color(0xFF182030),
                RoundedCornerShape(16.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Chevron Arrow (<)
            Icon(
                painter = painterResource(id = R.drawable.ic_back),
                contentDescription = "Arrow",
                tint = if (isActive) Color.White else TextMuted,
                modifier = Modifier.size(14.dp)
            )

            // Right: Text on the left, Icon Box on the far right (RTL look)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        textAlign = TextAlign.End
                    )
                }

                // Icon Box
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isActive) NeonRed
                            else if (isExit) Color(0xFF220307)
                            else Color(0xFF182032)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = title,
                        tint = if (isActive) Color.White else (if (isExit) NeonRed else Color(0xFFCBD5E1)),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
