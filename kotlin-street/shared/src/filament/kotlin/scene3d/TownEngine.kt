package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine

/**
 * The Filament engine's sizes for the whole town (TOWN.md T10), the app and the desktop preview alike: a street view
 * draws about 1,100-1,300 primitives and their shadow casters a frame, and the town holds thousands of textures,
 * buffers and material instances, so the defaults (a few MB) spill: Filament then warns that its handle arena is full
 * and falls back to the slower system heap. 12 GB of phone pays for arenas this size many times over.
 */
object TownEngine {
    fun config(): Engine.Config = Engine.Config().apply {
        // the GL driver's handles (textures, buffers, render primitives): thousands of them for the whole town
        driverHandleArenaSizeMB = 64
        // the commands a frame sends the driver, and the render passes' own (the view and the shadow cascades)
        minCommandBufferSizeMB = 4
        commandBufferSizeMB = 16
        perRenderPassArenaSizeMB = 16
        perFrameCommandsSizeMB = 8
    }
}
