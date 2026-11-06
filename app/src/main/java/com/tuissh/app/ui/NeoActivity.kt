package com.tuissh.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.tuissh.app.di.Graph
import com.tuissh.app.i18n.LocaleEngine
import com.tuissh.app.ui.theme.TuiSshTheme

/**
 * Single activity host. Mirrors the iOS `UIApplicationSceneManifest`.
 *
 * The dependency graph is initialised before `setContent`, so every composable can
 * assume `Graph.get()` is valid.
 */
class NeoActivity : ComponentActivity() {

    /**
     * Applies the app language before the activity inflates anything.
     *
     * Vietnamese is the default, so an English device still starts in Vietnamese
     * unless the user picked another language in Settings.
     */
    /**
     * Applies the stored language before anything inflates.
     *
     * The preference lives in the key/value table, which is not open yet at this point,
     * so it is read through a synchronous shared-preference mirror that
     * [SettingsViewModel] keeps in step. Reading the database here would require opening
     * Room on the main thread during activity start.
     */
    override fun attachBaseContext(newBase: Context) {
        val stored = newBase
            .getSharedPreferences(LANGUAGE_PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
        val tag = LocaleEngine.resolveTag(
            preference = stored,
            systemTag = java.util.Locale.getDefault().toLanguageTag(),
        )
        super.attachBaseContext(LocaleEngine.wrap(newBase, tag))
    }

    companion object {
        /** Mirror of the language setting, readable before the database opens. */
        const val LANGUAGE_PREFS = "app_language"
        const val LANGUAGE_KEY = "tag"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            TuiSshTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NeoApp()
                }
            }
        }
    }
}

/** Kept so previews and tests can render the shell without the activity. */
@Composable
fun NeoAppPreviewHost() {
    TuiSshTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NeoApp()
        }
    }
}
