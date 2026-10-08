package com.vdelaar.mylibby.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vdelaar.mylibby.core.AvatarStore
import com.vdelaar.mylibby.core.datastore.Profile
import com.vdelaar.mylibby.ui.appContainer
import kotlinx.coroutines.launch

/** The user's own photo when they set one, otherwise their emoji avatar. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo = remember(profile.photoVersion) { AvatarStore.file(context, profile.photoVersion) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = modifier.size(size).clip(CircleShape)) {
        if (photo != null) {
            AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Box(contentAlignment = Alignment.Center) { Text(profile.avatar, fontSize = (size.value * .5f).sp) }
        }
    }
}

/** Opens the system photo picker and stores the chosen picture as the profile photo. */
@Composable
fun rememberPhotoPicker(): () -> Unit {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val old = c.settings.app.value.profile.photoVersion
            val version = AvatarStore.save(context, uri, old) ?: return@launch
            c.settings.updateApp { it.copy(profile = it.profile.copy(photoVersion = version)) }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * Star rating on Grimmory's 1..10 scale (two points per star, so half stars work).
 * With [onChange] set, tapping the left half of a star gives a half star; tapping your current rating clears it.
 */
@Composable
fun StarRating(value: Int, modifier: Modifier = Modifier, starSize: Dp = 20.dp, onChange: ((Int?) -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val icon = when {
                value >= star * 2 -> Icons.Rounded.Star
                value == star * 2 - 1 -> Icons.Rounded.StarHalf
                else -> Icons.Rounded.StarBorder
            }
            val tint = if (value >= star * 2 - 1) com.vdelaar.mylibby.ui.theme.Gold else MaterialTheme.colorScheme.outline
            val m = if (onChange == null) Modifier else Modifier.pointerInput(star, value) {
                detectTapGestures { offset ->
                    val v = if (offset.x < size.width / 2f) star * 2 - 1 else star * 2
                    onChange(if (v == value) null else v)
                }
            }
            Icon(icon, null, tint = tint, modifier = m.size(starSize))
        }
    }
}
