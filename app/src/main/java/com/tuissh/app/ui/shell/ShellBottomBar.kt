package com.tuissh.app.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.R
import com.tuissh.app.ui.theme.LocalMacOsColors

/**
 * The five bottom destinations.
 */
enum class ShellTab(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
) {
    METRICS("metrics", Icons.Outlined.Dashboard, R.string.nav_metrics),
    TERMINAL("terminal_tab", Icons.Outlined.Terminal, R.string.nav_terminal),
    FILES("files_tab", Icons.Outlined.Folder, R.string.nav_sftp),
    CONTAINERS("containers_tab", Icons.Outlined.Layers, R.string.nav_containers),
    MORE("more", Icons.Outlined.MoreHoriz, R.string.nav_more),
}

/**
 * macOS 27 Liquid Glass Floating Capsule Dock.
 */
@Composable
fun ShellBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMacOsColors.current
    val capsuleShape = RoundedCornerShape(26.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(capsuleShape)
                .background(tokens.chromeGlassBar)
                .border(
                    BorderStroke(
                        width = 0.75.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                tokens.specularBorderTop,
                                tokens.specularBorderBottom,
                            ),
                        ),
                    ),
                    shape = capsuleShape,
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in ShellTab.entries) {
                BottomItem(
                    tab = tab,
                    active = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomItem(
    tab: ShellTab,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMacOsColors.current
    val tint = if (active) tokens.primaryAccent else tokens.textSecondary
    val pillShape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .clip(pillShape)
            .background(if (active) tokens.selectionPill else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = active, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Box(Modifier.size(2.dp))
        Text(
            text = stringResource(tab.labelRes),
            color = tint,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Placeholder body for a tab whose engine has not landed yet. */
@Composable
fun TabPlaceholder(title: String, detail: String, modifier: Modifier = Modifier) {
    val tokens = LocalMacOsColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.windowBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            Box(Modifier.size(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
    }
}
