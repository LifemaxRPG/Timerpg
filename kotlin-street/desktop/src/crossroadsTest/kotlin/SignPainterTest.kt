package com.lifemax.rpg.feature.habits.domain.signs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO

class SignPainterTest {
    private data class ProofDemo(
        val text: String,
        val era: Int,
        val w: Int,
        val h: Int,
        val lines: Int,
        val face: SignFace,
        val paper: Int,
        val ink: Int,
        val outline: Int?,
        val align: SignAlign,
        val glow: Boolean,
    )

    @Test
    fun everyGlyphMatchesStatedHeightAndRequiredCoverage() {
        val requiredCommon = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789&.,'-! "
        val requiredLower = "abcdefghijklmnopqrstuvwxyz"

        for (font in PixelFont.ALL) {
            assertTrue("${font.name} must define glyphs", font.glyphs.isNotEmpty())
            for ((ch, glyph) in font.glyphs) {
                assertEquals(
                    "${font.name} glyph '$ch' height must equal font height ${font.height}",
                    font.height,
                    glyph.height,
                )
                assertEquals(
                    "${font.name} glyph '$ch' rows.size must equal font height ${font.height}",
                    font.height,
                    glyph.rows.size,
                )
                assertTrue("${font.name} glyph '$ch' width must be positive", glyph.width > 0)
            }
            for (ch in requiredCommon) {
                assertNotNull("${font.name} missing required glyph '$ch'", font.glyphOrNull(ch))
            }
            if (font.supportsLowercase) {
                for (ch in requiredLower) {
                    assertNotNull("${font.name} missing lowercase glyph '$ch'", font.glyphOrNull(ch))
                }
            } else {
                // Uppercase-only faces must fold lowercase input to uppercase without returning null
                for (ch in requiredLower) {
                    assertEquals(
                        "${font.name} must fold '$ch' to '${ch.uppercaseChar()}'",
                        font.glyphOrNull(ch.uppercaseChar()),
                        font.glyphOrNull(ch),
                    )
                }
            }
        }

        assertEquals(PixelFont.SERIF_BLOCK, PixelFont.forEra(1925))
        assertEquals(PixelFont.ROUNDED, PixelFont.forEra(1958))
        assertEquals(PixelFont.SANS, PixelFont.forEra(1986))
    }

    @Test
    fun noGlyphPixelFallsOutsideRectAndPaperCoversOldName() {
        val stride = 160
        val height = 80
        val sentinel = 0xFFFF00FFL.toInt() // bright magenta stands in for an old baked sign + surrounding atlas
        val paper = 0xFFF3E8D2L.toInt()
        val ink = 0xFF201812L.toInt()
        val outline = 0xFF0A0806L.toInt()
        val glowColor = 0xFFFF6644L.toInt()
        val rect = SignRect(16, 18, 112, 30)

        val spec = SignSpec(
            id = "main_sign",
            rect = rect,
            lines = 2,
            face = SignFace.ROUNDED,
            ink = ink,
            paper = paper,
            outline = outline,
            align = SignAlign.CENTER,
            glow = true,
            glowColor = glowColor,
        )

        val samples = listOf(
            "Bob's Diner",
            "Pruitt & Sons Funeral Home",
            "Bartholomew Henderson and Sons General Merchandise Emporium",
        )

        for (text in samples) {
            val out = IntArray(stride * height) { sentinel }
            val glow = IntArray(stride * height) { sentinel }
            val painted = SignPainter.paint(spec, text, 1958, out, stride, glow, stride)
            assertTrue("paint must succeed for '$text'", painted)

            var inkPixels = 0
            var glowPixels = 0
            for (y in 0 until height) {
                for (x in 0 until stride) {
                    val inside = x >= rect.x && x < rect.x + rect.width && y >= rect.y && y < rect.y + rect.height
                    val px = out[y * stride + x]
                    val gx = glow[y * stride + x]
                    if (!inside) {
                        assertEquals("pixel outside rect at ($x,$y) must be untouched for '$text'", sentinel, px)
                        assertEquals("glow pixel outside rect at ($x,$y) must be untouched for '$text'", sentinel, gx)
                    } else {
                        assertNotEquals("old baked pixel at ($x,$y) must be covered for '$text'", sentinel, px)
                        assertNotEquals("old glow pixel at ($x,$y) must be cleared for '$text'", sentinel, gx)
                        if (gx == glowColor) glowPixels++
                        if (px == ink) {
                            inkPixels++
                            assertEquals("glow mask must match letter pixel at ($x,$y)", glowColor, gx)
                        } else {
                            assertEquals("non-ink pixel at ($x,$y) must be unlit in glow mask", 0xFF000000L.toInt(), gx)
                        }
                    }
                }
            }
            assertTrue("expected painted ink pixels for '$text'", inkPixels > 10)
            assertEquals("glow pixels must match ink pixels for '$text'", inkPixels, glowPixels)
        }
    }

