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
     * At night on the wet ground: each street lamp's light mirrored as a long warm streak down the pavement and road, broken
     * up by the wet surface (and the rain's ripples), as wet streets catch lamps. [lampsA], [lampsB]: up to eight lenses' x
     * (street units; far away when unused), [night] 0..1, [wet] 0..1, [warm] the lamps' colour.
     */
    const val SHEEN = """
uniform float time;
uniform float night;
uniform float wet;
uniform float rain;
uniform float px;
uniform float4 lampsA;
uniform float4 lampsB;
uniform float3 warm;
$HASH
float streak(float x, float lx, float y, float row) {
    float d = abs(x - lx);
    // wider further down (nearer us), broken into short bright dashes by the wet surface
    float w = 3.0 + y * 0.08;
    float core = smoothstep(w, 0.0, d);
    float broken = step(0.35, h(float2(floor(y / 2.0) + row, floor(lx))) * 0.6 + 0.4 * sin(y * 0.9 + time * (1.0 + rain * 3.0) + lx));
    return core * broken * exp(-y * 0.025);
}
half4 main(float2 p) {
    float2 s = floor(p / px);
    float y = s.y;
    float k = 0.0;
    k = max(k, streak(s.x, lampsA.x / px, y, 1.0));
    k = max(k, streak(s.x, lampsA.y / px, y, 2.0));
    k = max(k, streak(s.x, lampsA.z / px, y, 3.0));
    k = max(k, streak(s.x, lampsA.w / px, y, 4.0));
    k = max(k, streak(s.x, lampsB.x / px, y, 5.0));
    k = max(k, streak(s.x, lampsB.y / px, y, 6.0));
    k = max(k, streak(s.x, lampsB.z / px, y, 7.0));
    k = max(k, streak(s.x, lampsB.w / px, y, 8.0));
    float a = clamp(k * night * wet * 1.1, 0.0, 0.9);
    return half4(half3(warm * a), half(a));
}
"""

    /**
     * Fog that drifts: soft banks moving slowly along the street, thickest low down. Screen coordinates; [offset] the
     * view's place along the street in screen pixels (so the banks stay on the street as it scrolls), [ground] the
     * pavement's line, [mist] 0..1, [tint] the fog's colour.
     */
    const val FOG = """
uniform float time;
uniform float mist;
uniform float offset;
uniform float ground;
uniform float top;
uniform float3 tint;
$HASH
float noise(float2 x) {
    float2 i = floor(x); float2 f = fract(x); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), f.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), f.x), f.y);
}
half4 main(float2 p) {
    float2 q = float2((p.x + offset) / 260.0 + time * 0.035, p.y / 140.0);
    float n = noise(q) * 0.55 + noise(q * 2.1 + float2(time * 0.05, 0.0)) * 0.3 + noise(q * 4.3) * 0.15;
    // thick near the ground, thinning upwards; the banks
    float low = clamp((p.y - top) / max(ground - top, 1.0), 0.0, 1.0);
    float a = mist * (0.06 + 0.2 * low) * (0.4 + 1.1 * n);
    a = clamp(a, 0.0, 0.38);
    return half4(half3(tint * a), half(a));
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
