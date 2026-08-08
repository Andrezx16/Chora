package com.craftworks.music.ui.screens.tv

import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.WideButton
import com.craftworks.music.R
import com.craftworks.music.data.model.Screen
import com.craftworks.music.managers.DataRefreshManager
import kotlinx.coroutines.launch


@Preview(
    showSystemUi = false, showBackground = true, device = "id:tv_1080p"
)
@Composable
fun TvSettingScreen(
    navHostController: NavHostController = rememberNavController()
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp)
            .focusRestorer(focusRequester)
            .focusRequester(focusRequester)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsButton(
            Screen.S_Appearance.route,
            R.drawable.s_a_palette,
            R.string.Settings_Header_Appearance,
            navHostController,
            Modifier.onFocusChanged {
                focusRequester.saveFocusedChild()
            }
        )

        SettingsButton(
            Screen.S_Providers.route,
            R.drawable.s_m_media_providers,
            R.string.Settings_Header_Media,
            navHostController,
            Modifier.onFocusChanged {
                focusRequester.saveFocusedChild()
            }
        )

        SettingsButton(
            Screen.S_Playback.route,
            R.drawable.s_m_playback,
            R.string.Settings_Header_Playback,
            navHostController,
            Modifier.onFocusChanged {
                focusRequester.saveFocusedChild()
            }
        )

        WideButton(
            modifier = Modifier.onFocusChanged {
                focusRequester.saveFocusedChild()
            },
            onClick = {
                scope.launch {
                    context.cacheDir.listFiles()?.forEach { file ->
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        }
                    }
                    DataRefreshManager.notifyDataSourcesChanged()
                }
                Toast.makeText(context, R.string.Settings_Header_Refresh, Toast.LENGTH_SHORT).show()
            },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            icon = {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.s_m_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.Settings_Header_Refresh), modifier = Modifier
                )
            }
        )
    }
}

@Composable
private fun SettingsButton(
    route: String,
    icon: Int,
    text: Int,
    navHostController: NavHostController,
    modifier: Modifier = Modifier
) {
    WideButton(
        modifier = modifier,
        onClick = {
            navHostController.navigate(route) {
                launchSingleTop = true
            }
        },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        icon = {
            Icon(
                imageVector = ImageVector.vectorResource(icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
        },
        title = {
            Text(
                text = stringResource(text), modifier = Modifier
            )
        }
    )
}