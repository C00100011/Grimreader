package com.vdelaar.mylibby.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps the user's profile picture: a small square JPEG in app storage, named by version so image caches never go stale. */
object AvatarStore {
    private const val SIZE = 512

    fun file(context: Context, version: Long): File? =
        if (version <= 0) null else File(context.filesDir, "profile-$version.jpg").takeIf { it.exists() }

    /** Crops the picked image to a centred square, stores it and returns the new version (or null when it can't be read). */
    suspend fun save(context: Context, uri: Uri, previous: Long): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val side = minOf(raw.width, raw.height)
            val square = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
            val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
            val version = System.currentTimeMillis()
            File(context.filesDir, "profile-$version.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            remove(context, previous)
            version
        }.getOrNull()
    }

    fun remove(context: Context, version: Long) {
        if (version > 0) File(context.filesDir, "profile-$version.jpg").delete()
    }
}
