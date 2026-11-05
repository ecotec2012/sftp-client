package org.ecotecworld.sftpclient

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ecotecworld.sftpclient.ui.BrowserScreen
import org.ecotecworld.sftpclient.ui.HostKeyDialog
import org.ecotecworld.sftpclient.ui.MainViewModel
import org.ecotecworld.sftpclient.ui.ProfileEditorScreen
import org.ecotecworld.sftpclient.ui.ProfilesScreen
import org.ecotecworld.sftpclient.ui.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SftpTheme {
                App()
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    when (val s = vm.screen) {
        Screen.Profiles -> ProfilesScreen(vm)
        is Screen.Editor -> ProfileEditorScreen(vm, s.profile)
        Screen.Browser -> BrowserScreen(vm)
    }
    vm.hostKeyPrompt?.let { prompt ->
        HostKeyDialog(prompt.message, onAnswer = vm::answerHostKey)
    }
}

@Composable
fun SftpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
