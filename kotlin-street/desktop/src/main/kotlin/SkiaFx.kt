import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import com.lifemax.rpg.feature.habits.presentation.scene.FxShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** The preview's shaders: Skia's RuntimeEffect (SkSL), as the phone runs them with RuntimeShader (AGSL). */
class SkiaFx(src: String) : FxShader {
    private val b = RuntimeShaderBuilder(RuntimeEffect.makeForShader(src))
    override fun set(name: String, vararg v: Float) {
        when (v.size) {
            1 -> b.uniform(name, v[0]); 2 -> b.uniform(name, v[0], v[1]); 3 -> b.uniform(name, v[0], v[1], v[2])
            4 -> b.uniform(name, v[0], v[1], v[2], v[3]); else -> b.uniform(name, v)
        }
    }
    override fun brush(): Brush = ShaderBrush(b.makeShader(null))
}