    @Test
    fun longNameShrinksWrapsAndAbbreviatesInsteadOfOverflowing() {
        val stride = 160
        val height = 60
        val sentinel = 0xFF00FF00L.toInt()
        val scratch = PaintScratch()

        // 1. Short name scales up to 2x or 3x on a roomy board
        val bigSpec = SignSpec(id = "big", rect = SignRect(10, 10, 138, 36), lines = 1, face = SignFace.ROUNDED)
        val out = IntArray(stride * height) { sentinel }
        SignPainter.paint(bigSpec, "MOTEL", 1958, out, stride, scratch = scratch)
        assertEquals("short name on 138x36 board should scale to 3x", 3, scratch.lastScale)
        assertEquals(1, scratch.lastLineCount)

        // 2. Medium two-line name wraps cleanly
        val wrapSpec = SignSpec(id = "wrap", rect = SignRect(10, 10, 112, 26), lines = 2, face = SignFace.SANS)
        out.fill(sentinel)
        SignPainter.paint(wrapSpec, "Bartholomew Henderson Funeral Home", 1984, out, stride, scratch = scratch)
        assertEquals(2, scratch.lastLineCount)
        assertEquals(1, scratch.lastScale)

        // 3. Tight board forces "&" and suffix abbreviation before clamping, with zero overflow
        val tightSpec = SignSpec(id = "tight", rect = SignRect(10, 10, 72, 20), lines = 2, face = SignFace.SERIF_BLOCK)
        out.fill(sentinel)
        SignPainter.paint(tightSpec, "Pruitt and Sons General Store", 1942, out, stride, scratch = scratch)
        val combined = (scratch.debugLine(0) + " " + scratch.debugLine(1)).trim()
        assertTrue("expected 'and' abbreviated to '&' in '$combined'", combined.contains("&"))
        assertFalse("expected 'GENERAL STORE' abbreviated in '$combined'", combined.contains("GENERAL"))
        for (y in 0 until height) {
            for (x in 0 until stride) {
                val inside = x in 10 until 82 && y in 10 until 30
                if (!inside) assertEquals(sentinel, out[y * stride + x])
            }
        }
    }

    @Test
    fun signSpecParsesModelInfoNamingAndLegacySignRegions() {
        val ownerJson = """
            {
              "naming": "owner",
              "signs": [
                {
                  "id": "sign_name",
                  "texture": "signs.png",
                  "rect": [0, 0, 138, 36],
                  "lines": 2,
                  "maxChars": 28,
                  "face": "rounded",
                  "ink": "#FF5A4E",
                  "paper": "#1B2E3C",
                  "outline": "#0D171F",
                  "align": "center",
                  "glow": true
                }
              ]
            }
        """.trimIndent()
        val ownerCfg = SignSpec.parseJson(ownerJson)
        assertEquals(NamingMode.OWNER, ownerCfg.naming)
        assertTrue(ownerCfg.repaintable)
        assertEquals(1, ownerCfg.signs.size)
        val s = ownerCfg.signs[0]
        assertEquals("sign_name", s.id)
        assertEquals("signs.png", s.texture)
        assertEquals(SignRect(0, 0, 138, 36), s.rect)
        assertEquals(2, s.lines)
        assertEquals(28, s.maxChars)
        assertEquals(SignFace.ROUNDED, s.face)
        assertEquals(0xFFFF5A4EL.toInt(), s.ink)
        assertEquals(0xFF1B2E3CL.toInt(), s.paper)
        assertEquals(0xFF0D171FL.toInt(), s.outline)
        assertEquals(SignAlign.CENTER, s.align)
        assertTrue(s.glow)

        // Missing naming defaults to FIXED and is never repainted by paintIfAllowed
        val fixedJson = """{ "signs": [{ "id": "plaque", "rect": [0, 0, 64, 16] }] }"""
        val fixedCfg = SignSpec.parseModelInfo(fixedJson)
        assertEquals(NamingMode.FIXED, fixedCfg.naming)
        assertFalse(fixedCfg.repaintable)
        val buf = IntArray(64 * 16) { 0xFF112233L.toInt() }
        assertFalse(SignPainter.paintIfAllowed(fixedCfg, fixedCfg.signs[0], "Bob's Courthouse", 1955, buf, 64))
        assertEquals(0xFF112233L.toInt(), buf[0])

        // Legacy sign-regions.json shape
        val legacyJson = """{ "front_sign": [4, 8, 120, 28], "side_sign": [4, 40, 80, 18] }"""
        val legacyCfg = SignSpec.parseJson(legacyJson)
        assertEquals(NamingMode.OWNER, legacyCfg.naming)
        assertEquals(2, legacyCfg.signs.size)
        assertEquals("front_sign", legacyCfg.signs[0].id)
        assertEquals(SignRect(4, 8, 120, 28), legacyCfg.signs[0].rect)
        assertEquals(2, legacyCfg.signs[0].lines)
        assertEquals(1, legacyCfg.signs[1].lines)
    }

