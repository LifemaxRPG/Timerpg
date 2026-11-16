package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.skyPhase
import com.lifemax.rpg.feature.habits.domain.skyTone

/*
 * The street is a picture, not a page: its sky, its buildings and its lights have colours of their own, the same
 * in the light and the dark theme (brick is brick at any hour), as the cat's room has. Everything around the
 * picture (the button, the sheets, the toast) takes the page's palette tokens. The sky's colours blend from one
 * time of day to the next ([skyTone]), so dusk comes on slowly.
 */

/** The world at one moment of the day. */
internal class SkyColors(
    val sky: List<Color>,
    val ground: List<Color>,
    /** The pavement in front of the buildings, its kerb, the road beyond it. */
    val pavement: Color,
    val kerb: Color,
    val road: Color,
    val line: Color,
    /** The far city's three rows, furthest first. */
    val far: List<Color>,
    /** The glow over the horizon: the city's light at night, haze by day. */
    val glow: Color,
    val halo: Color,
    val orb: Color,
    val cloud: Color,
    /**
     * A cloud's lit top, its body and its shaded underside: warm and rosy at dusk, silver-blue at
     * night.
     */
    val cloudLit: Color,
    val cloudBody: Color,
    val cloudShade: Color,
    /** Words drawn straight on the sky or the ground. */
    val ink: Color,
    val inkSoft: Color,
    /** How dark it is: 1 at night, about half at dusk, 0 by day (lamps, lit windows, the sign). */
    val dark: Float,
    val phase: SkyPhase,
)

private class Tone(
    val sky: List<Color>,
    val ground: List<Color>,
    val pavement: Color,
    val kerb: Color,
    val road: Color,
    val line: Color,
    val far: List<Color>,
    val glow: Color,
    val halo: Color,
    val orb: Color,
    val cloud: Color,
    val cloudLit: Color,
    val cloudBody: Color,
    val cloudShade: Color,
    val ink: Color,
    val inkSoft: Color,
    val dark: Float,
)

private val NIGHT =
    Tone(
        sky = listOf(Color(0xFF050915), Color(0xFF0B1330), Color(0xFF1F2C5E)),
        ground = listOf(Color(0xFF151B33), Color(0xFF080B17)),
        pavement = Color(0xFF2A3150),
        kerb = Color(0xFF3B4466),
        road = Color(0xFF12162A),
        line = Color(0x338FA5FF),
        far = listOf(Color(0xFF0F1734), Color(0xFF111A3A), Color(0xFF141E42)),
        glow = Color(0x66FF9E5E),
        halo = Color(0x2EE9EDFF),
        orb = Color(0xFFE9EDFF),
        cloud = Color(0xFF1E2640),
        cloudLit = Color(0xFF6676A2),
        cloudBody = Color(0xFF29324F),
        cloudShade = Color(0xFF10162C),
        ink = Color(0xFFE6ECFF),
        inkSoft = Color(0xFF9AA8D8),
        dark = 1f,
    )

private val DUSK =
    Tone(
        sky = listOf(Color(0xFF1D2350), Color(0xFF6B4B7C), Color(0xFFE39A74)),
        ground = listOf(Color(0xFF45345E), Color(0xFF221A36)),
        pavement = Color(0xFF6A5578),
        kerb = Color(0xFF85709A),
        road = Color(0xFF2C2240),
        line = Color(0x3DFFD6BE),
        far = listOf(Color(0xFF6E5381), Color(0xFF5B4473), Color(0xFF4A3763)),
        glow = Color(0x80FFB27A),
        halo = Color(0x66FFC896),
        orb = Color(0xFFFFD9A8),
        cloud = Color(0xFFF2B9A0),
        cloudLit = Color(0xFFFFE0C2),
        cloudBody = Color(0xFFF0A58E),
        cloudShade = Color(0xFF8C5A86),
        ink = Color(0xFFFFF1E6),
        inkSoft = Color(0xFFE3C4BC),
        dark = .55f,
    )

private val DAY =
    Tone(
        sky = listOf(Color(0xFF2F66B8), Color(0xFF6FA5DD), Color(0xFFC4E2F5)),
        ground = listOf(Color(0xFF9AA7B8), Color(0xFF6E7C90)),
        pavement = Color(0xFFC9CFD8),
        kerb = Color(0xFFE4E8EE),
        road = Color(0xFF59616F),
        line = Color(0x33283C6E),
        far = listOf(Color(0xFFB4CDEA), Color(0xFF9DBDE2), Color(0xFF86ADD9)),
        glow = Color(0x80FFFFFF),
        halo = Color(0x66FFF3C4),
        orb = Color(0xFFFFF3C4),
        cloud = Color(0xFFFFFFFF),
        cloudLit = Color(0xFFFFFFFF),
        cloudBody = Color(0xFFF3F7FC),
        cloudShade = Color(0xFFA9BCD8),
        ink = Color(0xFFFFFFFF),
        inkSoft = Color(0xFFE3EEFA),
        dark = 0f,
    )

