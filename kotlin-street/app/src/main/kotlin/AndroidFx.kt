package com.timerpg.street

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import com.lifemax.rpg.feature.habits.presentation.scene.FxShader

/**
 * The game's GPU effects on the phone: RuntimeShader (AGSL, Android 13 and later). Older phones get none (the street keeps
 * its drawn rain).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AndroidFx(src: String) : FxShader {
    private val shader = RuntimeShader(src)
    private val brush = ShaderBrush(shader)
    override fun set(name: String, vararg v: Float) = shader.setFloatUniform(name, v)
    override fun brush(): Brush = brush

    companion object {
        fun install() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                com.lifemax.rpg.feature.habits.presentation.scene.StageFx.make = { src -> AndroidFx(src) }
        }
    }
}
