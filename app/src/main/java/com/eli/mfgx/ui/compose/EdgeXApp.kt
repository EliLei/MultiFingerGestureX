package com.eli.mfgx.ui.compose

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eli.mfgx.BuildConfig
import com.eli.mfgx.R
import com.eli.mfgx.config.AppConfig
import com.eli.mfgx.config.ModuleActivationState
import com.eli.mfgx.config.configPrefs
import com.eli.mfgx.config.getConfigBool
import com.eli.mfgx.config.getConfigString
import com.eli.mfgx.config.putConfig
import com.eli.mfgx.ui.compose.components.EdgeXIcon
import com.eli.mfgx.ui.compose.components.EdgeXIcons
import com.eli.mfgx.ui.compose.components.EdgeXTopBar
import com.eli.mfgx.ui.compose.components.EdgeXToast
import com.eli.mfgx.ui.compose.components.UpdateDialog
import com.eli.mfgx.ui.compose.screens.HomeCallbacks
import com.eli.mfgx.ui.compose.screens.HomeScreen
import com.eli.mfgx.ui.compose.screens.MultiTouchGesturesScreen
import com.eli.mfgx.ui.compose.theme.EdgeXAccent
import com.eli.mfgx.ui.compose.theme.EdgeXTheme
import com.eli.mfgx.ui.compose.theme.LocalEdgeXColors
import com.eli.mfgx.utils.UpdateChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay

enum class EdgeXRoute(@StringRes val labelRes: Int) {
    Home(R.string.compose_route_home),
    MultiTouchGestures(R.string.header_multitouch_gestures),
    About(R.string.menu_about),
}

data class HomeUiState(
    val stats: HomeStats,
    val gesturesEnabled: Boolean,
    val debug: Boolean,
    val haptic: Boolean,
    val hapticType: String,
    val moduleActive: Boolean,
    val accent: EdgeXAccent,
    val darkMode: Boolean,
)

data class HomeStats(
    val configuredGestures: Int,
)

@Composable
fun EdgeXApp() {
    val context = LocalContext.current
    val restartSystemUiFailed = stringResource(R.string.toast_restart_sysui_failed)
    val updateChecking = stringResource(R.string.update_checking)
    val updateAlreadyLatest = stringResource(R.string.update_already_latest)
    val stack = remember { mutableStateListOf(EdgeXRoute.Home) }
    val saveableStateHolder = rememberSaveableStateHolder()
    var uiState by remember { mutableStateOf(context.readHomeUiState()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    fun refresh() {
        uiState = context.readHomeUiState()
    }

    fun showToast(message: String) {
        toast = message
    }

    fun popRoute() {
        if (stack.size > 1) {
            val popped = stack.removeAt(stack.lastIndex)
            saveableStateHolder.removeState(popped)
        }
    }

    fun popRouteAndRefresh() {
        refresh()
        popRoute()
    }

    fun checkForUpdates() {
        showToast(updateChecking)
        UpdateChecker.checkNow(context as Activity) { release ->
            if (release == null) {
                showToast(updateAlreadyLatest)
            } else {
                availableUpdate = release
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1800)
            toast = null
        }
    }

    LaunchedEffect(Unit) {
        UpdateChecker.checkOnLaunch(context as Activity) { availableUpdate = it }
        ModuleActivationState.requestRefresh(context)
        delay(350)
        refresh()
    }

    EdgeXTheme(darkTheme = uiState.darkMode, accent = uiState.accent) {
        val colors = LocalEdgeXColors.current
        BackHandler(enabled = stack.size > 1) {
            when (stack.last()) {
                EdgeXRoute.MultiTouchGestures -> popRouteAndRefresh()
                else -> popRoute()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val route = stack.last()
            saveableStateHolder.SaveableStateProvider(key = route) {
                when (route) {
                    EdgeXRoute.Home -> HomeScreen(
                        state = uiState,
                        callbacks = HomeCallbacks(
                            openRoute = { stack.add(it) },
                            showToast = ::showToast,
                            restartSystemUi = {
                                restartSystemUi {
                                    showToast(restartSystemUiFailed)
                                }
                            },
                            setDebug = {
                                context.putConfig(AppConfig.DEBUG_MATRIX, it)
                                refresh()
                            },
                            setHaptic = {
                                context.putConfig(AppConfig.HAPTIC_FEEDBACK, it)
                                refresh()
                            },
                            setHapticType = {
                                context.putConfig(AppConfig.HAPTIC_FEEDBACK_TYPE, it)
                                refresh()
                            },
                            setArcDrawer = {
                                // No longer applicable
                            },
                        ),
                    )
                    EdgeXRoute.MultiTouchGestures -> MultiTouchGesturesScreen(
                        onBack = ::popRouteAndRefresh,
                        showToast = ::showToast,
                    )
                    EdgeXRoute.About -> AboutScreen(
                        onBack = ::popRoute,
                        showToast = ::showToast,
                        onCheckForUpdates = ::checkForUpdates,
                    )
                }
            }

            EdgeXToast(
                message = toast,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
            )

            availableUpdate?.let { release ->
                UpdateDialog(
                    release = release,
                    onDismiss = { availableUpdate = null },
                    onSkip = {
                        UpdateChecker.skipVersion(context, release)
                        availableUpdate = null
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    showToast: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EdgeXTopBar(
            title = stringResource(R.string.menu_about),
            onBack = onBack,
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.value_project_url),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = onCheckForUpdates,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.update_checking))
            }
        }
    }
}

private fun restartSystemUi(onFailure: () -> Unit) {
    Thread {
        val succeeded = runCatching {
            Shell.cmd("killall com.android.systemui").exec().isSuccess
        }.getOrDefault(false)
        if (!succeeded) {
            Handler(Looper.getMainLooper()).post(onFailure)
        }
    }.start()
}

private fun Context.readHomeUiState(): HomeUiState =
    HomeUiState(
        stats = readHomeStats(),
        gesturesEnabled = getConfigBool(AppConfig.GESTURES_ENABLED),
        debug = getConfigBool(AppConfig.DEBUG_MATRIX),
        haptic = getConfigBool(AppConfig.HAPTIC_FEEDBACK, default = true),
        hapticType = getConfigString(
            AppConfig.HAPTIC_FEEDBACK_TYPE,
            AppConfig.HAPTIC_FEEDBACK_TYPE_CLICK,
        ),
        moduleActive = ModuleActivationState.isActive(this),
        accent = EdgeXAccent.fromId(getConfigString(AppConfig.UI_ACCENT, EdgeXAccent.Default.id)),
        darkMode = run {
            val darkSetting = getConfigString(AppConfig.UI_DARK_MODE, "system")
            when (darkSetting) {
                "dark" -> true
                "light" -> false
                "system" -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                else -> darkSetting.toBooleanStrictOrNull() ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }
        },
    )

private fun Context.readHomeStats(): HomeStats {
    val prefs = configPrefs()
    var configuredGestures = 0
    for (count in AppConfig.MULTI_TOUCH_FINGER_COUNTS) {
        for (type in AppConfig.MULTI_TOUCH_GESTURE_TYPES) {
            val enabled = prefs.getString(AppConfig.gestureEnabledKey(count, type), "false") == "true"
            val action = prefs.getString(AppConfig.gestureActionKey(count, type), "")
            if (enabled && !action.isNullOrBlank() && action != "none") {
                configuredGestures++
            }
        }
    }
    return HomeStats(configuredGestures = configuredGestures)
}