private fun mix(a: Tone, b: Tone, t: Float, phase: SkyPhase): SkyColors {
    fun c(x: Color, y: Color) = lerp(x, y, t)
    fun l(x: List<Color>, y: List<Color>) = x.indices.map { lerp(x[it], y[it], t) }
    return SkyColors(
        l(a.sky, b.sky),
        l(a.ground, b.ground),
        c(a.pavement, b.pavement),
        c(a.kerb, b.kerb),
        c(a.road, b.road),
        c(a.line, b.line),
        l(a.far, b.far),
        c(a.glow, b.glow),
        c(a.halo, b.halo),
        c(a.orb, b.orb),
        c(a.cloud, b.cloud),
        c(a.cloudLit, b.cloudLit),
        c(a.cloudBody, b.cloudBody),
        c(a.cloudShade, b.cloudShade),
        if (t < .5f) a.ink else b.ink,
        if (t < .5f) a.inkSoft else b.inkSoft,
        a.dark + (b.dark - a.dark) * t,
        phase,
    )
}

/** The sky at [hour], blended between night, dusk and day. */
internal fun skyColors(hour: Float): SkyColors {
    val tone = skyTone(hour)
    val phase = skyPhase(hour)
    return if (tone <= 1f) mix(NIGHT, DUSK, tone, phase) else mix(DUSK, DAY, tone - 1f, phase)
}

/**
 * A floor's front by its material: the wall from [wallTop] down to [wallBottom], its shaded [side],
 * its [roof], the [trim] round its windows and along its cornice, the [plaque] its name is on and
 * the plaque's [ink].
 */
internal class Facade(
    val wallTop: Color,
    val wallBottom: Color,
    val side: Color,
    val roof: Color,
    val trim: Color,
    val plaque: Color,
    val ink: Color,
    val inkSoft: Color,
    /** The material's own colour: the glow of the next floor. */
    val tint: Color,
)

internal fun facade(material: HabitMaterial): Facade =
    when (material) {
        // a bare steel frame filled in with glass, scaffolding still up
        HabitMaterial.GLASS ->
            Facade(
                Color(0xFF5A6E8C),
                Color(0xFF465772),
                Color(0xFF34425A),
                Color(0xFF8395B2),
                Color(0xFF9FB3CF),
                Color(0xF2EAF3FF),
                Color(0xFF1D2B44),
                Color(0xCC1D2B44),
                Color(0xFF9FD8FF),
            )
        // brick
        HabitMaterial.CLAY ->
            Facade(
                Color(0xFFBE6844),
                Color(0xFF9C4E31),
                Color(0xFF6E3524),
                Color(0xFFD08A68),
                Color(0xFFEEDCC8),
                Color(0xF23A1A0B),
                Color(0xFFFFEBD9),
                Color(0xCCFFDCC4),
                Color(0xFFFFA57A),
            )
        // cut stone
        HabitMaterial.STONE ->
            Facade(
                Color(0xFFDCD3C0),
                Color(0xFFBDB29C),
                Color(0xFF8F846F),
                Color(0xFFECE6D8),
                Color(0xFFF5F1E8),
                Color(0xF22B3045),
                Color(0xFFF2F4FA),
                Color(0xCCDDE2F0),
                Color(0xFFC9D6F0),
            )
        // stone, trimmed in gold
        HabitMaterial.GOLD ->
            Facade(
                Color(0xFFEDE3CC),
                Color(0xFFCDBD9C),
                Color(0xFF9A8865),
                Color(0xFFF6EED8),
                Color(0xFFE8B84A),
                Color(0xF2402A00),
                Color(0xFFFFE9A8),
                Color(0xCCFFE3A0),
                Color(0xFFF3CF63),
            )
        // run down
        HabitMaterial.FADE ->
            Facade(
                Color(0xFF6E5C60),
                Color(0xFF524549),
                Color(0xFF3B3134),
                Color(0xFF85747A),
                Color(0xFF8A7A7E),
                Color(0xF2251A1E),
                Color(0xFFFFE9F1),
                Color(0xCCFFDCE8),
                Color(0xFFE08AAE),
            )
    }

/** The awnings a lobby may have: one per building, by its foundation. */
internal val AWNINGS =
    arrayOf(
        Color(0xFFC8473F),
        Color(0xFF2F8A5F),
        Color(0xFF2E4A7D),
        Color(0xFF2A8C8C),
        Color(0xFFD49A2A),
        Color(0xFF7B3F7A),
    )

internal object SceneInk {
    val LobbyWall = Color(0xFF3A3F52)
    val LobbyWallLow = Color(0xFF2C3040)
    val LobbySide = Color(0xFF232634)
    val SignBoard = Color(0xFF1B1F2C)

    /** The glass labels on each floor: a dark translucent panel, a fine bright rim, light type. */
    val GlassPanel = Color(0xFF0C1426)
    val GlassRim = Color(0xFFE6EEFF)
    val GlassInk = Color(0xFFF6F8FF)
    val GlassInkSoft = Color(0xFFB4C0DA)
    val SignText = Color(0xFFFFF1D6)
    val Shop = Color(0xFF2A3348)
    val ShopLit = Color(0xFFFFD48A)
    val Door = Color(0xFF151925)

