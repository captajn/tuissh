package com.tuissh.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.i18n.LocaleEngine
import com.tuissh.app.ui.theme.AppThemeMode
import com.tuissh.app.ui.theme.LocalMacOsColors
import com.tuissh.app.ui.theme.TuiPalette
import com.tuissh.app.ui.theme.liquidGlassCard
import kotlinx.coroutines.launch

/**
 * App settings.
 *
 * Split into sections that match what a user would look for rather than by which module
 * owns the value: appearance, terminal behaviour, and the entries that lead elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onManageKeys: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tokens = LocalMacOsColors.current

    LaunchedEffect(Unit) { viewModel.load() }

    // Changing the language re-creates the activity once the value is written, so the
    // user does not have to kill the app for a setting that looks applied but is not.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val onLanguageChange: (String) -> Unit = { tag ->
        viewModel.setLanguage(tag)
        scope.launch {
            kotlinx.coroutines.delay(150)
            val target = if (tag == SettingsViewModel.FOLLOW_SYSTEM) {
                java.util.Locale.getDefault().toLanguageTag()
            } else {
                tag
            }
            (context as? androidx.activity.ComponentActivity)?.let {
                LocaleEngine.restartFor(it, target)
            }
        }
    }

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    navigationIconContentColor = tokens.textPrimary,
                ),
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.server_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_appearance))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .liquidGlassCard(RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemeSegmentPill(
                    title = stringResource(R.string.settings_theme_dark),
                    selected = state.appThemeMode == AppThemeMode.DARK,
                    onClick = { viewModel.setAppTheme(AppThemeMode.DARK) },
                    modifier = Modifier.weight(1f),
                )
                ThemeSegmentPill(
                    title = stringResource(R.string.settings_theme_light),
                    selected = state.appThemeMode == AppThemeMode.LIGHT,
                    onClick = { viewModel.setAppTheme(AppThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f),
                )
                ThemeSegmentPill(
                    title = stringResource(R.string.settings_theme_system),
                    selected = state.appThemeMode == AppThemeMode.SYSTEM,
                    onClick = { viewModel.setAppTheme(AppThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(
                color = tokens.dividerHairline,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            SectionTitle(stringResource(R.string.settings_language))

            for (option in viewModel.languages) {
                LanguageRow(
                    label = option.label,
                    tag = option.tag,
                    selected = state.language == option.tag,
                    onClick = { onLanguageChange(option.tag) },
                )
            }

            HorizontalDivider(
                color = tokens.dividerHairline,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            SectionTitle(stringResource(R.string.settings_terminal))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_font_size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${state.fontSize} sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = TuiPalette.Amber,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Slider(
                    value = state.fontSize.toFloat(),
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                    valueRange = SettingsViewModel.UiState.MIN_FONT_SIZE.toFloat()..
                        SettingsViewModel.UiState.MAX_FONT_SIZE.toFloat(),
                    steps = SettingsViewModel.UiState.MAX_FONT_SIZE -
                        SettingsViewModel.UiState.MIN_FONT_SIZE - 1,
                    modifier = Modifier.weight(1.4f),
                )
            }

            SwitchRow(
                title = stringResource(R.string.settings_cursor_blink),
                checked = state.cursorBlink,
                onChange = viewModel::setCursorBlink,
            )
            SwitchRow(
                title = stringResource(R.string.settings_keep_awake),
                checked = state.keepScreenOn,
                onChange = viewModel::setKeepScreenOn,
            )

            TextButton(
                onClick = viewModel::resetTerminalLook,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_reset_look)) }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_security))

            NavRow(
                icon = Icons.Default.Key,
                title = stringResource(R.string.key_title),
                subtitle = stringResource(R.string.settings_keys_subtitle),
                onClick = onManageKeys,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_about))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.settings_version),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = appVersion(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TuiPalette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ThemeSegmentPill(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMacOsColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.selectionPill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.primaryAccent else tokens.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val tokens = LocalMacOsColors.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = tokens.primaryAccent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun LanguageRow(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.selectionPill.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = if (selected) tokens.primaryAccent else tokens.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) tokens.primaryAccent else tokens.textPrimary,
            )
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textTertiary,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = tokens.primaryAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.primaryAccent,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
            )
        }
        Text(
            text = "\u203A",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textTertiary,
        )
    }
}

/** Version string, read reflectively so this file needs no BuildConfig import. */
private fun appVersion(): String = runCatching {
    val buildConfig = Class.forName("com.tuissh.app.BuildConfig")
    val name = buildConfig.getField("VERSION_NAME").get(null) as String
    val code = buildConfig.getField("VERSION_CODE").get(null)
    "$name ($code)"
}.getOrDefault("debug")

/** Small rounded chip used by the appearance preview row. */
@Composable
private fun PaletteChip(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TuiPalette.InkCard)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
