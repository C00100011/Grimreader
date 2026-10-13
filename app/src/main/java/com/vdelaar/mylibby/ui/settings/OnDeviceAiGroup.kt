package com.vdelaar.mylibby.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vdelaar.mylibby.R
import com.vdelaar.mylibby.ai.AiStatus
import com.vdelaar.mylibby.di.AppContainer

private const val AICORE = "com.google.android.aicore"

/**
 * The way to remove the model. It belongs to Android's AICore app, so Grimreader cannot delete it itself: the button
 * opens AICore's app info, where its storage can be cleared. Shown only while there is a model to remove.
 */
@Composable
fun OnDeviceAiGroup(c: AppContainer) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<AiStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // Coming back from AICore's settings: the model may be gone now.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) { status = c.localAi.status() }
    val aicore = remember(refresh) { runCatching { context.packageManager.getPackageInfo(AICORE, 0) }.isSuccess }
    if (!aicore || (status != AiStatus.AVAILABLE && status != AiStatus.DOWNLOADING)) return
    SettingsGroup(stringResource(R.string.on_device_ai)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ai_remove_model_body), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$AICORE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.ai_remove_model)) }
        }
    }
}