    @Test
    fun signNamesAreDeterministicPerSeedAndRespondToOwnerAndEra() {
        val kinds = listOf(
            "barber",
            "funeral_home",
            "diner",
            "five_and_dime",
            "salon",
            "drugstore",
            "gas_station",
            "motel",
            "laundromat",
            "hardware",
        )
        for (kind in kinds) {
            val a = SignNames.nameFor(kind, "Hollis", "Bob", 1956, "town-alpha")
            val b = SignNames.nameFor(kind, "Hollis", "Bob", 1956, "town-alpha")
            assertEquals("nameFor must be deterministic for $kind", a, b)
            assertTrue("name '$a' must include owner surname or first name", a.contains("Hollis") || a.contains("Bob"))
        }

        val owner1 = SignNames.nameFor("barber", "Hollis", "Arthur", 1935, "seed-1")
        val owner2 = SignNames.nameFor("barber", "Pruitt", "Claire", 1935, "seed-1")
        assertNotEquals("changing owner must change sign name", owner1, owner2)

        val earlySalon = SignNames.nameFor("salon", "Ashby", "Mabel", 1932, "seed-2")
        val modernSalon = SignNames.nameFor("salon", "Ashby", "Mabel", 1998, "seed-2")
        assertNotEquals("era shift should update period naming style", earlySalon, modernSalon)
    }

    @Test
    fun paintAllocatesZeroBytesAfterWarmup() {
        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!mx.isThreadAllocatedMemorySupported) return

        val stride = 160
        val out = IntArray(stride * 48)
        val glow = IntArray(stride * 48)
        val scratch = PaintScratch()
        val specs = arrayOf(
            SignSpec("s1", rect = SignRect(4, 4, 138, 36), lines = 2, face = SignFace.SERIF_BLOCK, outline = 0xFF101010L.toInt()),
            SignSpec("s2", rect = SignRect(4, 4, 138, 36), lines = 1, face = SignFace.ROUNDED, outline = 0xFF0D171FL.toInt(), glow = true),
            SignSpec("s3", rect = SignRect(4, 4, 120, 30), lines = 2, face = SignFace.SANS, glow = true),
            SignSpec("s4", rect = SignRect(4, 4, 72, 20), lines = 2, face = SignFace.SERIF_BLOCK, maxChars = 22),
        )
        val texts = arrayOf(
            "Pruitt & Sons Funeral Home",
            "Bob's Diner",
            "Kessler Nail Room",
            "Pruitt and Sons General Store",
        )
        val eras = intArrayOf(1932, 1956, 1998, 1942)

        // Warm up JIT + ThreadLocal/scratch buffers
        repeat(500) { i ->
            val k = i and 3
            SignPainter.paint(specs[k], texts[k], eras[k], out, stride, glow, stride, scratch)
        }
        // Also warm up the ThreadLocal default path
        repeat(100) { i ->
            val k = i and 3
            SignPainter.paint(specs[k], texts[k], eras[k], out, stride, glow, stride)
        }

