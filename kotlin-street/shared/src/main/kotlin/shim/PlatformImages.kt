package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The only platform-specific image code in the scene: making an [ImageBitmap] from ARGB pixels, and rewriting
 * one in place. Android and the desktop preview each install their own at start-up ([Platform]). A picture from
 * [create] is immutable on Android (Bitmap.createBitmap from pixels): only one made blank with ImageBitmap(w, h) may be
 * passed to [update].
 */
object PlatformImages {
    lateinit var create: (pixels: IntArray, w: Int, h: Int, mipmap: Boolean) -> ImageBitmap
    lateinit var update: (image: ImageBitmap, pixels: IntArray, w: Int, h: Int) -> Unit
}
