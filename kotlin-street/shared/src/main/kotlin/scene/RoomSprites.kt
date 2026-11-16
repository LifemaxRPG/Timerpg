package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.PeopleActivities
import com.lifemax.rpg.feature.habits.domain.RoomKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A room as the layers drawn into a lit window, back to front: [base] is the room with its lamp off
 * (walls, floor, furniture, in the dim light from outside), [lamp] the warm light its own lamp adds
 * when it is on (drawn additively, as strong as the lamp is), [screen] the cool light of a
 * television or a monitor when there is one (additive too, as strong as the set's picture), [front]
 * what stands between the person and the glass (a desk, a counter, a table). The person goes
 * between [lamp] and [screen], so the set's light falls on them. The lamp and screen layers are
 * soft, so they are painted at half the resolution; [front], [lamp] and [screen] are cut to the
 * pixels they use, their origin kept at the window's top left ([Sprite.ox], [Sprite.oy]).
 */
internal class Room(val base: Sprite, val lamp: Sprite, val screen: Sprite?, val front: Sprite)

/**
 * The rooms behind the windows, painted once each (per kind, variant and window height) as the
 * layers of [Room]: a back wall with its paint, paper, tiles or brick, side walls for depth, a
 * floor in perspective with a rug, a skirting board, the furniture of the room's kind in two or
 * three layouts and colour schemes, small things (books, mugs, pictures, a clock, plants), and the
 * room's own light: a pendant, a floor lamp or a desk lamp whose warm pool falls down the wall and
 * across the floor, consistent with the rim light baked into the figures (from above, a little to
 * the left). Pure Kotlin, drawn like the figures, so it can be looked at on any JVM.
 */
internal object RoomSprites {
    /**
     * Pixels to a unit for the room and its furniture: a touch over a phone's at the focus zoom.
     */
    const val PPU = 3.2f

    /** Pixels to a unit for the soft light layers. */
    const val LIGHT_PPU = 1.6f

    /** A window's width in units, what the rooms are painted for. */
    const val WIN_W = 17f

    /**
     * How deep the floor is, in the rig's units (scaled with the furniture): the back wall meets
     * the floor this far above the window's bottom, where the people stand.
     */
    const val BACK = 3.8f

    /** How many layouts and colour schemes every kind has. */
    const val VARIANTS = 3

    /** How big a person is in a window [h] tall: whole in a tall one, smaller in a short one. */
    fun personScale(h: Float): Float = ((h - 2.5f) / 26f).coerceIn(.62f, 1f)

    /** Where the back wall meets the floor in a window [h] tall, in units from its top. */
    fun floorLine(h: Float, kind: RoomKind): Float =
        h - BACK * personScale(h) * PeopleActivities.roomFit(kind)

    /**
     * The most one room's layers take for a window [h] tall, in bytes (before the front and the
     * lights are cut down).
     */
    fun bytes(h: Float): Int {
        val big = ceil(WIN_W * PPU).toInt() * ceil(h * PPU).toInt() * 4
        val small = ceil(WIN_W * LIGHT_PPU).toInt() * ceil(h * LIGHT_PPU).toInt() * 4
        return big * 2 + small * 2
    }

    /** Whether rooms of [kind] have a screen (a monitor, a television) that lights them. */
    fun hasScreen(kind: RoomKind): Boolean = kind == RoomKind.OFFICE || kind == RoomKind.LIVING

    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000
    private const val WARM = 0xFFE0AC
    private const val COOL = 0xA8C8FF
    private const val PAPER = 0xF2E8D2
    private const val GLASS = 0xBFD4E8
    private const val LEAF = 0x3E5A26
    private const val LEAF2 = 0x6A8A36
    private const val POT = 0x8A4A2C
    private const val DARKWOOD = 0x3A2314
    private const val OAK = 0x8A5A32
    private const val PINE = 0xB8865A
    private const val METAL = 0x6A7080
    private const val SCREEN_OFF = 0x1A2028

    /** How a texture is painted on the back wall. */
    private enum class Tex {
        PAINT,
        PAPER,
        TILE,
        BRICK,
        PANEL,
    }

    /** The kind of lamp a room has. */
    private enum class Lamp {
        PENDANT,
        FLOOR,
        DESK,
        SCONCE,
    }

    /** One variant's colours and lamp. */
    private class Scheme(
        val wall: Int,
        val wall2: Int,
        val tex: Tex,
        val floor: Int,
        val rug: Int,
        val rug2: Int,
        val wood: Int,
        val accent: Int,
        val lamp: Lamp,
        /** Where the lamp hangs or stands, in the window's units from its middle. */
        val lampX: Float,
    )

    private fun scheme(kind: RoomKind, v: Int): Scheme =
        when (kind) {
            RoomKind.OFFICE ->
                arrayOf(
                    Scheme(
                        0xB9BFC4,
                        0xA7AEB4,
                        Tex.PAINT,
                        0x5A5F66,
                        0x3F4A5C,
                        0x34404F,
                        DARKWOOD,
                        0x4A7AB8,
                        Lamp.PENDANT,
                        -1f,
                    ),
                    Scheme(
                        0xD9C9A8,
                        0xC9B890,
                        Tex.PAINT,
                        0x7A5A3A,
                        0x8A3A2E,
                        0x6E2E24,
                        OAK,
                        0x3C8C6E,
                        Lamp.DESK,
                        5.4f,
                    ),
                    Scheme(
                        0x8A9AA8,
                        0x7A8A98,
                        Tex.PANEL,
                        0x4A4A4E,
                        0x5A4A3A,
                        0x4A3A2C,
                        DARKWOOD,
                        0xD88A3C,
                        Lamp.PENDANT,
                        1f,
                    ),
                )[v]
            RoomKind.DESK ->
                arrayOf(
                    Scheme(
                        0xE3D5B8,
                        0xD3C5A8,
                        Tex.PAPER,
                        0x8A6A44,
                        0x6A4A6A,
                        0x563C56,
                        OAK,
                        0xC8603C,
                        Lamp.DESK,
                        5.6f,
                    ),
                    Scheme(
                        0xAFC4C9,
                        0x9FB4B9,
                        Tex.PAINT,
                        0x6A5A4A,
                        0x3A6A6A,
                        0x2E5858,
                        PINE,
                        0xE8A040,
                        Lamp.DESK,
                        6f,
                    ),
                    Scheme(
                        0xD8B8A8,
                        0xC8A898,
                        Tex.PAINT,
                        0x5A4A3A,
                        0x8A6A3A,
                        0x745830,
                        DARKWOOD,
                        0x4A8AB8,
                        Lamp.PENDANT,
                        -2f,
                    ),
                )[v]
            RoomKind.STUDY ->
                arrayOf(
                    Scheme(
                        0x7A3A2E,
                        0x6A3026,
                        Tex.PAINT,
                        0x4A3222,
                        0x7A5A2A,
                        0x604618,
                        DARKWOOD,
                        0xC8A050,
                        Lamp.FLOOR,
                        -7.6f,
                    ),
                    Scheme(
                        0x4E6A58,
                        0x40584A,
                        Tex.PANEL,
                        0x5A4A3A,
                        0x8A3A2E,
                        0x6E2E24,
                        OAK,
                        0xE0B870,
                        Lamp.FLOOR,
                        -7.6f,
                    ),
                    Scheme(
                        0xCDBFA5,
                        0xBDAF95,
                        Tex.PAPER,
                        0x6A5A4A,
                        0x4A5A7A,
                        0x3A4A68,
                        DARKWOOD,
                        0x9A4A3A,
                        Lamp.FLOOR,
                        -7.6f,
                    ),
                )[v]
            RoomKind.GYM ->
                arrayOf(
                    Scheme(
                        0x58606A,
                        0x4A525C,
                        Tex.PAINT,
                        0x3A3A3E,
                        0x2E4A6A,
                        0x263E58,
                        METAL,
                        0xE85A3C,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0x8A8E92,
                        0x7A7E82,
                        Tex.BRICK,
                        0x4A4034,
                        0x4A6A3A,
                        0x3E5830,
                        METAL,
                        0x40A0C8,
                        Lamp.PENDANT,
                        -2f,
                    ),
                    Scheme(
                        0x3A3E48,
                        0x30343E,
                        Tex.PAINT,
                        0x2E2E34,
                        0x6A2E2E,
                        0x582626,
                        METAL,
                        0xF0C040,
                        Lamp.SCONCE,
                        -6.5f,
                    ),
                )[v]
            RoomKind.YOGA ->
                arrayOf(
                    Scheme(
                        0xE8DCC8,
                        0xD8CCB8,
                        Tex.PAINT,
                        0xB08A5A,
                        0x7A4A7A,
                        0x663C66,
                        PINE,
                        0x8AB08A,
                        Lamp.FLOOR,
                        -5.5f,
                    ),
                    Scheme(
                        0xC8D8C8,
                        0xB8C8B8,
                        Tex.PAINT,
                        0x9A7A52,
                        0x4A7A8A,
                        0x3C6676,
                        PINE,
                        0xE0A070,
                        Lamp.PENDANT,
                        -1f,
                    ),
                    Scheme(
                        0xE2C8B0,
                        0xD2B8A0,
                        Tex.PAINT,
                        0xA8805A,
                        0xA06040,
                        0x885034,
                        OAK,
                        0x60A090,
                        Lamp.FLOOR,
                        -5f,
                    ),
                )[v]
            RoomKind.KITCHEN ->
                arrayOf(
                    Scheme(
                        0xE8E4D8,
                        0xC8C4B8,
                        Tex.TILE,
                        0x7A6A5A,
                        0x6A4A3A,
                        0x5A3E30,
                        OAK,
                        0x4A8A9A,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0x9AB8B0,
                        0x7A9890,
                        Tex.TILE,
                        0x5A5A5A,
                        0x8A6A3A,
                        0x745830,
                        PINE,
                        0xE06A3C,
                        Lamp.PENDANT,
                        -2f,
                    ),
                    Scheme(
                        0xE6D2B4,
                        0xC6B294,
                        Tex.BRICK,
                        0x6A5A4A,
                        0x4A5A6A,
                        0x3C4C5C,
                        DARKWOOD,
                        0xC8A040,
                        Lamp.PENDANT,
                        1f,
                    ),
                )[v]
            RoomKind.BATH ->
                arrayOf(
                    Scheme(
                        0xD8E8EE,
                        0xB8C8CE,
                        Tex.TILE,
                        0x8A9AA2,
                        0x5A8AA8,
                        0x4C7890,
                        METAL,
                        0xE8C8A0,
                        Lamp.SCONCE,
                        0f,
                    ),
                    Scheme(
                        0xE8E0D0,
                        0xC8C0B0,
                        Tex.TILE,
                        0x6A6A6E,
                        0x8A6A5A,
                        0x76584A,
                        DARKWOOD,
                        0x60A8A0,
                        Lamp.SCONCE,
                        -1f,
                    ),
                    Scheme(
                        0x9AB8C8,
                        0x7A98A8,
                        Tex.TILE,
                        0x4A5A6A,
                        0x3A4A5A,
                        0x2E3C4A,
                        OAK,
                        0xF0E0C0,
                        Lamp.PENDANT,
                        0f,
                    ),
                )[v]
            RoomKind.BEDROOM ->
                arrayOf(
                    Scheme(
                        0xC8B8D8,
                        0xB8A8C8,
                        Tex.PAPER,
                        0x7A6A5A,
                        0x5A4A7A,
                        0x4C3E68,
                        DARKWOOD,
                        0xE0A0A0,
                        Lamp.SCONCE,
                        3f,
                    ),
                    Scheme(
                        0xE0CFB8,
                        0xD0BFA8,
                        Tex.PAINT,
                        0x8A6A4A,
                        0x8A4A4A,
                        0x763E3E,
                        OAK,
                        0x5A8AB0,
                        Lamp.PENDANT,
                        -1f,
                    ),
                    Scheme(
                        0x7A8AA0,
                        0x6A7A90,
                        Tex.PAINT,
                        0x4A4A52,
                        0x8A7A5A,
                        0x76684A,
                        PINE,
                        0xE8C080,
                        Lamp.SCONCE,
                        3f,
                    ),
                )[v]
            RoomKind.LIVING ->
                arrayOf(
                    Scheme(
                        0xD8CBB4,
                        0xC8BBA4,
                        Tex.PAINT,
                        0x7A5A3E,
                        0x8A3A34,
                        0x74302C,
                        OAK,
                        0x4A8A8A,
                        Lamp.FLOOR,
                        -7.6f,
                    ),
                    Scheme(
                        0x8AA090,
                        0x7A9080,
                        Tex.PAINT,
                        0x5A4A3A,
                        0xB08050,
                        0x946A42,
                        DARKWOOD,
                        0xE07050,
                        Lamp.PENDANT,
                        -2f,
                    ),
                    Scheme(
                        0xB8B8C8,
                        0xA8A8B8,
                        Tex.PAPER,
                        0x4A4A4E,
                        0x3A4A6A,
                        0x2E3C58,
                        PINE,
                        0xE8B050,
                        Lamp.FLOOR,
                        -7.6f,
                    ),
                )[v]
            RoomKind.MUSIC ->
                arrayOf(
                    Scheme(
                        0x5A4A5A,
                        0x4A3A4A,
                        Tex.PAINT,
                        0x4A3A2E,
                        0x8A3A5A,
                        0x74304C,
                        DARKWOOD,
                        0xE8A040,
                        Lamp.PENDANT,
                        -3f,
                    ),
                    Scheme(
                        0x8A5A3E,
                        0x7A4A32,
                        Tex.BRICK,
                        0x3A3A3E,
                        0x3A5A5A,
                        0x2E4A4A,
                        DARKWOOD,
                        0x40B0C8,
                        Lamp.PENDANT,
                        2f,
                    ),
                    Scheme(
                        0x3E4A5A,
                        0x323E4E,
                        Tex.PAINT,
                        0x5A4A3A,
                        0x6A4A2E,
                        0x583C24,
                        OAK,
                        0xF06040,
                        Lamp.SCONCE,
                        -7f,
                    ),
                )[v]
            RoomKind.PIANO ->
                arrayOf(
                    Scheme(
                        0xE0D0B8,
                        0xD0C0A8,
                        Tex.PAPER,
                        0x6A4A32,
                        0x8A5A3A,
                        0x764A30,
                        DARKWOOD,
                        0xA04040,
                        Lamp.PENDANT,
                        -2f,
                    ),
                    Scheme(
                        0x6A4A4A,
                        0x5A3A3A,
                        Tex.PAINT,
                        0x4A3A2E,
                        0x5A5A3A,
                        0x4A4A30,
                        DARKWOOD,
                        0xE0C080,
                        Lamp.PENDANT,
                        -3f,
                    ),
                    Scheme(
                        0xB8C8C0,
                        0xA8B8B0,
                        Tex.PAINT,
                        0x5A4A42,
                        0x4A4A6A,
                        0x3C3C58,
                        OAK,
                        0xD08040,
                        Lamp.PENDANT,
                        -1f,
                    ),
                )[v]
            RoomKind.PLANTS ->
                arrayOf(
                    Scheme(
                        0xE0E0D0,
                        0xD0D0C0,
                        Tex.PAINT,
                        0x8A7A5A,
                        0x6A7A4A,
                        0x58683C,
                        PINE,
                        0xC06040,
                        Lamp.PENDANT,
                        -1f,
                    ),
                    Scheme(
                        0xC8D0C0,
                        0xB8C0B0,
                        Tex.BRICK,
                        0x6A5A4A,
                        0x4A6A6A,
                        0x3C5858,
                        OAK,
                        0xE0A060,
                        Lamp.PENDANT,
                        1f,
                    ),
                    Scheme(
                        0xE8D8C0,
                        0xD8C8B0,
                        Tex.PAINT,
                        0x7A6A52,
                        0x8A5A4A,
                        0x764A3C,
                        PINE,
                        0x5A9A8A,
                        Lamp.PENDANT,
                        2f,
                    ),
                )[v]
            RoomKind.BOARD ->
                arrayOf(
                    Scheme(
                        0xC8CDD2,
                        0xB8BDC2,
                        Tex.PAINT,
                        0x5A5A5E,
                        0x3A4A5A,
                        0x2E3C4A,
                        METAL,
                        0x4A8AD0,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0xE0D8C8,
                        0xD0C8B8,
                        Tex.PAINT,
                        0x6A5A4A,
                        0x5A6A5A,
                        0x4A5A4A,
                        OAK,
                        0xE05A3C,
                        Lamp.PENDANT,
                        -2f,
                    ),
                    Scheme(
                        0x6A7A8A,
                        0x5A6A7A,
                        Tex.PANEL,
                        0x3A3A3E,
                        0x4A4A5A,
                        0x3C3C4A,
                        DARKWOOD,
                        0xF0B040,
                        Lamp.PENDANT,
                        2f,
                    ),
                )[v]
            RoomKind.HALL ->
                arrayOf(
                    Scheme(
                        0xD8D0C0,
                        0xC8C0B0,
                        Tex.PAINT,
                        0x6A5A4A,
                        0x7A3A3A,
                        0x663030,
                        DARKWOOD,
                        0xC8A060,
                        Lamp.SCONCE,
                        -7f,
                    ),
                    Scheme(
                        0xA8B0B8,
                        0x98A0A8,
                        Tex.PANEL,
                        0x4A4A4E,
                        0x3A4A6A,
                        0x2E3C58,
                        OAK,
                        0xE08050,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0xE0D0C0,
                        0xD0C0B0,
                        Tex.PAPER,
                        0x8A6A4A,
                        0x5A5A3A,
                        0x4A4A30,
                        DARKWOOD,
                        0x6A8AB0,
                        Lamp.SCONCE,
                        6.8f,
                    ),
                )[v]
            RoomKind.DINING ->
                arrayOf(
                    Scheme(
                        0xD8C8B0,
                        0xC8B8A0,
                        Tex.PAINT,
                        0x7A5A3E,
                        0x6A3A3A,
                        0x583030,
                        DARKWOOD,
                        0xC8A050,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0x7A8A7A,
                        0x6A7A6A,
                        Tex.PANEL,
                        0x5A4A3A,
                        0x8A6A3A,
                        0x745830,
                        OAK,
                        0xE0A060,
                        Lamp.PENDANT,
                        0f,
                    ),
                    Scheme(
                        0xE8DCC8,
                        0xD8CCB8,
                        Tex.PAPER,
                        0x6A5A4A,
                        0x4A5A7A,
                        0x3C4A68,
                        PINE,
                        0xA04A3A,
                        Lamp.PENDANT,
                        0f,
                    ),
                )[v]
        }

    /** The room [kind] in its [variant] (0 up to [VARIANTS]) for a window [h] tall. */
    fun render(kind: RoomKind, variant: Int, h: Float): Room {
        val v = ((variant % VARIANTS) + VARIANTS) % VARIANTS
        val sc = scheme(kind, v)
        val c =
            Cv(h, PeopleActivities.shift(kind), personScale(h) * PeopleActivities.roomFit(kind), sc)
        c.architecture()
        c.layer = Cv.BASE
        furniture(c, kind, v)
        c.lampFixture()
        c.light()
        c.layer = Cv.FRONT
        front(c, kind, v)
        val base = c.out(Cv.BASE)
        val lamp = PeopleSprites.crop(c.out(Cv.LAMP))
        val front = PeopleSprites.crop(c.out(Cv.FRONT))
        val screen = if (hasScreen(kind)) PeopleSprites.crop(c.out(Cv.SCREEN)) else null
        return Room(base, lamp, screen, front)
    }

    // ------------------------------------------------------------------ the rooms

    private fun furniture(c: Cv, kind: RoomKind, v: Int) {
        val s = c.sc
        when (kind) {
            RoomKind.OFFICE -> {
                // the desk is in front; behind it a monitor on an arm, the chair, shelves
                c.chair(c.rig(-3.2f), s.wood)
                val mx = c.rig(8.2f)
                c.box(mx - 2.8f, -19.6f, mx + 2.8f, -15.6f, 0x22262C, 1f)
                c.rect(mx - 2.5f, -19.3f, mx + 2.5f, -15.9f, SCREEN_OFF, 1f)
                c.screen(mx - 2.5f, -19.3f, mx + 2.5f, -15.9f, .55f)
                c.rect(mx - .3f, -15.6f, mx + .3f, -10f, 0x22262C, 1f)
                c.rect(mx - 1.6f, -10.3f, mx + 1.6f, -9.8f, 0x22262C, 1f)
                if (v == 1) c.deskLampArm(s.lampX)
                if (v == 0) {
                    c.shelf(-8f, -22f, -1.5f)
                    c.books(-7.6f, -22f, 5, s.accent)
                    c.binder(-3f, -22f, 0x3A4A6A)
                    c.pinboard(2f, -26f, 7.5f, -21.5f)
                    c.clock(-5f, -27f)
                } else if (v == 1) {
                    c.shelf(-8f, -20f, -2f)
                    c.books(-7.6f, -20f, 4, s.accent)
                    c.plantPot(-7f, -c.backY(), 5, 2.6f)
                    c.picture(1f, -27f, 6f, -22.5f, s.accent)
                    c.mug(-4.4f, -20f, s.accent)
                } else {
                    c.bookcase(-8.4f, -27f, -2.2f, s.wood, s.accent)
                    c.pinboard(1.5f, -26f, 7f, -21f)
                    c.plantPot(7.4f, -c.backY(), 4, 2.2f)
                }
            }
            RoomKind.DESK -> {
                c.chair(c.rig(-3.2f), s.wood)
                if (v != 2) c.deskLampArm(s.lampX)
                c.rect(c.rig(6f), -10.2f, c.rig(8.4f), -9.6f, 0xE8E0D0, .9f)
                if (v == 0) {
                    c.picture(-7.5f, -26f, -2.5f, -21f, s.accent)
                    c.picture(-1.5f, -25f, 2.5f, -21.5f, s.rug)
                    c.shelf(3f, -24f, 8f)
                    c.books(3.4f, -24f, 4, s.rug)
                } else if (v == 1) {
                    c.poster(-8f, -27f, -3f, -20f, s.accent)
                    c.poster(-2.4f, -26f, 1.6f, -21f, s.rug)
                    c.plantPot(-7f, -c.backY(), 6, 3f)
                } else {
                    c.bookcase(-8.4f, -26f, -3f, s.wood, s.accent)
                    c.clock(5f, -25f)
                    c.shelf(1f, -21f, 7.5f)
                    c.books(1.4f, -21f, 5, s.accent)
                }
            }
            RoomKind.STUDY -> {
                // an armchair at the lamp, bookshelves
                c.armchair(c.rig(-7.6f), c.rig(2.6f), s.rug, s.rug2)
                c.sideTable(c.rig(4.4f), c.rig(7.4f), s.wood)
                c.mug(c.rig(5.4f), -7.4f, s.accent)
                if (v == 0) {
                    c.bookcase(-8.4f, -27f, -2f, s.wood, s.accent)
                    c.picture(1f, -26f, 6f, -21f, s.accent)
                } else if (v == 1) {
                    c.shelf(-8f, -24f, -1f)
                    c.books(-7.6f, -24f, 6, s.accent)
                    c.shelf(-8f, -19f, -1f)
                    c.books(-7.6f, -19f, 5, s.rug)
                    c.picture(1.5f, -27f, 5.5f, -22f, s.rug)
                    c.plantPot(7.4f, -c.backY(), 5, 2.4f)
                } else {
                    c.bookcase(-8.4f, -28f, -3.4f, s.wood, s.accent)
                    c.bookcase(-3f, -28f, 1.4f, s.wood, s.rug)
                    c.clock(4.5f, -25f)
                }
            }
            RoomKind.GYM -> {
                // a mirror wall, a rack of weights, a kettlebell, a mat
                c.mirror(-8f, -26f, -1f, -8f)
                c.mat(c.rig(-6f), c.rig(3f), s.rug)
                val rx = c.rig(5.6f)
                c.box(rx, -14f, rx + 3.8f, -13.3f, s.wood, 1f)
                c.box(rx, -7.8f, rx + 3.8f, -7.1f, s.wood, 1f)
                c.rect(rx, -14f, rx + .5f, 0f, s.wood, 1f)
                c.rect(rx + 3.3f, -14f, rx + 3.8f, 0f, s.wood, 1f)
                for (i in 0 until 3) {
                    c.ellipse(rx + .9f + i * 1f, -14.7f, .55f, .55f, 0x202428, 1f)
                    c.ellipse(rx + .9f + i * 1f, -8.5f, .65f, .65f, 0x202428, 1f)
                }
                c.kettlebell(c.rig(-7.4f), 0f, s.accent)
                if (v == 0) {
                    c.poster(1f, -27f, 6f, -20.5f, s.accent)
                    c.clock(-4.5f, -28.5f)
                } else if (v == 1) {
                    c.poster(.5f, -26f, 4.5f, -20f, s.rug)
                    c.plantPot(7.6f, -c.backY(), 4, 2f)
                } else {
                    c.poster(.5f, -27f, 7f, -20f, s.accent)
                    c.rect(1f, -18.5f, 7f, -18f, s.wood, 1f)
                    for (i in 0 until 4) c.capsule(
                        1.6f + i * 1.5f,
                        -18f,
                        .25f,
                        1.6f + i * 1.5f,
                        -15.6f,
                        .25f,
                        0x303438,
                        1f,
                    )
                }
            }
            RoomKind.YOGA -> {
                // a mat, a plant, a cushion, something on the wall
                c.mat(c.rig(-8f), c.rig(6.5f), s.rug)
                c.plantPot(c.rig(7.6f), 0f, 6, 2.4f)
                c.cushion(c.rig(-6.4f), 0f, s.accent)
                if (v == 0) {
                    c.hanging(-1f, -27f, 4f, -18f, s.accent)
                    c.shelf(5f, -24f, 8.3f)
                    c.candle(6f, -24f)
                    c.candle(7.2f, -24f)
                } else if (v == 1) {
                    c.picture(-6f, -26f, -1.5f, -22f, s.accent)
                    c.picture(-.5f, -26f, 4f, -22f, s.rug)
                    c.shelf(-8f, -19f, -3f)
                    c.candle(-7f, -19f)
                    c.plantSmall(-4.5f, -19f)
                } else {
                    c.hanging(-7f, -27f, -2f, -19f, s.rug)
                    c.hanging(-1f, -27f, 4f, -19f, s.accent)
                    c.clock(6.5f, -25f)
                }
            }
            RoomKind.KITCHEN -> {
                // wall cabinets, a hood over the hob, hanging pans, a shelf of jars
                val cab = if (v == 1) 0xE8E8E0 else s.wood
                c.cabinets(-8.4f, -28f, 8.4f, -20f, cab, v)
                val hx = c.rig(9f)
                c.poly(
                    floatArrayOf(hx - 3f, -17.5f, hx + 3f, -17.5f, hx + 2f, -20f, hx - 2f, -20f),
                    METAL,
                    1f,
                )
                c.rect(hx - 3f, -17.8f, hx + 3f, -17.5f, WHITE, .35f)
                for (i in 0 until 3) {
                    val px = -6.5f + i * 2.4f
                    c.capsule(px, -20f, .12f, px, -18.4f, .12f, 0x303030, 1f)
                    c.ellipse(px, -17.2f, 1f, 1.1f, if (i == 1) 0xC87A3A else 0x50545A, 1f)
                    c.ellipse(px - .3f, -17.5f, .35f, .4f, WHITE, .25f)
                }
                c.rect(-8f, -15.6f, 0f, -15.2f, s.wood, 1f)
                for (i in 0 until 4) c.jar(
                    -7.2f + i * 2f,
                    -15.6f,
                    if (i % 2 == 0) s.accent else 0xD8B870,
                )
                if (v == 2) c.clock(4f, -25.5f)
            }
            RoomKind.BATH -> {
                // tiles, a mirror cabinet, a towel on a rail, a bath at the back
                c.mirror(c.rig(1f), -22f, c.rig(10.4f), -12.4f)
                c.rect(c.rig(1f), -12.4f, c.rig(10.4f), -11.6f, 0xE8E8E8, 1f)
                c.bottle(c.rig(2.2f), -11.6f, s.accent)
                c.bottle(c.rig(3.6f), -11.6f, 0xE0E0E0)
                c.bottle(c.rig(8.6f), -11.6f, 0x80B0C0)
                c.rect(-8.4f, -17.6f, -3.4f, -17f, METAL, 1f)
                c.towel(-8f, -17f, -4f, s.accent)
                val by = -c.backY()
                c.box(-8.6f, by - 7f, -1.4f, by, 0xE8ECEE, 1f)
                c.rect(-8.6f, by - 7.3f, -1.4f, by - 6.8f, WHITE, .8f)
                c.capsule(-2.2f, by - 7.3f, .3f, -2.2f, by - 10f, .25f, METAL, 1f)
                if (v == 1) c.plantSmall(-7f, by - 7.3f)
                if (v == 2) c.picture(-7.5f, -26f, -3.5f, -22.5f, s.accent)
            }
            RoomKind.BEDROOM -> {
                // a bed with its headboard to the right, a bedside table and lamp, a wardrobe
                val bx0 = c.rig(2f)
                val bx1 = c.rig(11f)
                c.box(bx1 - 1f, -16f, bx1 + 1.4f, 0f, s.wood, 1f)
                c.box(bx0, -7f, bx1, -3.6f, if (v == 1) 0xE8E0D0 else s.rug, 1f)
                c.rect(bx0 + .4f, -7.3f, bx1 - 1f, -6.6f, WHITE, .5f)
                c.ellipse(bx1 - 2.8f, -8.4f, 2.2f, 1.1f, PAPER, 1f)
                c.rect(bx0, -3.6f, bx1, -1f, s.wood, 1f)
                c.rect(bx0, -1f, bx0 + .6f, 0f, s.wood, 1f)
                c.rect(bx1 - .6f, -1f, bx1, 0f, s.wood, 1f)
                c.box(bx0 + 1.5f, -4.8f, bx0 + 5.5f, -4.2f, s.accent, .8f)
                val tx = c.rig(-4.8f)
                c.box(tx, -7.6f, tx + 3.6f, 0f, s.wood, 1f)
                c.rect(tx + .4f, -6.6f, tx + 3.2f, -5.6f, BLACK, .3f)
                c.lampOnTable(tx + 1.8f, -7.6f, s.accent)
                if (v == 0) {
                    c.picture(-2f, -25f, 3f, -20f, s.accent)
                    c.picture(4f, -24f, 7.5f, -20.5f, s.rug)
                } else if (v == 1) {
                    c.wardrobe(-8.4f, -27f, -3.2f, s.wood)
                    c.picture(1f, -25f, 6f, -20.5f, s.accent)
                } else {
                    c.shelf(-8f, -23f, -2f)
                    c.books(-7.6f, -23f, 4, s.accent)
                    c.plantSmall(-3.4f, -23f)
                    c.poster(1f, -27f, 6f, -20f, s.accent)
                }
            }
            RoomKind.LIVING -> {
                // a sofa, the television on its stand to the right, a bookshelf, a plant
                c.sofa(c.rig(-7f), c.rig(4f), s.rug, s.rug2)
                val tx = c.rig(7.1f)
                c.box(tx - 2.4f, -c.backY() - 3f, tx + 2.4f, -c.backY(), s.wood, 1f)
                c.plantSmall(tx - 1.2f, -c.backY() - 3f)
                c.box(tx - 2.6f, -19.4f, tx + 2.6f, -14.6f, 0x1A1C20, 1f)
                c.rect(tx - 2.3f, -19.1f, tx + 2.3f, -14.9f, SCREEN_OFF, 1f)
                c.screen(tx - 2.3f, -19.1f, tx + 2.3f, -14.9f, .7f)
                if (v == 0) {
                    c.shelf(-8f, -23f, -2f)
                    c.books(-7.6f, -23f, 5, s.accent)
                    c.picture(-1f, -26f, 4f, -21.5f, s.accent)
                } else if (v == 1) {
                    c.picture(-7.5f, -26f, -1.5f, -21f, s.accent)
                    c.picture(-.5f, -26f, 3.5f, -22f, s.rug)
                    c.clock(5.5f, -26f)
                } else {
                    c.bookcase(-8.4f, -27f, -3.2f, s.wood, s.accent)
                    c.poster(-2f, -26f, 3f, -20f, s.accent)
                }
            }
            RoomKind.MUSIC -> {
                // an amp, a guitar on a stand, a keyboard, posters
                val ax = c.rig(8f)
                c.box(ax - 2.4f, -9f, ax + 2.4f, 0f, 0x202024, 1f)
                c.rect(ax - 1.9f, -8.2f, ax + 1.9f, -3f, 0x4A4034, 1f)
                c.ellipse(ax, -5.4f, 1.5f, 1.5f, 0x15161A, 1f)
                c.capsule(ax - 1.6f, -8.6f, .15f, ax + 1.6f, -8.6f, .15f, s.accent, .9f)
                c.guitarStand(c.rig(-6f), 0f, c.dark(s.accent, .62f))
                if (v == 0) {
                    c.poster(-8f, -27f, -3f, -20f, s.accent)
                    c.poster(-2f, -26f, 3f, -19f, s.rug)
                    c.shelf(4.5f, -24f, 8.3f)
                    c.books(4.9f, -24f, 3, s.accent)
                } else if (v == 1) {
                    c.poster(-6f, -27f, 0f, -20f, s.accent)
                    c.keyboard(c.rig(-1f), c.rig(6f), s.wood)
                } else {
                    c.poster(-8f, -26f, -4f, -21f, s.rug)
                    c.poster(-3f, -27f, 2f, -20f, s.accent)
                    c.poster(3f, -26f, 7f, -21.5f, s.rug)
                }
            }
            RoomKind.PIANO -> {
                // the piano's upper case against the wall (its keyboard is in front)
                val px0 = c.rig(2f)
                val px1 = c.rig(13.4f)
                c.box(px0, -24f, px1, -9f, if (v == 2) 0x4A3020 else 0x1A1416, 1f)
                c.rect(px0 + .8f, -23.4f, px1 - .8f, -22.8f, WHITE, .12f)
                c.rect(px0 + 2f, -18f, px1 - 2f, -11.4f, PAPER, .85f)
                for (i in 0 until 4) c.rect(
                    px0 + 3f,
                    -17f + i * 1.2f,
                    px1 - 3f,
                    -16.8f + i * 1.2f,
                    0x3A3A3A,
                    .5f,
                )
                c.stool(c.rig(-3f), s.wood)
                if (v == 0) {
                    c.picture(-8f, -26f, -3f, -21f, s.accent)
                    c.plantPot(-7.6f, -c.backY(), 5, 2.6f)
                } else if (v == 1) {
                    c.picture(-5f, -27f, 0f, -22f, s.rug)
                    c.plantSmall(-3f, -c.backY())
                } else {
                    c.bookcase(-8.4f, -27f, -3.4f, s.wood, s.accent)
                    c.metronome(px0 + 1f, -24f)
                }
            }
            RoomKind.PLANTS -> {
                // shelves of plants, a hanging one, a watering shelf
                val sx0 = c.rig(.8f)
                val sx1 = c.rig(15f)
                c.box(sx0, -10.4f, sx1, -9.6f, s.wood, 1f)
                for ((i, x) in listOf(2.6f, 7f, 11.4f).withIndex()) {
                    c.pot(c.rig(x), -10.4f - i * .3f, 1.1f, 2.4f, POT)
                    c.leaves(c.rig(x), -12.8f - i * .3f, 7, 3.2f + (i % 2))
                }
                c.capsule(-6f, -c.h(), .15f, -6f, -21f, .15f, 0x303030, 1f)
                c.pot(-6f, -21f, 1.2f, 1.8f, 0xC08860)
                c.leaves(-6f, -22.8f, 6, 2.4f)
                for (i in 0 until 3) c.capsule(
                    -7f + i * 1f,
                    -21f,
                    .3f,
                    -7.4f + i * 1.2f,
                    -16f + i * .6f,
                    .2f,
                    LEAF2,
                    .9f,
                )
                c.plantPot(-7.2f, 0f, 5, 2.4f)
                if (v == 0) {
                    c.shelf(-8.4f, -16f, -2f)
                    c.plantSmall(-7f, -16f)
                    c.plantSmall(-4f, -16f)
                } else if (v == 1) {
                    c.picture(-3f, -26f, 2f, -22f, s.accent)
                } else {
                    c.shelf(-8.4f, -15f, -1.5f)
                    c.jar(-7f, -15f, s.accent)
                    c.plantSmall(-4f, -15f)
                    c.clock(1f, -26f)
                }
            }
            RoomKind.BOARD -> {
                // a whiteboard with notes, a meeting table, chairs
                val wx0 = c.rig(1.6f)
                val wx1 = c.rig(14.6f)
                c.box(wx0 - .4f, -25.4f, wx1 + .4f, -9.8f, 0xD8D8D8, 1f)
                c.rect(wx0, -25f, wx1, -10.2f, 0xF4F4F0, 1f)
                for (i in 0 until 5) c.rect(
                    wx0 + 1.4f,
                    -22.6f + i * 2.2f,
                    wx0 + 5.4f + (i * 3) % 5,
                    -22.2f + i * 2.2f,
                    0x3A4A5A,
                    .6f,
                )
                c.rect(wx1 - 4.2f, -23f, wx1 - 1.2f, -20.4f, 0xFFC94A, 1f)
                c.rect(wx1 - 4.2f, -19.6f, wx1 - 1.2f, -17f, 0x6FB7FF, 1f)
                c.rect(wx1 - 4.2f, -16.2f, wx1 - 1.2f, -13.6f, 0xFF8E7A, 1f)
                c.rect(wx0 + 1.4f, -11.2f, wx1 - 1.4f, -10.6f, METAL, 1f)
                c.chair(-6f, s.wood)
                if (v == 0) {
                    c.plantPot(-7.8f, -c.backY(), 6, 3f)
                    c.clock(-5f, -27f)
                } else if (v == 1) {
                    c.picture(-8f, -26f, -3.5f, -22f, s.accent)
                    c.rect(wx0 + 1.4f, -24f, wx0 + 6f, -23.6f, s.accent, .8f)
                } else {
                    c.poster(-8f, -27f, -3f, -21f, s.accent)
                    c.plantSmall(-6f, -21f)
                }
            }
            RoomKind.HALL -> {
                // a corridor: a door at the back, a console table, coat hooks, pictures
                val dx = c.rig(7f)
                c.box(dx - 3f, -21f, dx + 3f, -c.backY(), s.wood, 1f)
                c.rect(dx - 2.4f, -20f, dx + 2.4f, -c.backY() - .6f, s.wood, 1f)
                c.rect(dx - 2.4f, -20f, dx + 2.4f, -19.5f, WHITE, .2f)
                c.rect(dx - 1.6f, -18.6f, dx + 1.6f, -14f, BLACK, .18f)
                c.rect(dx - 1.6f, -12.6f, dx + 1.6f, -c.backY() - 2f, BLACK, .18f)
                c.ellipse(dx + 1.8f, -10.4f, .45f, .45f, 0xE8C860, 1f)
                c.console(c.rig(-6.6f), c.rig(-.6f), s.wood)
                c.plantPot(-7.4f, 0f, 6, 2.6f)
                if (v == 0) {
                    c.picture(-6f, -25f, -1.5f, -20.5f, s.accent)
                    c.hooks(1f, -22f, s.wood, s.accent)
                } else if (v == 1) {
                    c.mirror(-6f, -26f, -1.5f, -14f)
                    c.hooks(1f, -22f, s.wood, s.rug)
                } else {
                    c.picture(-7f, -26f, -3.5f, -22.5f, s.accent)
                    c.picture(-2.5f, -26f, 1f, -22.5f, s.rug)
                    c.clock(3.5f, -25f)
                }
            }
            RoomKind.DINING -> {
                // two chairs either side of the table (the table is in front), a sideboard; the
                // pair is drawn smaller to fit, and its chairs and table with it
                val f =
                    PeopleActivities.fitted(com.lifemax.rpg.feature.habits.domain.Activity.TALKING)
                c.chairBack(-10.6f * f, -15f * f, -9f * f, s.wood, false, f)
                c.chairBack(10.6f * f, -15f * f, -9f * f, s.wood, true, f)
                c.sideboard(-8f, 2f, s.wood, v)
                if (v == 0) {
                    c.picture(-6f, -26f, -1f, -21f, s.accent)
                    c.picture(1f, -26f, 5f, -22f, s.rug)
                } else if (v == 1) {
                    c.picture(-5f, -27f, 4f, -21f, s.accent)
                } else {
                    c.shelf(-7f, -23f, 7f)
                    c.jar(-5.5f, -23f, s.accent)
                    c.books(-3f, -23f, 4, s.rug)
                    c.plantSmall(4f, -23f)
                }
            }
        }
    }

    /** What stands between the person and the glass: a desk, a counter, a sink, a table. */
    private fun front(c: Cv, kind: RoomKind, v: Int) {
        val s = c.sc
        when (kind) {
            RoomKind.OFFICE,
            RoomKind.DESK -> {
                val x0 = c.rig(2.2f)
                val x1 = c.rig(13f)
                c.box(x0, -9.2f, x1, -8f, s.wood, 1f, .3f)
                c.rect(x0 + .4f, -8f, x1 - .4f, 0f, c.dark(s.wood, .75f), 1f)
                c.rect(x0 + .6f, -7.6f, x1 - .6f, -1f, c.dark(s.wood, .85f), 1f)
                if (kind == RoomKind.DESK) {
                    c.box(x0 + 1f, -9.6f, x0 + 4.4f, -9.1f, PAPER, 1f)
                    c.mug(x1 - 1.6f, -9.2f, s.accent)
                } else {
                    c.mug(x1 - 1.4f, -9.2f, s.accent)
                    c.box(x0 + .6f, -9.5f, x0 + 2.6f, -9.1f, 0xE8E4DC, 1f)
                }
            }
            RoomKind.KITCHEN -> {
                val x0 = c.rig(2.5f)
                val x1 = c.rig(13.5f)
                val top = if (v == 2) 0x50585A else 0xD8CFC0
                c.box(x0, -9.6f, x1, -8.6f, top, 1f, .3f)
                val cab = if (v == 1) 0xE8E8E0 else s.wood
                c.rect(x0 + .3f, -8.6f, x1 - .3f, 0f, cab, 1f)
                c.rect(x0 + .3f, -8.6f, x1 - .3f, -8.2f, BLACK, .18f)
                val mid = (x0 + x1) / 2f
                c.rect(mid - .15f, -8.2f, mid + .15f, 0f, BLACK, .3f)
                c.rect(x0 + 1.2f, -6.2f, x0 + 2.6f, -5.9f, METAL, 1f)
                c.rect(x1 - 2.6f, -6.2f, x1 - 1.2f, -5.9f, METAL, 1f)
                // the hob and a pan on it
                val hx = c.rig(9f)
                c.rect(hx - 2.6f, -9.9f, hx + 2.6f, -9.6f, 0x202428, 1f)
                c.ellipse(hx, -10.1f, 1.5f, .35f, 0x303438, 1f)
                c.ellipse(hx - .1f, -10.2f, 1.1f, .25f, 0xC8503A, .9f)
                c.capsule(hx + 1.4f, -10.3f, .14f, hx + 3.6f, -10.9f, .12f, 0x202428, 1f)
                c.mug(x0 + 1.2f, -9.6f, s.accent)
                c.board(x0 + 2.6f, -9.6f, s.wood)
            }
            RoomKind.BATH -> {
                val x0 = c.rig(2.5f)
                val x1 = c.rig(12.5f)
                c.box(x0, -8.9f, x1, -8f, 0xE8E4DC, 1f, .35f)
                c.ellipse((x0 + x1) / 2f - 1f, -8.9f, 2.6f, .4f, 0xCFD6DC, 1f)
                c.rect(x0 + .3f, -8f, x1 - .3f, 0f, if (v == 1) s.wood else 0xE0E4E8, 1f)
                c.rect(x0 + .3f, -8f, x1 - .3f, -7.6f, BLACK, .15f)
                val tx = (x0 + x1) / 2f - 1f
                c.capsule(tx, -9.2f, .3f, tx, -11.4f, .25f, 0xC0C8D0, 1f)
                c.capsule(tx, -11.4f, .25f, tx + 1.5f, -11.6f, .2f, 0xC0C8D0, 1f)
                c.bottle(x1 - 1.6f, -8.9f, s.accent)
            }
            RoomKind.PIANO -> {
                val x0 = c.rig(2f)
                val x1 = c.rig(13.4f)
                val body = if (v == 2) 0x4A3020 else 0x1A1416
                c.box(x0, -9f, x1, 0f, body, 1f, .12f)
                c.rect(x0 + .2f, -9.3f, x1 - .2f, -8.9f, WHITE, .15f)
                c.rect(x0, -9f, x0 + 6.2f, -8.2f, PAPER, 1f)
                for (i in 0 until 5) c.rect(
                    x0 + .7f + i * 1.1f,
                    -9f,
                    x0 + 1.2f + i * 1.1f,
                    -8.6f,
                    0x151515,
                    1f,
                )
            }
            RoomKind.DINING -> {
                val f =
                    PeopleActivities.fitted(com.lifemax.rpg.feature.habits.domain.Activity.TALKING)
                val t = -9.4f * f
                val hw = 4.6f * f
                c.box(-hw, t, hw, t + 1f, s.wood, 1f, .3f)
                c.rect(-hw + .4f, t + 1f, hw - .4f, t + 1.5f, BLACK, .3f)
                c.rect(-.3f, t + 1f, .3f, 0f, c.dark(s.wood, .7f), 1f)
                c.poly(
                    floatArrayOf(-hw - .4f, -1f, hw + .4f, -1f, hw - 1.4f, 0f, -hw + 1.4f, 0f),
                    c.dark(s.wood, .7f),
                    1f,
                )
                c.ellipse(-hw * .4f, t - .1f, 1f, .3f, 0xE8E4DC, 1f)
                c.ellipse(hw * .45f, t - .1f, 1f, .3f, 0xE8E4DC, 1f)
                c.mug(-hw * .75f, t, s.accent)
                c.candle(.4f, t - .1f)
            }
            RoomKind.BOARD -> {
                // the end of the meeting table, as seen past the chairs
                c.box(-7.5f, -8.8f, 7.5f, -8f, 0xE8E4DC, 1f, .3f)
                c.rect(-7f, -8f, 7f, -7.5f, BLACK, .25f)
                c.rect(-.3f, -7.5f, .3f, 0f, METAL, 1f)
                c.rect(-4f, -9.2f, -1f, -8.8f, 0xF4F4F0, 1f)
                c.rect(1.5f, -9.1f, 4.5f, -8.8f, s.accent, .8f)
            }
            else -> {}
        }
    }

    /**
     * A painting surface for one window: [out] gives each of its layers. Shapes are given in the
     * room's units: the window's middle at x = 0, the floor (where the people stand) at y = 0 and
     * up negative, drawn at [s] times their size, so a room's furniture keeps its proportions to
     * the person in a short window. [rig] moves the rig's x (where a person's pose is given) into
     * the room's.
     */
    private class Cv(
        private val hUnits: Float,
        private val dx: Float,
        private val s: Float,
        val sc: Scheme,
    ) {
        companion object {
            const val BASE = 0
            const val FRONT = 1
            const val LAMP = 2
            const val SCREEN = 3
        }

        private class Layer(val w: Int, val h: Int, val ppu: Float) {
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            val a = FloatArray(w * h)
        }

        private val layers =
            arrayOf(
                Layer(ceil(WIN_W * PPU).toInt(), ceil(hUnits * PPU).toInt(), PPU),
                Layer(ceil(WIN_W * PPU).toInt(), ceil(hUnits * PPU).toInt(), PPU),
                Layer(ceil(WIN_W * LIGHT_PPU).toInt(), ceil(hUnits * LIGHT_PPU).toInt(), LIGHT_PPU),
                Layer(ceil(WIN_W * LIGHT_PPU).toInt(), ceil(hUnits * LIGHT_PPU).toInt(), LIGHT_PPU),
            )
        var layer = BASE

        /** The rig's x in the room's. */
        fun rig(x: Float): Float = x + dx

        /** The window's height in the room's units. */
        fun h(): Float = hUnits / s

        /** How far above the people's floor the back wall meets it, in the room's units. */
        fun backY(): Float = BACK

        fun dark(color: Int, k: Float): Int {
            val r = (((color shr 16) and 255) * k).toInt().coerceIn(0, 255)
            val g = (((color shr 8) and 255) * k).toInt().coerceIn(0, 255)
            val b = ((color and 255) * k).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }

        private fun px(l: Layer, x: Float) = (s * x + WIN_W / 2f) * l.ppu

        private fun py(l: Layer, y: Float) = (hUnits + s * y) * l.ppu

        fun out(l: Int): Sprite {
            val ly = layers[l]
            val pixels =
                IntArray(ly.w * ly.h) {
                    PeopleRaster.argb(ly.a[it], ly.r[it] * 255f, ly.g[it] * 255f, ly.b[it] * 255f)
                }
            return Sprite(ly.w, ly.h, ly.ppu, 0f, 0f, pixels)
        }

        private fun over(l: Layer, i: Int, color: Int, alpha: Float) {
            if (alpha <= 0f) return
            val al = min(1f, alpha)
            val sr = ((color shr 16) and 255) / 255f
            val sg = ((color shr 8) and 255) / 255f
            val sb = (color and 255) / 255f
            val da = l.a[i]
            val oa = al + da * (1f - al)
            l.r[i] = (sr * al + l.r[i] * da * (1f - al)) / oa
            l.g[i] = (sg * al + l.g[i] * da * (1f - al)) / oa
            l.b[i] = (sb * al + l.b[i] * da * (1f - al)) / oa
            l.a[i] = oa
        }

        // ------------------------------------------------------------ the walls and the floor

        /**
         * The room's shell into the base layer: the back wall with its texture, the side walls
         * narrowing towards it, the floor in perspective with its boards and a rug, the skirting,
         * the shade in the corners and under the ceiling. Dim: the light from outside only.
         */
        fun architecture() {
            val l = layers[BASE]
            val floorY = hUnits - BACK * s
            val amb = .58f
            val seed = sc.wall * 31 + sc.floor
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val u = (x + .5f) / l.ppu - WIN_W / 2f
                val yy = (y + .5f) / l.ppu
                val i = y * l.w + x
                var c: Int
                var k = amb
                if (yy < floorY) {
                    // the side walls: wider nearer the viewer (the top of the pane is further up
                    // the wall, so they narrow upwards a little)
                    val t = yy / floorY
                    val inset = 1f + 1.2f * t
                    val onLeft = u < -WIN_W / 2f + inset
                    val onRight = u > WIN_W / 2f - inset
                    if (onLeft || onRight) {
                        c = sc.wall2
                        k *= if (onLeft) .62f else .74f
                        // a line where the walls meet
                        val edge =
                            if (onLeft) (-WIN_W / 2f + inset) - u else u - (WIN_W / 2f - inset)
                        if (edge < .25f) k *= .8f
                    } else {
                        c = texture(u, yy, seed)
                        // the corners and the ceiling line are in shade; the wall is lightest where
                        // the window's own daylight reaches it (lower middle)
                        val nx = u / (WIN_W / 2f - inset)
                        k *= 1f - .22f * nx * nx * nx * nx
                        if (yy < 1.4f) k *= .55f + .45f * yy / 1.4f
                        val toFloor = floorY - yy
                        if (toFloor < 1.2f) k *= .7f + .3f * toFloor / 1.2f
                        k *= .9f + .1f * t
                    }
                } else {
                    // the floor, nearer and lighter towards the bottom, with boards that run away
                    // from the viewer
                    val t = ((yy - floorY) / (hUnits - floorY)).coerceIn(0f, 1f)
                    c = sc.floor
                    k *= .72f + .4f * t
                    val depth = 1f / (.35f + .65f * t)
                    val board = u * depth / 2.2f
                    val fr = board - floor(board)
                    if (fr < .08f * depth) k *= .78f
                    val grain = Noise.value(board * 3f, yy * .7f, seed + 7)
                    k *= .94f + .12f * grain
                    // the rug
                    val rugHalf = 5.6f * s * (.6f + .4f * t)
                    val ry0 = floorY + (hUnits - floorY) * .2f
                    if (yy > ry0 && abs(u) < rugHalf) {
                        val border = abs(u) > rugHalf - .7f || yy < ry0 + .6f
                        c = if (border) sc.rug2 else sc.rug
                        val weave = Noise.value(u * 4f, yy * 4f, seed + 3)
                        k = amb * (.8f + .4f * t) * (.92f + .16f * weave)
                        if (!border && ((u * 1.6f + 100f).toInt() + (yy * 1.6f).toInt()) % 5 == 0)
                            c = sc.rug2
                    }
                }
                over(l, i, dark(c, k), 1f)
            }
            // the skirting board along the back wall's foot
            val sk = layers[BASE]
            for (y in 0 until sk.h) for (x in 0 until sk.w) {
                val yy = (y + .5f) / sk.ppu
                if (yy >= floorY - .7f * s && yy < floorY) {
                    val u = (x + .5f) / sk.ppu - WIN_W / 2f
                    val t = (floorY - .7f * s) / floorY
                    val inset = 1f + 1.2f * t
                    if (abs(u) < WIN_W / 2f - inset) {
                        val top = yy < floorY - .5f * s
                        over(sk, y * sk.w + x, dark(sc.wood, if (top) .95f else .6f), .9f)
                    }
                }
            }
        }

        /** The back wall's colour at ([u], [yy]) in the window's units. */
        private fun texture(u: Float, yy: Float, seed: Int): Int =
            when (sc.tex) {
                Tex.PAINT -> {
                    val n = Noise.fbm(u * .9f, yy * .9f, seed, 2)
                    mix(sc.wall, sc.wall2, (n - .5f) * .6f + .3f)
                }
                Tex.PAPER -> {
                    val stripe = ((u + 50f) / 1.4f).toInt() % 2 == 0
                    val motif = Noise.value(u * 1.5f, yy * 1.5f, seed) > .72f
                    if (motif) mix(sc.wall, sc.wall2, .8f)
                    else if (stripe) sc.wall else mix(sc.wall, sc.wall2, .4f)
                }
                Tex.TILE -> {
                    val tile = 2.2f
                    val gx = (u + 50f) / tile
                    val gy = yy / tile
                    val fx = gx - floor(gx)
                    val fy = gy - floor(gy)
                    if (fx < .09f || fy < .09f) sc.wall2
                    else {
                        // a gloss along each tile's top left
                        val gloss = (1f - fx) * (1f - fy)
                        mix(sc.wall, WHITE, gloss * .18f)
                    }
                }
                Tex.BRICK -> {
                    val bh = 1.1f
                    val bw = 2.6f
                    val row = floor(yy / bh).toInt()
                    val off = if (row % 2 == 0) 0f else bw / 2f
                    val fx = ((u + off + 50f) / bw).let { it - floor(it) }
                    val fy = yy / bh - row
                    if (fx < .08f || fy < .12f) sc.wall2
                    else {
                        val n = Noise.value((u + off) * 1.3f, yy * 1.3f, seed + row)
                        mix(sc.wall, sc.wall2, (n - .5f) * .5f + .2f)
                    }
                }
                Tex.PANEL -> {
                    val fx = ((u + 50f) / 1.7f).let { it - floor(it) }
                    val grain = Noise.value(u * 6f, yy * .5f, seed)
                    if (fx < .1f) sc.wall2
                    else mix(sc.wall, sc.wall2, .2f + .25f * grain + (if (fx > .85f) .2f else 0f))
                }
            }

        private fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val r =
                (((a shr 16) and 255) + ((((b shr 16) and 255) - ((a shr 16) and 255)) * k)).toInt()
            val g =
                (((a shr 8) and 255) + ((((b shr 8) and 255) - ((a shr 8) and 255)) * k)).toInt()
            val bl = ((a and 255) + (((b and 255) - (a and 255)) * k)).toInt()
            return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        }

        // ------------------------------------------------------------ the light

        /** Where the room's lamp is (the light's source), in the room's units. */
        private fun lampAt(): FloatArray =
            when (sc.lamp) {
                Lamp.PENDANT -> floatArrayOf(sc.lampX / s, -h() + 3.2f)
                Lamp.FLOOR -> floatArrayOf(sc.lampX, -18.5f)
                Lamp.DESK -> floatArrayOf(sc.lampX, -16.4f)
                Lamp.SCONCE -> floatArrayOf(sc.lampX, -23f)
            }

        /** The lamp itself, in the base layer: its cord or stand and its shade, unlit. */
        fun lampFixture() {
            layer = BASE
            val at = lampAt()
            val lx = at[0]
            val ly = at[1]
            when (sc.lamp) {
                Lamp.PENDANT -> {
                    capsule(lx, -h(), .12f, lx, ly - 2.2f, .12f, 0x202020, 1f)
                    poly(
                        floatArrayOf(
                            lx - 2.6f,
                            ly,
                            lx + 2.6f,
                            ly,
                            lx + 1.1f,
                            ly - 2.4f,
                            lx - 1.1f,
                            ly - 2.4f,
                        ),
                        sc.accent,
                        1f,
                    )
                    rect(lx - 2.6f, ly - .35f, lx + 2.6f, ly, BLACK, .2f)
                    ellipse(lx, ly, 2.4f, .45f, PAPER, .8f)
                }
                Lamp.FLOOR -> {
                    ellipse(lx, -.2f, 1.6f, .45f, 0x2A2A30, 1f)
                    capsule(lx, -.4f, .18f, lx, ly + 1f, .14f, 0x2A2A30, 1f)
                    poly(
                        floatArrayOf(
                            lx - 2.4f,
                            ly + 1.4f,
                            lx + 2.4f,
                            ly + 1.4f,
                            lx + 1.6f,
                            ly - 2.2f,
                            lx - 1.6f,
                            ly - 2.2f,
                        ),
                        PAPER,
                        1f,
                    )
                    rect(lx - 2.4f, ly + 1f, lx + 2.4f, ly + 1.4f, BLACK, .18f)
                }
                Lamp.DESK -> {
                    // a desk lamp on something: the arm is drawn by the room, this is its shade
                    poly(
                        floatArrayOf(
                            lx - 2.2f,
                            ly + 1.4f,
                            lx + 1.2f,
                            ly + 1.4f,
                            lx + .6f,
                            ly - 1f,
                            lx - 1.4f,
                            ly - 1f,
                        ),
                        sc.accent,
                        1f,
                    )
                    rect(lx - 2.2f, ly + 1.1f, lx + 1.2f, ly + 1.4f, WHITE, .3f)
                }
                Lamp.SCONCE -> {
                    poly(
                        floatArrayOf(
                            lx - 1.8f,
                            ly + 1f,
                            lx + 1.8f,
                            ly + 1f,
                            lx + 1.2f,
                            ly - 1f,
                            lx - 1.2f,
                            ly - 1f,
                        ),
                        PAPER,
                        1f,
                    )
                    rect(lx - 1.8f, ly + .7f, lx + 1.8f, ly + 1f, BLACK, .2f)
                }
            }
        }

        /**
         * The lamp's light into the lamp layer: the shade's own glow, a warm pool down the wall, a
         * pool on the floor under it, a faint fill over the whole room and the light that spills
         * along the sill in front of everything.
         */
        fun light() {
            val l = layers[LAMP]
            val at = lampAt()
            val lx = at[0]
            val ly = at[1]
            val floorY = hUnits - BACK * s
            val reach =
                when (sc.lamp) {
                    Lamp.PENDANT -> 15f
                    Lamp.FLOOR -> 12f
                    Lamp.DESK -> 9.5f
                    Lamp.SCONCE -> 10f
                }
            val down = sc.lamp == Lamp.PENDANT || sc.lamp == Lamp.FLOOR
            val x0 = px(l, lx)
            val y0 = py(l, ly)
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val i = y * l.w + x
                val ddx = (x + .5f - x0) / (s * l.ppu)
                var ddy = (y + .5f - y0) / (s * l.ppu)
                // more light below the shade than above it
                if (down && ddy < 0f) ddy *= 2.2f
                val d = sqrt(ddx * ddx + ddy * ddy * .8f) / reach
                var k = if (d < 1f) (1f - d) * (1f - d) * .4f else 0f
                k += .04f
                val yy = (y + .5f) / l.ppu
                if (yy > floorY) {
                    // the floor catches the light in a pool under the lamp, strongest nearest it
                    val t = (yy - floorY) / (hUnits - floorY)
                    val fd = abs(ddx) / (reach * .7f)
                    if (fd < 1f) k += (1f - fd) * (1f - fd) * .14f * (1f - .5f * t)
                }
                over(l, i, WARM, min(.9f, k))
            }
            // the shade itself glows
            layer = LAMP
            glow(lx, ly, 3.4f, 0xFFF4DC, .9f)
            glow(lx, ly, 1.6f, WHITE, .8f)
            // the light along the sill
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val yy = (l.h - y - .5f) / l.ppu
                if (yy < 2.4f)
                    over(l, y * l.w + x, 0xFFE2A0, (1f - yy / 2.4f) * (1f - yy / 2.4f) * .3f)
            }
            layer = BASE
        }

        /**
         * A screen (lit) into the screen layer at the box given: its picture, pale and cool, and
         * its glow on what is around it, [k] strong.
         */
        fun screen(x0: Float, y0: Float, x1: Float, y1: Float, k: Float) {
            val was = layer
            layer = SCREEN
            rect(x0, y0, x1, y1, 0xDCE8FF, .95f)
            rect(x0, y0, x1, y0 + (y1 - y0) * .4f, WHITE, .35f)
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            glow(cx, cy, (x1 - x0) * 2.2f, COOL, k)
            glow(cx, cy, (x1 - x0) * 1.1f, COOL, k * .5f)
            // the wash on the floor in front of it
            val l = layers[SCREEN]
            val floorY = hUnits - BACK * s
            val sx = px(l, cx)
            for (y in 0 until l.h) for (x in 0 until l.w) {
                val yy = (y + .5f) / l.ppu
                if (yy <= floorY) continue
                val ddx = abs(x + .5f - sx) / (l.ppu * s * (x1 - x0) * 2.4f)
                if (ddx < 1f) over(l, y * l.w + x, COOL, (1f - ddx) * (1f - ddx) * k * .35f)
            }
            layer = was
        }

        // ------------------------------------------------------------ shapes

        fun glow(cx: Float, cy: Float, radius: Float, color: Int, k: Float) {
            val l = layers[layer]
            val x0 = px(l, cx)
            val y0 = py(l, cy)
            val rr = radius * s * l.ppu
            for (y in max(0, floor(y0 - rr).toInt())..min(l.h - 1, ceil(y0 + rr).toInt())) for (x in
                max(0, floor(x0 - rr).toInt())..min(l.w - 1, ceil(x0 + rr).toInt())) {
                val d = sqrt((x + .5f - x0) * (x + .5f - x0) + (y + .5f - y0) * (y + .5f - y0)) / rr
                if (d < 1f) over(l, y * l.w + x, color, (1f - d) * (1f - d) * k)
            }
        }

        fun rect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, alpha: Float) =
            poly(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), color, alpha)

        /** A shaded block: lit along its top and left edge, in shade along its bottom and right. */
        fun box(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            color: Int,
            alpha: Float,
            lit: Float = .22f,
        ) {
            rect(x0, y0, x1, y1, color, alpha)
            val t = min(.45f, (y1 - y0) * .2f)
            val e = min(.3f, (x1 - x0) * .1f)
            rect(x0, y0, x1, y0 + t, WHITE, alpha * lit)
            rect(x0, y1 - t, x1, y1, BLACK, alpha * .28f)
            rect(x1 - e, y0, x1, y1, BLACK, alpha * .22f)
            rect(x0, y0, x0 + e, y1, WHITE, alpha * lit * .5f)
        }

        fun ellipse(
            cx: Float,
            cy: Float,
            rx: Float,
            ry: Float,
            color: Int,
            alpha: Float,
            rot: Float = 0f,
        ) =
            poly(
                com.lifemax.rpg.feature.habits.domain.FigBuilder.ellipse(cx, cy, rx, ry, rot, 14),
                color,
                alpha,
            )

        fun capsule(
            x0: Float,
            y0: Float,
            r0: Float,
            x1: Float,
            y1: Float,
            r1: Float,
            color: Int,
            alpha: Float,
        ) =
            poly(
                com.lifemax.rpg.feature.habits.domain.FigBuilder.capsule(x0, y0, r0, x1, y1, r1, 5),
                color,
                alpha,
            )

        /** Fills a polygon (room units) with [color] at [alpha], anti-aliased, in the layer. */
        fun poly(pts: FloatArray, color: Int, alpha: Float) {
            val l = layers[layer]
            val n = pts.size / 2
            val q = FloatArray(pts.size)
            var x0 = 1e9f
            var y0 = 1e9f
            var x1 = -1e9f
            var y1 = -1e9f
            for (i in 0 until n) {
                q[i * 2] = px(l, pts[i * 2])
                q[i * 2 + 1] = py(l, pts[i * 2 + 1])
                x0 = min(x0, q[i * 2])
                x1 = max(x1, q[i * 2])
                y0 = min(y0, q[i * 2 + 1])
                y1 = max(y1, q[i * 2 + 1])
            }
            val bx = max(0, floor(x0).toInt())
            val by = max(0, floor(y0).toInt())
            val ex = min(l.w, ceil(x1).toInt())
            val ey = min(l.h, ceil(y1).toInt())
            if (ex <= bx || ey <= by) return
            val pl = PeopleRaster.Planes((ex - bx) * PeopleRaster.SS, (ey - by) * PeopleRaster.SS)
            PeopleRaster.fill(pl, q, 1f, -bx.toFloat(), -by.toFloat(), 1f)
            val ss = PeopleRaster.SS
            for (y in by until ey) for (x in bx until ex) {
                var c = 0f
                for (j in 0 until ss) for (i in 0 until ss) c +=
                    pl.alpha[((y - by) * ss + j) * pl.w + (x - bx) * ss + i]
                if (c > 0f) over(l, y * l.w + x, color, alpha * c / (ss * ss))
            }
        }

        // ------------------------------------------------------------ furniture

        /** An office chair seen from the side, its back at [x]. */
        fun chair(x: Float, wood: Int) {
            box(x - .6f, -14f, x + .6f, -6f, 0x2A2A30, 1f, .15f)
            box(x - .2f, -6.6f, x + 7f, -5.4f, 0x2A2A30, 1f, .15f)
            rect(x + 2.9f, -5.4f, x + 3.7f, -1.2f, 0x3A3A40, 1f)
            rect(x + .4f, -1.2f, x + 6.2f, -.6f, 0x3A3A40, 1f)
            ellipse(x + .9f, -.4f, .5f, .4f, 0x202024, 1f)
            ellipse(x + 5.7f, -.4f, .5f, .4f, 0x202024, 1f)
        }

        /** A dining chair's back at [x], its seat [seat] up, facing right or left, [f] its size. */
        fun chairBack(x: Float, top: Float, seat: Float, wood: Int, right: Boolean, f: Float) {
            val d = if (right) -1f else 1f
            box(x - .5f, top, x + .5f, seat, wood, 1f)
            rect(x - .5f, top, x + .5f, top + .5f, WHITE, .25f)
            box(x - .6f, seat, x + d * 5f * f + .6f, seat + .8f, wood, 1f)
            rect(x + d * 4.4f * f, seat + .8f, x + d * 5f * f, 0f, wood, 1f)
            rect(x - .4f, seat + .8f, x + .4f, 0f, wood, 1f)
        }

        /** A desk lamp's arm from the desk top up to its shade at ([lx], -16.4). */
        fun deskLampArm(lx: Float) {
            ellipse(lx + 2.2f, -9.9f, 1f, .3f, 0x2A2A30, 1f)
            capsule(lx + 2.2f, -10f, .22f, lx + 1.2f, -13.6f, .18f, 0x2A2A30, 1f)
            capsule(lx + 1.2f, -13.6f, .18f, lx - .2f, -16.4f, .16f, 0x2A2A30, 1f)
        }

        /** An armchair from [x0] (its back) to [x1] (its front). */
        fun armchair(x0: Float, x1: Float, cloth: Int, dark: Int) {
            box(x0, -15.5f, x0 + 3f, -4f, cloth, 1f)
            box(x0 + .6f, -6.6f, x1, -3.2f, cloth, 1f)
            rect(x0 + .8f, -6.4f, x1 - .6f, -5.8f, WHITE, .14f)
            box(x1 - 2.2f, -9.6f, x1 + .4f, -3.2f, dark, 1f)
            rect(x0 + .4f, -3.2f, x1 - .2f, -.6f, dark, 1f)
            rect(x0 + .8f, -.6f, x0 + 1.6f, 0f, 0x202024, 1f)
            rect(x1 - 1.2f, -.6f, x1 - .4f, 0f, 0x202024, 1f)
        }

        /** A sofa from [x0] to [x1] with its cushions. */
        fun sofa(x0: Float, x1: Float, cloth: Int, dark: Int) {
            box(x0, -13.6f, x1, -7.8f, cloth, 1f)
            val n = 3
            val w = (x1 - x0 - 1.6f) / n
            for (i in 0 until n) {
                rect(
                    x0 + .8f + i * w + .15f,
                    -13.2f,
                    x0 + .8f + (i + 1) * w - .15f,
                    -8f,
                    dark,
                    .35f,
                )
                rect(
                    x0 + .8f + i * w + .4f,
                    -13f,
                    x0 + .8f + (i + 1) * w - .4f,
                    -12.4f,
                    WHITE,
                    .14f,
                )
            }
            box(x0 - .6f, -9.6f, x0 + 1.2f, -3f, dark, 1f)
            box(x1 - 1.2f, -9.6f, x1 + .6f, -3f, dark, 1f)
            box(x0 + 1f, -7.8f, x1 - 1f, -3.4f, cloth, 1f, .3f)
            rect(x0 + .8f, -3.4f, x1 - .8f, -.8f, dark, 1f)
            rect(x0 + 1.2f, -.8f, x0 + 2f, 0f, 0x202024, 1f)
            rect(x1 - 2f, -.8f, x1 - 1.2f, 0f, 0x202024, 1f)
        }

        fun sideTable(x0: Float, x1: Float, wood: Int) {
            box(x0, -7.6f, x1, -6.9f, wood, 1f)
            rect(x0 + .3f, -6.9f, x0 + .9f, 0f, wood, 1f)
            rect(x1 - .9f, -6.9f, x1 - .3f, 0f, wood, 1f)
        }

        fun console(x0: Float, x1: Float, wood: Int) {
            box(x0, -9f, x1, -8.2f, wood, 1f)
            rect(x0 + .4f, -8.2f, x1 - .4f, -5.4f, wood, 1f)
            rect(x0 + .9f, -7.4f, x1 - .9f, -6.4f, BLACK, .3f)
            rect(x0 + .3f, -5.4f, x0 + .9f, 0f, wood, 1f)
            rect(x1 - .9f, -5.4f, x1 - .3f, 0f, wood, 1f)
            vase((x0 + x1) / 2f, -9f, sc.accent)
        }

        fun sideboard(x0: Float, x1: Float, wood: Int, v: Int) {
            val by = -backY()
            box(x0, by - 7.6f, x1, by, wood, 1f)
            rect(x0 + .5f, by - 7f, (x0 + x1) / 2f - .2f, by - 1f, BLACK, .22f)
            rect((x0 + x1) / 2f + .2f, by - 7f, x1 - .5f, by - 1f, BLACK, .22f)
            if (v != 1) vase(x0 + 2f, by - 7.6f, sc.accent)
            if (v != 2) {
                bottle(x1 - 2f, by - 7.6f, 0x3A6A3A)
                bottle(x1 - 3.2f, by - 7.6f, 0x3A6A3A)
            }
        }

        fun wardrobe(x0: Float, top: Float, x1: Float, wood: Int) {
            val by = -backY()
            box(x0, top, x1, by, wood, 1f)
            rect((x0 + x1) / 2f - .1f, top + .5f, (x0 + x1) / 2f + .1f, by - .5f, BLACK, .4f)
            ellipse((x0 + x1) / 2f - .7f, (top + by) / 2f, .25f, .25f, 0xE8C860, 1f)
            ellipse((x0 + x1) / 2f + .7f, (top + by) / 2f, .25f, .25f, 0xE8C860, 1f)
        }

        fun bookcase(x0: Float, top: Float, x1: Float, wood: Int, accent: Int) {
            val by = -backY()
            rect(x0, top, x1, by, dark(wood, .8f), 1f)
            rect(x0, top, x1, top + .4f, WHITE, .18f)
            var y = top + .5f
            var row = 0
            while (y + 4.4f <= by) {
                rect(x0 + .3f, y, x1 - .3f, y + 4f, BLACK, .32f)
                books(
                    x0 + .5f,
                    y + 4f,
                    ((x1 - x0 - 1f) / 1.05f).toInt(),
                    if (row % 2 == 0) accent else sc.rug,
                    4f,
                )
                rect(x0, y + 4f, x1, y + 4.4f, wood, 1f)
                y += 4.4f
                row++
            }
        }

        fun shelf(x0: Float, y: Float, x1: Float) {
            box(x0, y, x1, y + .5f, sc.wood, 1f)
            rect(x0 + .3f, y + .5f, x1 + .2f, y + 1f, BLACK, .18f)
        }

        /** [n] books standing on a shelf whose top is [y], from [x]. */
        fun books(x: Float, y: Float, n: Int, accent: Int, tall: Float = 3.4f) {
            val tones = intArrayOf(accent, 0x6A3A2E, 0x3A4A6A, 0xC8B080, 0x4A6A4A, 0x8A3A4A)
            var bx = x
            for (i in 0 until n) {
                val w = .7f + (i * 7 % 3) * .2f
                val hh = tall - (i * 5 % 4) * .35f
                rect(bx, y - hh, bx + w, y, tones[(i + (accent and 7)) % tones.size], 1f)
                rect(bx, y - hh, bx + .12f, y, WHITE, .2f)
                rect(bx, y - .9f, bx + w, y - .7f, WHITE, .12f)
                bx += w + .1f
            }
        }

        fun binder(x: Float, y: Float, color: Int) {
            rect(x, y - 3.6f, x + 1.4f, y, color, 1f)
            rect(x + .3f, y - 3f, x + 1.1f, y - 1.8f, WHITE, .5f)
        }

        fun picture(x0: Float, y0: Float, x1: Float, y1: Float, tone: Int) {
            rect(x0 - .3f, y0 - .3f, x1 + .3f, y1 + .3f, sc.wood, 1f)
            rect(x0, y0, x1, y1, mix(tone, 0x7A9AC0, .5f), 1f)
            // a sky over a hill
            rect(x0, y0, x1, y0 + (y1 - y0) * .55f, 0xB8CCE0, .8f)
            poly(
                floatArrayOf(
                    x0,
                    y1,
                    x0,
                    y0 + (y1 - y0) * .62f,
                    x0 + (x1 - x0) * .45f,
                    y0 + (y1 - y0) * .4f,
                    x1,
                    y0 + (y1 - y0) * .7f,
                    x1,
                    y1,
                ),
                tone,
                .9f,
            )
            rect(x0, y0, x1, y0 + .25f, WHITE, .3f)
        }

        fun poster(x0: Float, y0: Float, x1: Float, y1: Float, tone: Int) {
            rect(x0, y0, x1, y1, PAPER, 1f)
            rect(x0 + .4f, y0 + .4f, x1 - .4f, y0 + (y1 - y0) * .62f, tone, 1f)
            ellipse(
                (x0 + x1) / 2f,
                y0 + (y1 - y0) * .34f,
                (x1 - x0) * .22f,
                (x1 - x0) * .22f,
                PAPER,
                .8f,
            )
            for (i in 0 until 3) rect(
                x0 + .5f,
                y0 + (y1 - y0) * (.7f + i * .09f),
                x1 - .5f - (i % 2) * 1f,
                y0 + (y1 - y0) * (.7f + i * .09f) + .22f,
                0x3A3A3A,
                .7f,
            )
            rect(x0, y1 - .3f, x1, y1, BLACK, .15f)
        }

        fun pinboard(x0: Float, y0: Float, x1: Float, y1: Float) {
            rect(x0 - .25f, y0 - .25f, x1 + .25f, y1 + .25f, 0x8A6A4A, 1f)
            rect(x0, y0, x1, y1, 0xB89A6A, 1f)
            rect(x0 + .4f, y0 + .5f, x0 + 2f, y0 + 2.2f, 0xFFE07A, 1f)
            rect(x0 + 2.5f, y0 + .8f, x1 - .4f, y0 + 2.6f, 0xF4F4F0, 1f)
            rect(x0 + .6f, y0 + 2.7f, x0 + 2.8f, y1 - .4f, 0x9AD0F0, 1f)
            rect(x0 + 3.2f, y0 + 3f, x1 - .6f, y1 - .5f, 0xF4F4F0, 1f)
        }

        fun mirror(x0: Float, y0: Float, x1: Float, y1: Float) {
            rect(x0 - .3f, y0 - .3f, x1 + .3f, y1 + .3f, METAL, 1f)
            rect(x0, y0, x1, y1, GLASS, .55f)
            poly(
                floatArrayOf(
                    x0 + .3f,
                    y1 - .3f,
                    x0 + .3f,
                    y0 + (y1 - y0) * .5f,
                    x0 + (x1 - x0) * .5f,
                    y0 + .3f,
                    x0 + (x1 - x0) * .8f,
                    y0 + .3f,
                ),
                WHITE,
                .14f,
            )
        }

        fun clock(cx: Float, cy: Float) {
            ellipse(cx, cy, 1.6f, 1.6f, 0x2A2A30, 1f)
            ellipse(cx, cy, 1.3f, 1.3f, PAPER, 1f)
            capsule(cx, cy, .12f, cx + .1f, cy - .9f, .1f, 0x202020, 1f)
            capsule(cx, cy, .12f, cx + .7f, cy + .2f, .1f, 0x202020, 1f)
        }

        fun mug(x: Float, y: Float, color: Int) {
            rect(x, y - 1.2f, x + 1.1f, y, color, 1f)
            rect(x, y - 1.2f, x + 1.1f, y - 1f, WHITE, .3f)
            capsule(x + 1.1f, y - .9f, .15f, x + 1.5f, y - .5f, .15f, color, 1f)
        }

        fun jar(x: Float, y: Float, color: Int) {
            rect(x - .6f, y - 1.6f, x + .6f, y, color, .9f)
            rect(x - .6f, y - 1.9f, x + .6f, y - 1.6f, 0x3A3A3A, 1f)
            rect(x - .5f, y - 1.5f, x - .3f, y - .2f, WHITE, .3f)
        }

        fun bottle(x: Float, y: Float, color: Int) {
            rect(x - .4f, y - 1.8f, x + .4f, y, color, 1f)
            rect(x - .2f, y - 2.4f, x + .2f, y - 1.8f, color, 1f)
            rect(x - .3f, y - 1.6f, x - .15f, y - .3f, WHITE, .35f)
        }

        fun candle(x: Float, y: Float) {
            rect(x - .35f, y - 1.6f, x + .35f, y, PAPER, 1f)
            ellipse(x, y - 1.9f, .18f, .3f, 0xFFD070, 1f)
        }

        fun vase(x: Float, y: Float, color: Int) {
            poly(
                floatArrayOf(
                    x - .9f,
                    y,
                    x + .9f,
                    y,
                    x + .6f,
                    y - 2.2f,
                    x + .3f,
                    y - 3f,
                    x - .3f,
                    y - 3f,
                    x - .6f,
                    y - 2.2f,
                ),
                color,
                1f,
            )
            rect(x - .6f, y - 2f, x - .4f, y - .4f, WHITE, .25f)
            for (i in 0 until 3) capsule(
                x,
                y - 3f,
                .1f,
                x - 1f + i * 1f,
                y - 5.2f + (i % 2) * .6f,
                .08f,
                LEAF2,
                1f,
            )
        }

        fun towel(x0: Float, y: Float, x1: Float, color: Int) {
            poly(floatArrayOf(x0, y, x1, y, x1 - .2f, y + 4.6f, x0 + .3f, y + 4.2f), color, 1f)
            rect(x0 + .1f, y + 1.2f, x1 - .1f, y + 1.5f, WHITE, .3f)
        }

        fun hanging(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
            capsule(x0 - .3f, y0, .12f, x1 + .3f, y0, .12f, sc.wood, 1f)
            poly(
                floatArrayOf(x0, y0, x1, y0, x1, y1 - 1.8f, (x0 + x1) / 2f, y1, x0, y1 - 1.8f),
                color,
                1f,
            )
            rect(x0 + .3f, y0 + 1.2f, x1 - .3f, y0 + 1.6f, PAPER, .5f)
            ellipse(
                (x0 + x1) / 2f,
                y0 + (y1 - y0) * .45f,
                (x1 - x0) * .25f,
                (x1 - x0) * .25f,
                PAPER,
                .6f,
            )
        }

        fun hooks(x: Float, y: Float, wood: Int, coat: Int) {
            box(x, y, x + 6f, y + .6f, wood, 1f)
            for (i in 0 until 3) capsule(
                x + 1f + i * 2f,
                y + .6f,
                .15f,
                x + 1f + i * 2f,
                y + 1.4f,
                .2f,
                METAL,
                1f,
            )
            poly(
                floatArrayOf(
                    x + .4f,
                    y + 1.4f,
                    x + 1.6f,
                    y + 1.4f,
                    x + 2.3f,
                    y + 9f,
                    x - .3f,
                    y + 9f,
                ),
                coat,
                1f,
            )
            poly(
                floatArrayOf(
                    x + 4.4f,
                    y + 1.4f,
                    x + 5.6f,
                    y + 1.4f,
                    x + 6.3f,
                    y + 7f,
                    x + 3.7f,
                    y + 7f,
                ),
                0x3A4A5A,
                1f,
            )
        }

        fun kettlebell(x: Float, y: Float, color: Int) {
            ellipse(x, y - 1.6f, 1.6f, 1.5f, 0x202428, 1f)
            ellipse(x - .5f, y - 2.1f, .5f, .35f, WHITE, .2f)
            capsule(x - 1.1f, y - 2.6f, .3f, x - .6f, y - 4f, .3f, 0x303438, 1f)
            capsule(x - .6f, y - 4f, .3f, x + .6f, y - 4f, .3f, 0x303438, 1f)
            capsule(x + .6f, y - 4f, .3f, x + 1.1f, y - 2.6f, .3f, 0x303438, 1f)
            rect(x - 1.2f, y - 1.4f, x + 1.2f, y - 1f, color, .9f)
        }

        fun mat(x0: Float, x1: Float, color: Int) {
            poly(floatArrayOf(x0, -.2f, x1, -.2f, x1 - 1.4f, -1.6f, x0 + 1.4f, -1.6f), color, 1f)
            rect(x0 + 1.2f, -1.6f, x1 - 1.2f, -1.3f, WHITE, .2f)
            ellipse(x1 - .8f, -1f, .9f, .9f, dark(color, .8f), 1f)
        }

        fun cushion(x: Float, y: Float, color: Int) {
            ellipse(x, y - .9f, 2f, .9f, color, 1f)
            ellipse(x - .4f, y - 1.3f, 1f, .3f, WHITE, .2f)
        }

        fun stool(x: Float, wood: Int) {
            box(x - 3f, -7.4f, x + 3f, -6.2f, wood, 1f)
            rect(x - 2.4f, -6.2f, x - 1.6f, 0f, wood, 1f)
            rect(x + 1.6f, -6.2f, x + 2.4f, 0f, wood, 1f)
        }

        fun lampOnTable(x: Float, y: Float, color: Int) {
            capsule(x, y, .25f, x, y - 2.4f, .2f, 0x2A2A30, 1f)
            poly(
                floatArrayOf(
                    x - 1.6f,
                    y - 2.4f,
                    x + 1.6f,
                    y - 2.4f,
                    x + 1f,
                    y - 4.4f,
                    x - 1f,
                    y - 4.4f,
                ),
                PAPER,
                1f,
            )
            layer = LAMP
            glow(x, y - 3.4f, 4.5f, WARM, .7f)
            glow(x, y - 3.4f, 1.6f, WHITE, .6f)
            layer = BASE
        }

        fun lampStand(x: Float, shade: Int, wood: Int) {
            ellipse(x, -.2f, 1.6f, .45f, wood, 1f)
            capsule(x, -.4f, .18f, x, -16f, .14f, wood, 1f)
            poly(
                floatArrayOf(x - 2.4f, -16f, x + 2.4f, -16f, x + 1.6f, -19.6f, x - 1.6f, -19.6f),
                shade,
                1f,
            )
            layer = LAMP
            glow(x, -17.8f, 6f, WARM, .55f)
            layer = BASE
        }

        fun guitarStand(x: Float, y: Float, color: Int) {
            capsule(x - 1.4f, y, .15f, x, y - 3f, .15f, 0x303030, 1f)
            capsule(x + 1.4f, y, .15f, x, y - 3f, .15f, 0x303030, 1f)
            ellipse(x, y - 3.2f, 2.2f, 2.7f, color, 1f)
            ellipse(x, y - 7f, 1.9f, 2.2f, color, 1f)
            ellipse(x, y - 3.6f, .8f, .8f, 0x202020, 1f)
            capsule(x, y - 8f, .3f, x + .4f, y - 15f, .25f, 0x3A2A1A, 1f)
            ellipse(x - .6f, y - 6f, .5f, .9f, WHITE, .14f)
        }

        fun keyboard(x0: Float, x1: Float, wood: Int) {
            box(x0, -9.4f, x1, -8.2f, 0x202024, 1f)
            rect(x0 + .3f, -9.2f, x1 - .3f, -8.6f, PAPER, 1f)
            for (i in 0 until 7) rect(
                x0 + .6f + i * 1f,
                -9.2f,
                x0 + 1f + i * 1f,
                -8.9f,
                0x151515,
                1f,
            )
            rect(x0 + 1f, -8.2f, x0 + 1.5f, 0f, METAL, 1f)
            rect(x1 - 1.5f, -8.2f, x1 - 1f, 0f, METAL, 1f)
        }

        fun metronome(x: Float, y: Float) {
            poly(
                floatArrayOf(x - .9f, y, x + .9f, y, x + .5f, y - 2.6f, x - .5f, y - 2.6f),
                0x6A3A2A,
                1f,
            )
            capsule(x, y - .4f, .08f, x + .4f, y - 2.3f, .08f, 0xE8E0D0, 1f)
        }

        fun board(x: Float, y: Float, wood: Int) {
            box(x, y - .5f, x + 3.4f, y, wood, 1f)
        }

        fun cabinets(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, v: Int) {
            box(x0, y0, x1, y1, color, 1f, .18f)
            val n = 4
            val w = (x1 - x0) / n
            for (i in 0 until n) {
                val cx0 = x0 + i * w
                rect(cx0 + .2f, y0 + .3f, cx0 + w - .2f, y1 - .3f, BLACK, .14f)
                rect(cx0 + .2f, y0 + .3f, cx0 + w - .2f, y0 + .6f, WHITE, .14f)
                capsule(cx0 + w - 1f, y1 - 2.4f, .12f, cx0 + w - 1f, y1 - 1.2f, .12f, METAL, 1f)
            }
            if (v == 1) rect(x0, y1 - .4f, x1, y1, 0x2A2A30, 1f)
            // the light under the cabinets, onto the tiles
            layer = LAMP
            for (i in 0 until 4) glow(x0 + 2f + i * 4.2f, y1 + .5f, 4f, WARM, .22f)
            layer = BASE
        }

        fun pot(cx: Float, base: Float, rx: Float, hgt: Float, color: Int) {
            poly(
                floatArrayOf(
                    cx - rx,
                    base,
                    cx + rx,
                    base,
                    cx + rx * 1.15f,
                    base - hgt,
                    cx - rx * 1.15f,
                    base - hgt,
                ),
                color,
                1f,
            )
            rect(cx - rx * 1.15f, base - hgt, cx + rx * 1.15f, base - hgt + .4f, WHITE, .2f)
            rect(cx + rx * .4f, base - hgt + .5f, cx + rx * .9f, base - .3f, BLACK, .2f)
        }

        fun leaves(cx: Float, cy: Float, n: Int, r: Float) {
            for (i in 0 until n) {
                val a = -150f + 120f * i / max(1, n - 1)
                val ra = Math.toRadians(a.toDouble())
                val lx = cx + (kotlin.math.sin(ra) * r).toFloat()
                val ly = cy - (kotlin.math.cos(ra) * r).toFloat() * 1.05f
                ellipse(lx, ly, r * .5f, r * .27f, if (i % 2 == 0) LEAF else LEAF2, 1f, a - 90f)
                ellipse(lx - r * .1f, ly - r * .08f, r * .22f, r * .09f, WHITE, .14f, a - 90f)
            }
            ellipse(cx, cy, r * .3f, r * .3f, LEAF, 1f)
        }

        fun plantPot(cx: Float, base: Float, n: Int, r: Float) {
            pot(cx, base, r * .55f, r * 1.2f, POT)
            leaves(cx, base - r * 1.2f, n, r)
        }

        fun plantSmall(cx: Float, base: Float) {
            pot(cx, base, .6f, 1.1f, 0xC08860)
            for (i in 0 until 3) capsule(
                cx,
                base - 1.1f,
                .2f,
                cx - 1f + i * 1f,
                base - 2.6f - (i % 2) * .5f,
                .3f,
                if (i == 1) LEAF2 else LEAF,
                1f,
            )
        }
    }
}
