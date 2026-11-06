package com.tuissh.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.ui.theme.TuiPalette

/**
 * The five bottom destinations.
 *
 * Mirrors the original iOS layout: metrics, terminal, files, containers, more. The
 * order is not arbitrary — it follows how the original app's tabs were read
 * left-to-right, so muscle memory transfers.
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
 * Bottom navigation.
 *
 * Hand-rolled rather than `NavigationBar` because the skin calls for a flatter shape
 * and an amber indicator: the selected item takes the accent colour and a soft
 * background pill, unselected items stay muted. Material's default puts an oversized
 * pill behind the icon that fights a dark violet ground.
 *
 * Selection is exposed as callbacks instead of reading the nav controller, so this
 * composable is testable and the graph owns routing.
 */
@Composable
fun ShellBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A fixed height is required: the Row does not constrain its children, so
            // `fillMaxHeight` inside BottomItem expanded to the whole screen and
            // pushed the content out of view.
            .height(IntrinsicSize.Min)
            .background(TuiPalette.InkRaised)
            .padding(horizontal = 6.dp, vertical = 6.dp),
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

@Composable
private fun BottomItem(
    tab: ShellTab,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (active) TuiPalette.Amber else TuiPalette.TextSecondary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) TuiPalette.Amber.copy(alpha = 0.12f) else TuiPalette.InkRaised)
            .selectable(selected = active, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Box(Modifier.size(2.dp))
        Text(
            text = stringResource(tab.labelRes),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Placeholder body for a tab whose engine has not landed yet. */
@Composable
fun TabPlaceholder(title: String, detail: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.size(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = TuiPalette.TextSecondary,
            )
        }
    }
}