    /**
     * The lobby's reveals (its granite seen in depth), the shopfront's dark bronze frame and
     * glazing bars, the pale stone step at its door.
     */
    val LobbyReveal = Color(0xFF262A38)
    val ShopFrame = Color(0xFF2E2B2A)
    val Step = Color(0xFF777B88)

    /** Windows: lit (warm), and dark glass at night and by day. */
    val WindowLit = Color(0xFFFFD27A)
    val WindowLitLow = Color(0xFFFFB45A)
    val WindowLitDay = Color(0xFFFFE7B0)
    val GlassNight = Color(0xFF151C30)
    val GlassDay = Color(0xFF58728F)
    val Person = Color(0xE6331F17)
    val Boards = Color(0xFF8E6E4A)
    val BoardLine = Color(0xFF5E4630)
    val Dim = Color(0xFF6B4A32)

    val Scaffold = Color(0xFFB9C3D3)
    val Crane = Color(0xFFF2B33D)

    /** The next floor's glow, and the light that floods a floor as it is finished. */
    val Glow = Color(0xFFFFE2A0)

    /** The rooftop sign: dark until the building is done, then lit. */
    val SignOff = Color(0xFF2B2F3D)
    val SignOffText = Color(0xFF8D93A8)
    val SignLit = Color(0xFF3A2400)
    val SignLitText = Color(0xFFFFE08A)
    val Bulb = Color(0xFFFFF0B8)
    val Flag = Color(0xFFFF6B6B)
    val FlagPole = Color(0xFFFFF7D6)
    val Tank = Color(0xFF5A4A3E)

    /**
     * The facades' ironwork (pipes, railings, the sign's frame) and its highlight, an air
     * conditioner's box and its grille, a spray-can tag, the lobby sign's raised frame and its neon
     * tube, a door's brass handle.
     */
    val Iron = Color(0xFF2A2F3D)
    val IronLight = Color(0xFF8C94A4)
    val Box = Color(0xFFA6ABB4)
    val BoxDark = Color(0xFF3C414C)
    val Spray = Color(0xFFE05A8A)
    val SignFrame = Color(0xFF5A6478)
    val Tube = Color(0xFFFFF1D6)
    val Brass = Color(0xFFE8B84A)

    /** A ghost floor: where the next habit would go. */
    val PlanFill = Color(0x1F8CA8FF)
    val PlanLine = Color(0x99B4C6FF)
    val PlanText = Color(0xFFDCE6FF)

    val TreeTrunk = Color(0xFF6B4A2F)
    val TreeA = Color(0xFF3F9D6B)
    val TreeB = Color(0xFF348A5C)
    val TreeC = Color(0xFF46AD77)
    val Grass = Color(0xFF4F8F5E)

    /** A tree's leaves in four tones (with an olive and a night version), and its bark. */
    val LeafDeep = Color(0xFF1F5C3E)
    val LeafShade = Color(0xFF2A7A4E)
    val LeafMid = Color(0xFF3C9A66)
    val LeafLight = Color(0xFF74C484)
    val LeafGlint = Color(0xFFB4E59A)
    val LeafOlive = Color(0xFF6C9A3E)
    val LeafDeepNight = Color(0xFF08161F)
    val LeafShadeNight = Color(0xFF0F2A30)
    val LeafMidNight = Color(0xFF18413F)
    val LeafLightNight = Color(0xFF3A6E78)
    val LeafGlintNight = Color(0xFF6C9CB0)
    val BarkDark = Color(0xFF4A3322)
    val BarkLit = Color(0xFF7A5A3C)
    val BarkDarkNight = Color(0xFF1E1822)
    val BarkLitNight = Color(0xFF3A3446)
    val Lamp = Color(0xFF2A2F3D)
    val LampLight = Color(0xFFFFE3A3)
    val Bench = Color(0xFF6B4A2F)

    val Shadow = Color(0x59000000)
    val Occlusion = Color(0x47000000)
    val Highlight = Color(0x59FFFFFF)
    val Star = Color(0xFFD5DDFF)
    val FarLight = Color(0xFFFFD27A)
    val FarLightPale = Color(0xFFFFF1CF)
    val FarLightCool = Color(0xFFBFDDFF)
    val Crater = Color(0xFF7F8BB5)
    val Rays = Color(0xFFFFF0B8)
    val Warning = Color(0xFFFF3B30)
    val NavGreen = Color(0xFF4CFF8A)
    val Strobe = Color(0xFFFFFFFF)
    val Plane = Color(0xFF2B3040)
    val PlaneDay = Color(0xFFE9EEF6)
    val Contrail = Color(0xFFFFFFFF)
    val Bird = Color(0xFF242838)
    val BirdDusk = Color(0xFF2A1E2C)
    val Blimp = Color(0xFFDCE2EC)
    val BlimpShade = Color(0xFF8D98B0)
    val BlimpLivery = Color(0xFFC8463D)
    val Headlight = Color(0xFFFFF4D6)
    val Taillight = Color(0xFFFF4D4D)
    val Dust = Color(0xFFE8EDFF)
    val Sheen = Color(0xD9FFFFFF)
}