        mx.currentThreadAllocatedBytes
        val before = mx.currentThreadAllocatedBytes
        repeat(1000) { i ->
            val k = i and 3
            SignPainter.paint(specs[k], texts[k], eras[k], out, stride, glow, stride)
        }
        val delta = mx.currentThreadAllocatedBytes - before
        assertEquals("SignPainter.paint must allocate 0 bytes over 1000 calls (got $delta B)", 0L, delta)
    }

    private fun rgb(hex: Int): Int = (0xFF000000L or (hex.toLong() and 0xFFFFFFL)).toInt()

    @Test
    fun rendersTwentySignProofSheetPng() {
        val demos = listOf(
            // 1900–1940s SERIF_BLOCK
            ProofDemo("Hollis Barber Shop", 1925, 138, 36, 2, SignFace.SERIF_BLOCK, rgb(0xF3E8D2), rgb(0x261C15), null, SignAlign.CENTER, false),
            ProofDemo("Pruitt & Sons Funeral Home", 1932, 138, 36, 2, SignFace.SERIF_BLOCK, rgb(0x1B2228), rgb(0xE5C67B), rgb(0x0B0E11), SignAlign.CENTER, false),
            ProofDemo("Marlow's Five and Dime", 1938, 138, 36, 2, SignFace.SERIF_BLOCK, rgb(0x7A1C1C), rgb(0xF7EAD0), rgb(0x380B0B), SignAlign.CENTER, false),
            ProofDemo("Kessler Apothecary", 1918, 120, 26, 1, SignFace.SERIF_BLOCK, rgb(0x1D3326), rgb(0xE8D8A6), null, SignAlign.CENTER, false),
            ProofDemo("Pruitt & Sons Hardware", 1928, 96, 24, 2, SignFace.SERIF_BLOCK, rgb(0xE7DBC5), rgb(0x2A1D15), null, SignAlign.CENTER, false),
            ProofDemo("C. Hollis Tonsorial", 1912, 72, 20, 2, SignFace.SERIF_BLOCK, rgb(0x231D1A), rgb(0xE6D6B8), null, SignAlign.CENTER, false),
            // 1950s–1960s ROUNDED (1x, 2x, 3x + glow)
            ProofDemo("Bob's Diner", 1956, 138, 36, 1, SignFace.ROUNDED, rgb(0x1B2E3C), rgb(0xFF5A4E), rgb(0x0D171F), SignAlign.CENTER, true),
            ProofDemo("Pruitt Motor Court", 1958, 138, 36, 2, SignFace.ROUNDED, rgb(0x194D4A), rgb(0xFFF1C2), rgb(0x0D2927), SignAlign.CENTER, true),
            ProofDemo("Mabel's Drive-In", 1962, 120, 28, 1, SignFace.ROUNDED, rgb(0xF7EFE1), rgb(0xC82828), rgb(0x521010), SignAlign.CENTER, true),
            ProofDemo("MOTEL", 1964, 138, 36, 1, SignFace.ROUNDED, rgb(0x1A2240), rgb(0xFFD24C), rgb(0x0A0F20), SignAlign.CENTER, true),
            ProofDemo("Kessler Coin Laundry", 1959, 120, 30, 2, SignFace.ROUNDED, rgb(0xE9F2F5), rgb(0x1B4B72), null, SignAlign.CENTER, false),
            ProofDemo("Bob's Gas & Oil", 1955, 96, 24, 1, SignFace.ROUNDED, rgb(0xF4ECD8), rgb(0xB8261E), null, SignAlign.CENTER, false),
            ProofDemo("Marlow's 5 & 10", 1953, 72, 20, 2, SignFace.ROUNDED, rgb(0xA81D1D), rgb(0xFFF5DC), null, SignAlign.CENTER, false),
            // 1970s–today SANS (mixed case, 1-line, 2-line, left align, long wrap, tight abbreviation)
            ProofDemo("Ashby Hair Co.", 1984, 138, 36, 1, SignFace.SANS, rgb(0xF4F1EB), rgb(0x222226), null, SignAlign.CENTER, false),
            ProofDemo("Kessler Nail Room", 1998, 138, 36, 2, SignFace.SANS, rgb(0x2A2433), rgb(0xF5D8E6), null, SignAlign.CENTER, true),
            ProofDemo("Pruitt Family Pharmacy", 1988, 138, 36, 2, SignFace.SANS, rgb(0xF5F7F8), rgb(0x14524A), null, SignAlign.CENTER, false),
            ProofDemo("Bob's Video & Tape", 1986, 120, 30, 2, SignFace.SANS, rgb(0x1A1D36), rgb(0x67E2FF), rgb(0x0A0B18), SignAlign.CENTER, true),
            ProofDemo("Hollis Phone Repair", 2015, 120, 28, 2, SignFace.SANS, rgb(0x1E2228), rgb(0xF2F5F7), null, SignAlign.LEFT, false),
            ProofDemo("Bartholomew Henderson Funeral Home", 1978, 112, 26, 2, SignFace.SANS, rgb(0xE8E3DA), rgb(0x23201E), null, SignAlign.CENTER, false),
            ProofDemo("Pruitt and Sons General Store", 1942, 72, 20, 2, SignFace.SERIF_BLOCK, rgb(0xEFE5D0), rgb(0x2B1F16), null, SignAlign.CENTER, false),
        )

        val sheetW = 484
        val sheetH = 480
        val bg = rgb(0x1E2026)
        val frame = rgb(0x4B4E56)
        val glowFrame = rgb(0x415F6E)
        val pixels = IntArray(sheetW * sheetH) { bg }
        val glowSheet = IntArray(sheetW * sheetH) { 0xFF000000L.toInt() }

        val colX = intArrayOf(14, 174)
        val colY = intArrayOf(14, 14)
        val glowRects = ArrayList<SignRect>()

        for ((idx, d) in demos.withIndex()) {
            val col = if (idx < 10) 0 else 1
            val rx = colX[col]
            val ry = colY[col]
            drawFrame(pixels, sheetW, rx, ry, d.w, d.h, frame)
            val rect = SignRect(rx, ry, d.w, d.h)
            val spec = SignSpec(
                id = "demo_${idx + 1}",
                rect = rect,
                lines = d.lines,
                face = d.face,
                ink = d.ink,
                paper = d.paper,
                outline = d.outline,
                align = d.align,
                glow = d.glow,
            )
            assertTrue(SignPainter.paint(spec, d.text, d.era, pixels, sheetW, glowSheet, sheetW))
            if (d.glow) glowRects.add(rect)
            colY[col] += d.h + 10
        }

        // Side-by-side glow masks on the right column
        var gy = 14
        val gx = 332
        for (r in glowRects) {
            drawFrame(pixels, sheetW, gx, gy, r.width, r.height, glowFrame)
            for (dy in 0 until r.height) {
                for (dx in 0 until r.width) {
                    pixels[(gy + dy) * sheetW + (gx + dx)] = glowSheet[(r.y + dy) * sheetW + (r.x + dx)]
                }
            }
            gy += r.height + 10
        }

        val img = BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, sheetW, sheetH, pixels, 0, sheetW)

        val outFile = resolveProofSheetFile()
        outFile.parentFile?.mkdirs()
        assertTrue("failed to write proof sheet PNG to $outFile", ImageIO.write(img, "png", outFile))
        assertTrue("proof sheet PNG must be non-empty", outFile.length() > 1000L)
    }

    private fun drawFrame(buf: IntArray, stride: Int, x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (fx in (x - 1)..(x + w)) {
            buf[(y - 1) * stride + fx] = color
            buf[(y + h) * stride + fx] = color
        }
        for (fy in (y - 1)..(y + h)) {
            buf[fy * stride + (x - 1)] = color
            buf[fy * stride + (x + w)] = color
        }
    }

    private fun resolveProofSheetFile(): File {
        val prop = System.getProperty("signProofSheetOut")
        if (!prop.isNullOrBlank()) return File(prop)
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val protoDir = File(dir, "prototypes/G006")
            if (protoDir.isDirectory || File(dir, "docs/queue").isDirectory) {
                return File(protoDir, "proof-sheet.png")
            }
            dir = dir.parentFile
        }
        return File("build/sign-proof-sheet.png")
    }
}
