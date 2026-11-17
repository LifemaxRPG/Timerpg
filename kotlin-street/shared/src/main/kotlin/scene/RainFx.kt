package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A GPU shader the platform runs (Android 13+: RuntimeShader in AGSL; the desktop preview: Skia's RuntimeEffect in SkSL,
 * the same language for what is used here). Uniforms are set once a frame; [brush] paints with it. The shader is evaluated
 * in the drawing's own coordinates (the street's units), so what it draws stays put on the street as the view moves.
 */
interface FxShader {
    fun set(name: String, vararg v: Float)
    fun brush(): Brush
}

/** Where shaders come from: the app (and the preview) set [make]; without it, or on an older phone, effects are skipped. */
object StageFx {
    @Volatile var make: ((String) -> FxShader?)? = null
    private val made = HashMap<String, FxShader?>()

    fun get(src: String): FxShader? {
        val m = make ?: return null
        synchronized(made) { return made.getOrPut(src) { runCatching { m(src) }.getOrNull() } }
    }
}

/** The rain's GPU effects: drops on the window glass, rings on the wet ground. Pixel-crisp, on the sprites' own grid. */
internal object RainFx {
    private const val HASH = """
float h(float2 c) { return fract(sin(dot(c, float2(127.1, 311.7))) * 43758.5453); }
"""

    /**
     * Drops on glass: beads that sit (one or two sprite pixels, a bright face and a dark lip) and drops that run down a
     * pane leaving a fading trail. [px] street units per sprite pixel, [rain] 0..1, [sky] the glass's light.
     */
    const val GLASS = """
uniform float time;
uniform float rain;
uniform float px;
uniform float3 sky;
$HASH
half4 main(float2 p) {
    float2 s = floor(p / px);
    float a = 0.0;
    float3 col = float3(0.0);
    // beads sitting on the glass: one per 5x5 cell, more of them the harder it rains
    float2 cell = floor(s / 5.0);
    float r = h(cell);
    if (r < rain * 0.6) {
        float2 c = cell * 5.0 + floor(float2(h(cell + 3.1), h(cell + 7.7)) * 3.0) + 1.0;
        float2 d = s - c;
        float big = r < rain * 0.22 ? 1.0 : 0.0;
        if (d.x >= 0.0 && d.x <= big && d.y >= 0.0 && d.y <= big) { col = min(sky * 1.3 + 0.18, float3(1.0)); a = 0.6; }
        else if (d.y == big + 1.0 && d.x >= 0.0 && d.x <= big) { col = float3(0.04, 0.06, 0.1); a = 0.4; }
    }
    // drops running down: some columns, each with a drop sliding at its own pace and a trail above it
    float cx = floor(s.x / 7.0);
    if (h(float2(cx, 9.0)) < rain * 0.55) {
        float x0 = cx * 7.0 + floor(h(float2(cx, 2.0)) * 6.0);
        if (s.x == x0) {
            float speed = 5.0 + 9.0 * h(float2(cx, 4.0));
            float period = 46.0;
            float head = fract(time * speed / period + h(float2(cx, 5.0))) * period;
            float dy = head - mod(s.y, period);
            if (dy >= 0.0 && dy < 1.0) { col = min(sky * 1.35 + 0.22, float3(1.0)); a = 0.7; }
            else if (dy >= 1.0 && dy < 8.0) { col = min(sky * 1.1 + 0.12, float3(1.0)); a = 0.3 * (1.0 - (dy - 1.0) / 7.0); }
        }
    }
    return half4(half3(col * a), half(a));
}
"""

    /**
     * Rings where the rain hits the wet ground (squashed by the pavement's perspective), thicker in the puddles, which
     * hold the sky. [wet] how wet the street is, [sky] the sky's colour, [depthPx] the ground's depth in sprite pixels.
     */
    const val GROUND = """
uniform float time;
uniform float rain;
uniform float wet;
uniform float px;
uniform float3 sky;
$HASH
float noise(float2 x) {
    float2 i = floor(x); float2 f = fract(x); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), f.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), f.x), f.y);
}
half4 main(float2 p) {
    float2 s = floor(p / px);
    // puddles: low spots in a wide, flat pattern (pools spread along the street more than across it)
    float n = noise(s * float2(1.0 / 38.0, 1.0 / 9.0)) * 0.7 + noise(s * float2(1.0 / 11.0, 1.0 / 4.0)) * 0.3;
    float pool = smoothstep(1.0 - wet * 0.42, 1.0 - wet * 0.42 + 0.06, n);
    float3 col = min(sky * 1.15 + 0.12, float3(1.0));
    float a = pool * 0.42;
    // rings: one per cell, growing and fading; more and brighter in the pools
    float2 cell = floor(s / float2(9.0, 5.0));
    float r = h(cell);
    if (r < rain * (0.35 + 0.5 * pool)) {
        float2 c = cell * float2(9.0, 5.0) + float2(h(cell + 1.3) * 8.0, h(cell + 2.9) * 4.0);
        float t = fract(time * (0.9 + 0.8 * h(cell + 5.1)) + r * 7.0);
        float2 d = (s - c) * float2(1.0, 2.6);
        float ring = abs(length(d) - t * 4.5);
        if (ring < 0.75) {
            float k = (1.0 - t) * (0.6 + 0.35 * pool) * rain;
            col = mix(col, min(sky * 1.25 + 0.25, float3(1.0)), k / max(a + k, 0.001));
            a = max(a, k);
        }
    }
    return half4(half3(col * a), half(a));
}
"""
}
