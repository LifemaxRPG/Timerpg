package com.lifemax.rpg.feature.habits.maps

import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The planet's two wide tiers (docs/MAPS.md) from the baked world skeleton, in the town's own frame (street units, so
 * the player, the waypoint and the city map share one set of coordinates; the planet is unwrapped round the town):
 * - **world** ([world]): the whole planet, the continents and seas, the capitals and big cities, the main railways and
 *   the shipping lanes; no roads, no small towns;
 * - **regional** ([regional]): the town's continent (its landmass) and the sea round it: every town and village, the
 *   highways and county roads (with their route numbers as labels), the railways, the rivers, the natural wonders and
 *   the roadside's truck stops and diners.
 *
 * [printYear] is the year the map was printed: the skeleton knows when roads were built, upgraded and abandoned
 * (Highways v2) and when towns were founded, so a 1950 road map lacks the 1964 expressway (the rail lines have no years
 * in the skeleton yet: all are drawn).
 */
class PlanetMapModel private constructor(val tier: MapTier, val raster: MapRaster, val labels: List<MapLabel>, val printYear: Int) {

    companion object {
        private val SEA = MapRaster.argb(0x2B5D8C)
        private val SEA_DEEP = MapRaster.argb(0x234E78)
        private val COAST = MapRaster.argb(0x1B2B33)
        private val LAKE = MapRaster.argb(0x3D7FCB)
        private val RIVER = MapRaster.argb(0x4F92D8)
        private val ROAD = MapRaster.argb(0xE9E1C8)
        private val HIGHWAY = MapRaster.argb(0xF2C14E)
        private val EXPRESSWAY = MapRaster.argb(0xE8743B)
        private val RAIL = MapRaster.argb(0x2A2622)
        private val TOWN = MapRaster.argb(0x101010)
        private val SHIP = MapRaster.argb(0xC9DDEE)
        /** Flat land colours by biome (WorldSkeleton.BIOMES' order). */
        private val BIOME = intArrayOf(
            0x2B5D8C, 0xE8EEF2, 0x9DA98E, 0x4E7350, 0x5E8A4A, 0x4C7C4E, 0xA7B865, 0xB9B477, 0xD9C38A, 0xC2B25E,
            0x3F7A3A, 0x2F6A34, 0x9A968C, 0x6C8D6A, 0x3D7FCB, 0xE3D3A0, 0x8C877E, 0x9C9A62, 0xF2F6F8,
        ).map { MapRaster.argb(it) }.toIntArray()

        /** The street units of a cell. */
        private fun cellU(site: WorldSite): Double = Planet.CELL_M * site.frame.unitsPerMetre

        /** Street position of atlas point ([cx], [cz]), unwrapped round the town. */
        private fun sx(site: WorldSite, cx: Double): Double = site.frame.origin.dx(WorldPos.ofCells(cx, 0.0)) * site.frame.unitsPerMetre
        private fun sz(site: WorldSite, cz: Double): Double = (cz * Planet.CELL_M - site.frame.origin.z) * site.frame.unitsPerMetre

        /** A path's points (atlas cells) as street x, z pairs: the first wrapped near the town, the rest following it. */
        private fun streetPath(site: WorldSite, path: IntArray?): FloatArray {
            val p = site.skeleton.points(path)
            if (p.isEmpty()) return FloatArray(0)
            val u = cellU(site)
            val x0 = sx(site, p[0])
            return FloatArray(p.size) { k -> if (k % 2 == 0) (x0 + (p[k] - p[0]) * u).toFloat() else sz(site, p[k]).toFloat() }
        }

        /** The land of cells [i0]..[i1) x [j0]..[j1) (unwrapped columns) at [ppc] pixels a cell, with the coast a pixel dark. */
        private fun land(site: WorldSite, i0: Int, j0: Int, i1: Int, j1: Int, ppc: Int, keepMass: Int?): MapRaster {
            val sk = site.skeleton
            val u = cellU(site).toFloat()
            val r = MapRaster((i1 - i0) * ppc, (j1 - j0) * ppc, u / ppc, sx(site, i0.toDouble()).toFloat(), sz(site, j0.toDouble()).toFloat())
            for (j in j0 until j1) for (i in i0 until i1) {
                val c = when {
                    !sk.land(i, j) -> if (sk.elev(i, j) < -400) SEA_DEEP else SEA
                    // (another landmass on a road map: drawn, but dimmer, as a printed map greys what it does not cover)
                    keepMass != null && sk.mass(i, j) != keepMass -> MapRaster.shade(BIOME[sk.biome(i, j).coerceIn(0, BIOME.size - 1)], .72f)
                    else -> {
                        val b = BIOME[sk.biome(i, j).coerceIn(0, BIOME.size - 1)]
                        // (high ground a step lighter, the way relief tints a printed map)
                        val e = sk.elev(i, j)
                        if (e > 1800) MapRaster.shade(b, 1.18f) else if (e > 900) MapRaster.shade(b, 1.08f) else b
                    }
                }
                r.rect((i - i0) * ppc, (j - j0) * ppc, (i - i0 + 1) * ppc, (j - j0 + 1) * ppc, c)
            }
            val wet = setOf(SEA, SEA_DEEP)
            r.outline({ it !in wet }, { COAST })
            return r
        }

        /** The world map of [site]'s planet printed in [printYear] ([ppc]: pixels a cell; 1 gives 1,856 x 928). */
        fun world(site: WorldSite, printYear: Int, ppc: Int = 1): PlanetMapModel {
            val sk = site.skeleton
            val hx = floor(site.hub.x).toInt()
            val i0 = hx - WorldSkeleton.W / 2; val i1 = i0 + WorldSkeleton.W
            val r = land(site, i0, 0, i1, WorldSkeleton.H, ppc, null)
            val labels = ArrayList<MapLabel>()
            // the shipping lanes: straight dashes between the ports they join
            for (s in sk.sea) {
                val a = sk.place(s.a ?: continue) ?: continue; val b = sk.place(s.b ?: continue) ?: continue
                val ax = sx(site, a.x).toFloat(); val az = sz(site, a.z).toFloat()
                var bx = sx(site, b.x).toFloat(); val bz = sz(site, b.z).toFloat()
                val c = (WorldSkeleton.W * cellU(site)).toFloat()
                if (bx - ax > c / 2) bx -= c else if (ax - bx > c / 2) bx += c
                r.dashedU(floatArrayOf(ax, az, bx, bz), 1f, 3f, 3f, SHIP)
            }
            // the main railways
            for (l in sk.railLines) if (l.cls == "main") r.polylineU(streetPath(site, l.path), max(1f, ppc.toFloat()), RAIL)
            // the capitals and big cities
            for (p in sk.places) {
                if (p.founded > printYear && p.founded != 0) continue
                val big = p.type == "capital" || p.rank == "major" || p.hub
                if (!big) continue
                val x = sx(site, p.x).toFloat(); val z = sz(site, p.z).toFloat()
                r.disc(r.toPxX(x), r.toPxY(z), if (p.type == "capital") 3f else 2f, TOWN)
                labels += MapLabel(p.name, x, z, if (p.type == "capital") LabelKind.CAPITAL else LabelKind.CITY, 0f, if (p.type == "capital") 1000f else 100f + p.mass * 0f)
            }
            return PlanetMapModel(MapTier.WORLD, r, labels, printYear)
        }

        /**
         * The road map of the town's continent printed in [printYear]: the landmass under the town and a margin of sea
         * ([ppc] pixels a cell: 3 by default, about 360 m a pixel).
         */
        fun regional(site: WorldSite, printYear: Int, ppc: Int = 3): PlanetMapModel {
            val sk = site.skeleton
            val hx = floor(site.hub.x).toInt(); val hz = floor(site.hub.z).toInt()
            val mass = sk.mass(hx, hz)
            // the landmass's extent, columns unwrapped round the town
            var a0 = Int.MAX_VALUE; var a1 = Int.MIN_VALUE; var b0 = Int.MAX_VALUE; var b1 = Int.MIN_VALUE
            for (j in 0 until WorldSkeleton.H) for (i in 0 until WorldSkeleton.W) if (sk.mass(i, j) == mass && sk.land(i, j)) {
                val du = Math.floorMod(i - hx + WorldSkeleton.W / 2, WorldSkeleton.W) - WorldSkeleton.W / 2
                a0 = min(a0, du); a1 = max(a1, du); b0 = min(b0, j); b1 = max(b1, j)
            }
            if (a0 > a1) { a0 = -60; a1 = 60; b0 = hz - 60; b1 = hz + 60 }
            val m = 14
            val i0 = hx + a0 - m; val i1 = hx + a1 + m + 1; val j0 = (b0 - m).coerceAtLeast(0); val j1 = (b1 + m + 1).coerceAtMost(WorldSkeleton.H)
            val r = land(site, i0, j0, i1, j1, ppc, mass)
            val labels = ArrayList<MapLabel>()
            fun inBox(pts: FloatArray): Boolean { for (k in 0 until pts.size / 2) if (r.contains(pts[k * 2], pts[k * 2 + 1])) return true; return false }
            // the rivers (the bigger ones)
            for (rv in sk.rivers) if (rv.order >= 3) { val p = streetPath(site, rv.path); if (inBox(p)) r.polylineU(p, if (rv.order >= 5) 2f else 1f, RIVER) }
            // the roads as they were in the print year (Highways v2), else the skeleton's roads by the year they were built
            val hw = sk.highways
            val routeLabels = HashMap<String, MapLabel>()
            if (hw != null) {
                val order = listOf("track", "county", "local", "frontage", "highway", "expressway")
                for ((s, st) in hw.at(printYear).sortedBy { order.indexOf(it.second.cls) }) {
                    if (st.cls == "abandoned") continue
                    val p = streetPath(site, s.path); if (p.size < 4 || !inBox(p)) continue
                    val (w, c) = when (st.cls) { "expressway" -> 3f to EXPRESSWAY; "highway" -> 2f to HIGHWAY; "track" -> 1f to MapRaster.shade(ROAD, .8f); else -> 1f to ROAD }
                    r.polylineU(p, w, c)
                    val n = st.number
                    if (n != null && s.km > 8.0 && (routeLabels[n]?.priority ?: -1f) < s.km.toFloat()) {
                        val k = (p.size / 4) * 2
                        routeLabels[n] = MapLabel(n, p[k], p[k + 1], LabelKind.ROUTE, 0f, s.km.toFloat())
                    }
                }
                for (site2 in hw.sites) if (site2.open(printYear) && site2.mass == mass && (site2.kind == "truck stop" || site2.kind == "diner")) {
                    val x = sx(site, site2.x).toFloat(); val z = sz(site, site2.z).toFloat()
                    r.rect(Math.round(r.toPxX(x)) - 1, Math.round(r.toPxY(z)) - 1, Math.round(r.toPxX(x)) + 1, Math.round(r.toPxY(z)) + 1, MapRaster.argb(0xD0453A))
                }
            } else for (rd in sk.roads) {
                if (rd.built > printYear) continue
                val p = streetPath(site, rd.path); if (!inBox(p)) continue
                r.polylineU(p, if (rd.cls == "highway") 2f else 1f, if (rd.cls == "highway") HIGHWAY else ROAD)
            }
            labels += routeLabels.values
            // the railways (a dashed black line)
            for (l in sk.railLines) { val p = streetPath(site, l.path); if (inBox(p)) r.dashedU(p, if (l.cls == "main") 2f else 1f, 4f, 2f, RAIL) }
            // every town and village founded by the print year
            for (p in sk.places) {
                if (p.founded > printYear && p.founded != 0) continue
                val x = sx(site, p.x).toFloat(); val z = sz(site, p.z).toFloat()
                if (!r.contains(x, z)) continue
                val big = p.type == "capital" || p.hub
                val rad = when { big -> 4f; p.rank == "major" -> 3f; p.rank == "town" -> 2f; else -> 1.5f }
                r.disc(r.toPxX(x), r.toPxY(z), rad + 1f, MapRaster.argb(0xFFFFFF))
                r.disc(r.toPxX(x), r.toPxY(z), rad, TOWN)
                labels += MapLabel(p.name, x, z, when { p.type == "capital" -> LabelKind.CAPITAL; big || p.rank == "major" -> LabelKind.CITY; else -> LabelKind.TOWN }, 0f,
                    when { p.type == "capital" -> 1000f; big -> 800f; p.rank == "major" -> 500f; p.rank == "town" -> 200f; else -> 50f })
            }
            // the natural wonders
            for (wd in sk.wonders) {
                val x = sx(site, wd.x).toFloat(); val z = sz(site, wd.z).toFloat()
                if (!r.contains(x, z)) continue
                labels += MapLabel(wd.name, x, z, LabelKind.WONDER, 0f, when (wd.tier) { "legendary" -> 450f; "rare" -> 300f; "uncommon" -> 150f; else -> 60f })
            }
            return PlanetMapModel(MapTier.REGIONAL, r, labels, printYear)
        }

        /** The id of the region (the landmass) the town of [site] stands on: the road maps' area. */
        fun regionOf(site: WorldSite): String = "mass:" + site.skeleton.mass(floor(site.hub.x).toInt(), floor(site.hub.z).toInt())
    }
}
