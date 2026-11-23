package com.lifemax.rpg.feature.habits.domain.signs

/**
 * Era-styled bitmap fonts on the art-pixel grid, matching the 7-pixel cap-height proportions of
 * `kotlin-street/tools/landmarks/pixel_font.py`.
 *
 * Three faces:
 * - [SERIF_BLOCK]: 1900–1940s architectural serif-block uppercase (7 px tall).
 * - [ROUNDED]: 1950s–1960s diner & motel rounded uppercase (7 px tall).
 * - [SANS]: 1970s–today plain sans with uppercase and lowercase (9 px tall: 7 px cap + 2 px descender).
 */
enum class SignFontStyle {
    SERIF_BLOCK,
    ROUNDED,
    SANS,
}

/**
 * One immutable bitmap glyph.
 *
 * Each entry in [rows] is a bitmask of [width] pixels, MSB-first: column `x` (`0 <= x < width`)
 * is bit `(width - 1 - x)`. Every glyph in a [PixelFont] has `height == font.height` and
 * `rows.size == font.height`.
 */
class PixelGlyph(
    val char: Char,
    val width: Int,
    val height: Int,
    val rows: IntArray,
) {
    /** True if the art pixel at `(x, y)` is lit. */
    fun pixel(x: Int, y: Int): Boolean {
        if (x < 0 || x >= width || y < 0 || y >= height) return false
        return ((rows[y] ushr (width - 1 - x)) and 1) != 0
    }

    /** True if any pixel in rows `>= fromRow` is lit (used for optical centering with descenders). */
    fun hasPixelsFromRow(fromRow: Int): Boolean {
        var y = if (fromRow < 0) 0 else fromRow
        while (y < height) {
            if (rows[y] != 0) return true
            y++
        }
        return false
    }
}

class PixelFont private constructor(
    val style: SignFontStyle,
    val name: String,
    val height: Int,
    val capHeight: Int,
    val tracking: Int,
    val lineGap: Int,
    val supportsLowercase: Boolean,
    val glyphs: Map<Char, PixelGlyph>,
) {
    // Flat ASCII table for zero-allocation O(1) lookup during SignPainter.paint().
    private val asciiTable: Array<PixelGlyph?> = arrayOfNulls(128)
    private val spaceGlyph: PixelGlyph

    init {
        for ((ch, g) in glyphs) {
            val code = ch.code
            if (code in 0 until 128) asciiTable[code] = g
        }
        spaceGlyph = glyphs[' '] ?: PixelGlyph(' ', 3, height, IntArray(height))
    }

    /** Zero-allocation lookup of [ch] (folds lowercase to uppercase for uppercase-only faces). */
    fun glyphOrNull(ch: Char): PixelGlyph? {
        var code = ch.code
        if (!supportsLowercase && code in 97..122) {
            code -= 32
        }
        return if (code in 0 until 128) asciiTable[code] else null
    }

    /** Zero-allocation lookup with space fallback for unsupported characters. */
    fun glyphOrSpace(ch: Char): PixelGlyph = glyphOrNull(ch) ?: spaceGlyph

    /** Width in target pixels of `chars[start until start + length]` at integer [scale]. */
    fun measureWidth(chars: CharArray, start: Int, length: Int, scale: Int = 1): Int {
        if (length <= 0 || scale <= 0) return 0
        var px = 0
        var i = 0
        while (i < length) {
            if (i > 0) px += tracking
            px += glyphOrSpace(chars[start + i]).width
            i++
        }
        return px * scale
    }

    /** Width in target pixels of [text] at integer [scale]. */
    fun measureWidth(text: CharSequence, scale: Int = 1): Int {
        val n = text.length
        if (n == 0 || scale <= 0) return 0
        var px = 0
        var i = 0
        while (i < n) {
            if (i > 0) px += tracking
            px += glyphOrSpace(text[i]).width
            i++
        }
        return px * scale
    }

    /** True if any character in `chars[start until start + length]` draws below [capHeight]. */
    fun hasDescenders(chars: CharArray, start: Int, length: Int): Boolean {
        if (height <= capHeight) return false
        var i = 0
        while (i < length) {
            if (glyphOrSpace(chars[start + i]).hasPixelsFromRow(capHeight)) return true
            i++
        }
        return false
    }

    companion object {
        private fun parseGlyph(ch: Char, expectedHeight: Int, rows: Array<String>): PixelGlyph {
            require(rows.size == expectedHeight) {
                "Glyph '$ch' has ${rows.size} rows, expected $expectedHeight"
            }
            val w = rows[0].length
            require(w in 1..30) { "Glyph '$ch' invalid width $w" }
            val packed = IntArray(expectedHeight)
            for (y in 0 until expectedHeight) {
                val row = rows[y]
                require(row.length == w) {
                    "Glyph '$ch' row $y width ${row.length} != $w"
                }
                var bits = 0
                for (x in 0 until w) {
                    bits = (bits shl 1) or if (row[x] == '#' || row[x] == '1') 1 else 0
                }
                packed[y] = bits
            }
            return PixelGlyph(ch, w, expectedHeight, packed)
        }

        private fun padToHeight(rows: Array<String>, targetHeight: Int): Array<String> {
            if (rows.size == targetHeight) return rows
            val blank = ".".repeat(rows[0].length)
            return Array(targetHeight) { y -> if (y < rows.size) rows[y] else blank }
        }

        private fun buildFont(
            style: SignFontStyle,
            name: String,
            height: Int,
            capHeight: Int,
            tracking: Int,
            lineGap: Int,
            supportsLowercase: Boolean,
            defs: Map<Char, Array<String>>,
        ): PixelFont {
            val map = LinkedHashMap<Char, PixelGlyph>(defs.size)
            for ((ch, rows) in defs) {
                map[ch] = parseGlyph(ch, height, padToHeight(rows, height))
            }
            return PixelFont(style, name, height, capHeight, tracking, lineGap, supportsLowercase, map)
        }

        /**
         * 1900–1940s serif-block face (7 px tall, uppercase + digits + punctuation).
         * Sturdy architectural proportions with crisp slab serifs matching Main Street signboards.
         */
        val SERIF_BLOCK: PixelFont = buildFont(
            style = SignFontStyle.SERIF_BLOCK,
            name = "serif-block",
            height = 7,
            capHeight = 7,
            tracking = 1,
            lineGap = 1,
            supportsLowercase = false,
            defs = linkedMapOf(
                ' ' to arrayOf("...", "...", "...", "...", "...", "...", "..."),
                'A' to arrayOf("..##..", ".#..#.", ".#..#.", ".####.", ".#..#.", ".#..#.", "##..##"),
                'B' to arrayOf("#####.", ".#...#", ".#...#", ".####.", ".#...#", ".#...#", "#####."),
                'C' to arrayOf(".#####", "##...#", "##....", "##....", "##....", "##...#", ".#####"),
                'D' to arrayOf("#####.", ".#...#", ".#...#", ".#...#", ".#...#", ".#...#", "#####."),
                'E' to arrayOf("#####", ".#..#", ".#...", ".###.", ".#...", ".#..#", "#####"),
                'F' to arrayOf("#####", ".#..#", ".#...", ".###.", ".#...", ".#...", "###.."),
                'G' to arrayOf(".#####", "##...#", "##....", "##.###", "##...#", "##...#", ".####."),
                'H' to arrayOf("##..##", ".#..#.", ".#..#.", ".####.", ".#..#.", ".#..#.", "##..##"),
                'I' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
                'J' to arrayOf("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."),
                'K' to arrayOf("##..##", ".#.#..", ".##...", ".##...", ".#.#..", ".#..#.", "##..##"),
                'L' to arrayOf("###..", ".#...", ".#...", ".#...", ".#...", ".#..#", "#####"),
                'M' to arrayOf("##...##", ".##.##.", ".#.#.#.", ".#.#.#.", ".#...#.", ".#...#.", "##...##"),
                'N' to arrayOf("##..##", ".##.#.", ".##.#.", ".#.##.", ".#.##.", ".#..#.", "##..#."),
                'O' to arrayOf(".####.", "##..##", "##..##", "##..##", "##..##", "##..##", ".####."),
                'P' to arrayOf("#####.", ".#...#", ".#...#", ".####.", ".#....", ".#....", "###..."),
                'Q' to arrayOf(".###..", "##.##.", "##.##.", "##.##.", "##.##.", ".###..", "...###"),
                'R' to arrayOf("#####.", ".#...#", ".#...#", ".####.", ".#.#..", ".#..#.", "###.##"),
                'S' to arrayOf(".####", "##..#", "##...", ".###.", "...##", "#..##", "####."),
                'T' to arrayOf("#####", "#.#.#", "..#..", "..#..", "..#..", "..#..", ".###."),
                'U' to arrayOf("##..##", ".#..#.", ".#..#.", ".#..#.", ".#..#.", ".#..#.", "..##.."),
                'V' to arrayOf("##..##", ".#..#.", ".#..#.", ".#..#.", ".#..#.", "..##..", "..##.."),
                'W' to arrayOf("##...##", ".#...#.", ".#...#.", ".#.#.#.", ".#.#.#.", ".##.##.", "..#.#.."),
                'X' to arrayOf("##..##", ".#..#.", "..##..", "..##..", "..##..", ".#..#.", "##..##"),
                'Y' to arrayOf("##..##", ".#..#.", ".#..#.", "..##..", "..#...", "..#...", ".###.."),
                'Z' to arrayOf("#####", "#..##", "...#.", "..#..", ".#...", "##..#", "#####"),
                '0' to arrayOf(".####.", "##..##", "##.###", "##.#.#", "###.##", "##..##", ".####."),
                '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####"),
                '2' to arrayOf(".###.", "##.##", "...##", "..##.", ".##..", "##..#", "#####"),
                '3' to arrayOf("####.", "#..##", "...##", ".###.", "...##", "#..##", "####."),
                '4' to arrayOf("...##", "..###", ".#.##", "##.##", "#####", "...##", "..###"),
                '5' to arrayOf("#####", "##...", "####.", "...##", "...##", "#..##", ".###."),
                '6' to arrayOf(".###.", "##..#", "##...", "####.", "##.##", "##.##", ".###."),
                '7' to arrayOf("#####", "#..##", "...#.", "..#..", "..#..", ".##..", ".##.."),
                '8' to arrayOf(".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###."),
                '9' to arrayOf(".###.", "##.##", "##.##", ".####", "...##", "#..##", ".###."),
                '&' to arrayOf(".###..", "##.##.", ".###..", ".###.#", "##..#.", "##.##.", ".##.##"),
                '.' to arrayOf("..", "..", "..", "..", "..", "##", "##"),
                ',' to arrayOf("..", "..", "..", "..", ".#", ".#", "#."),
                '\'' to arrayOf("##", "##", "#.", "..", "..", "..", ".."),
                '-' to arrayOf("....", "....", "....", "####", "....", "....", "...."),
                '!' to arrayOf("##", "##", "##", "##", "..", "##", "##"),
                ':' to arrayOf("..", "##", "##", "..", "##", "##", ".."),
                '/' to arrayOf("...#", "...#", "..#.", "..#.", ".#..", "#...", "#..."),
                '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
            ),
        )

        /**
         * 1950s–1960s rounded face (7 px tall, uppercase + digits + punctuation).
         * Matches `pixel_font.py` diner/motel weight with chamfered corners and open bowls for glow signs.
         */
        val ROUNDED: PixelFont = buildFont(
            style = SignFontStyle.ROUNDED,
            name = "rounded",
            height = 7,
            capHeight = 7,
            tracking = 1,
            lineGap = 1,
            supportsLowercase = false,
            defs = linkedMapOf(
                ' ' to arrayOf("...", "...", "...", "...", "...", "...", "..."),
                'A' to arrayOf(".###.", "##.##", "##.##", "#####", "##.##", "##.##", "##.##"),
                'B' to arrayOf("####.", "##.##", "##.##", "####.", "##.##", "##.##", "####."),
                'C' to arrayOf(".###.", "##.##", "##...", "##...", "##...", "##.##", ".###."),
                'D' to arrayOf("####.", "##.##", "##.##", "##.##", "##.##", "##.##", "####."),
                'E' to arrayOf(".####", "##...", "##...", "####.", "##...", "##...", ".####"),
                'F' to arrayOf(".####", "##...", "##...", "####.", "##...", "##...", "##..."),
                'G' to arrayOf(".###.", "##.##", "##...", "##.##", "##.##", "##.##", ".###."),
                'H' to arrayOf("##.##", "##.##", "##.##", "#####", "##.##", "##.##", "##.##"),
                'I' to arrayOf(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."),
                'J' to arrayOf("..###", "...##", "...##", "...##", "##.##", "##.##", ".###."),
                'K' to arrayOf("##.##", "##.##", "####.", "###..", "####.", "##.##", "##.##"),
                'L' to arrayOf("##...", "##...", "##...", "##...", "##...", "##...", ".####"),
                'M' to arrayOf("##...##", "###.###", "#######", "##.#.##", "##...##", "##...##", "##...##"),
                'N' to arrayOf("##...#", "###..#", "##.#.#", "##.#.#", "##..##", "##..##", "#...##"),
                'O' to arrayOf(".####.", "##..##", "##..##", "##..##", "##..##", "##..##", ".####."),
                'P' to arrayOf("####.", "##.##", "##.##", "####.", "##...", "##...", "##..."),
                'Q' to arrayOf(".###.", "##.##", "##.##", "##.##", "##.##", ".###.", "...##"),
                'R' to arrayOf("####.", "##.##", "##.##", "####.", "###..", "##.#.", "##.##"),
                'S' to arrayOf(".###.", "##.##", "##...", ".###.", "...##", "##.##", ".###."),
                'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
                'U' to arrayOf("##.##", "##.##", "##.##", "##.##", "##.##", "##.##", ".###."),
                'V' to arrayOf("##.##", "##.##", "##.##", "##.##", "##.##", ".###.", "..#.."),
                'W' to arrayOf("##...##", "##...##", "##...##", "##.#.##", "#######", "###.###", ".#...#."),
                'X' to arrayOf("##.##", "##.##", ".###.", "..#..", ".###.", "##.##", "##.##"),
                'Y' to arrayOf("##.##", "##.##", ".###.", "..#..", "..#..", "..#..", "..#.."),
                'Z' to arrayOf("#####", "...##", "..##.", "..#..", ".##..", "##...", "#####"),
                '0' to arrayOf(".###.", "##.##", "#####", "##.##", "##.##", "##.##", ".###."),
                '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
                '2' to arrayOf(".###.", "##.##", "...##", "..##.", ".##..", "##...", "#####"),
                '3' to arrayOf(".###.", "##.##", "...##", "..##.", "...##", "##.##", ".###."),
                '4' to arrayOf("##.##", "##.##", "##.##", "#####", "...##", "...##", "...##"),
                '5' to arrayOf("#####", "##...", "####.", "...##", "...##", "##.##", ".###."),
                '6' to arrayOf(".###.", "##...", "####.", "##.##", "##.##", "##.##", ".###."),
                '7' to arrayOf("#####", "...##", "..##.", "..#..", ".##..", ".##..", ".##.."),
                '8' to arrayOf(".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###."),
                '9' to arrayOf(".###.", "##.##", "##.##", ".####", "...##", "##.##", ".###."),
                '&' to arrayOf(".##..", "##.#.", "##...", ".##.#", "##.#.", "##.#.", ".##.#"),
                '.' to arrayOf("..", "..", "..", "..", "..", "##", "##"),
                ',' to arrayOf("..", "..", "..", "..", ".#", ".#", "#."),
                '\'' to arrayOf("##", "##", "#.", "..", "..", "..", ".."),
                '-' to arrayOf("....", "....", "....", "####", "....", "....", "...."),
                '!' to arrayOf("##", "##", "##", "##", "..", "##", "##"),
                ':' to arrayOf("..", "##", "##", "..", "##", "##", ".."),
                '/' to arrayOf("...#", "...#", "..#.", "..#.", ".#..", "#...", "#..."),
                '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
            ),
        )

        /**
         * 1970s–today plain sans face (9 px tall = 7 px cap height + 2 px descender).
         * Supports both uppercase and lowercase, digits, and punctuation.
         */
        val SANS: PixelFont = buildFont(
            style = SignFontStyle.SANS,
            name = "sans",
            height = 9,
            capHeight = 7,
            tracking = 1,
            lineGap = 1,
            supportsLowercase = true,
            defs = linkedMapOf(
                ' ' to arrayOf("...", "...", "...", "...", "...", "...", "..."),
                // Uppercase (rows 0..6, padded with 2 empty descender rows to height 9)
                'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
                'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
                'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
                'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
                'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
                'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
                'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
                'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
                'I' to arrayOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
                'J' to arrayOf("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."),
                'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
                'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
                'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
                'N' to arrayOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
                'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
                'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
                'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
                'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
                'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
                'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
                'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
                'V' to arrayOf("#...#", "#...#", "#...#", ".#.#.", ".#.#.", "..#..", "..#.."),
                'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
                'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
                'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
                'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
                // Lowercase (x-height rows 2..6, ascenders 0..6, descenders 7..8)
                'a' to arrayOf("....", "....", ".##.", "...#", ".###", "#..#", ".###", "....", "...."),
                'b' to arrayOf("#...", "#...", "###.", "#..#", "#..#", "#..#", "###.", "....", "...."),
                'c' to arrayOf("....", "....", ".###", "#...", "#...", "#...", ".###", "....", "...."),
                'd' to arrayOf("...#", "...#", ".###", "#..#", "#..#", "#..#", ".###", "....", "...."),
                'e' to arrayOf("....", "....", ".##.", "#..#", "####", "#...", ".###", "....", "...."),
                'f' to arrayOf(".##", "#..", "###", "#..", "#..", "#..", "#..", "...", "..."),
                'g' to arrayOf("....", "....", ".###", "#..#", "#..#", "#..#", ".###", "...#", "###."),
                'h' to arrayOf("#...", "#...", "###.", "#..#", "#..#", "#..#", "#..#", "....", "...."),
                'i' to arrayOf("#", ".", "#", "#", "#", "#", "#", ".", "."),
                'j' to arrayOf("..#", "...", "..#", "..#", "..#", "..#", "..#", "..#", "##."),
                'k' to arrayOf("#...", "#...", "#..#", "#.#.", "##..", "#.#.", "#..#", "....", "...."),
                'l' to arrayOf("#.", "#.", "#.", "#.", "#.", "#.", ".#", "..", ".."),
                'm' to arrayOf(".....", ".....", "##.#.", "#.#.#", "#.#.#", "#.#.#", "#.#.#", ".....", "....."),
                'n' to arrayOf("....", "....", "###.", "#..#", "#..#", "#..#", "#..#", "....", "...."),
                'o' to arrayOf("....", "....", ".##.", "#..#", "#..#", "#..#", ".##.", "....", "...."),
                'p' to arrayOf("....", "....", "###.", "#..#", "#..#", "#..#", "###.", "#...", "#..."),
                'q' to arrayOf("....", "....", ".###", "#..#", "#..#", "#..#", ".###", "...#", "...#"),
                'r' to arrayOf("...", "...", "#.#", "##.", "#..", "#..", "#..", "...", "..."),
                's' to arrayOf("....", "....", ".###", "#...", ".##.", "...#", "###.", "....", "...."),
                't' to arrayOf(".#.", ".#.", "###", ".#.", ".#.", ".#.", "..#", "...", "..."),
                'u' to arrayOf("....", "....", "#..#", "#..#", "#..#", "#..#", ".###", "....", "...."),
                'v' to arrayOf(".....", ".....", "#...#", "#...#", ".#.#.", ".#.#.", "..#..", ".....", "....."),
                'w' to arrayOf(".....", ".....", "#...#", "#...#", "#.#.#", "#.#.#", ".#.#.", ".....", "....."),
                'x' to arrayOf("....", "....", "#..#", ".##.", ".##.", ".##.", "#..#", "....", "...."),
                'y' to arrayOf("....", "....", "#..#", "#..#", "#..#", "#..#", ".###", "...#", "###."),
                'z' to arrayOf("....", "....", "####", "..#.", ".#..", "#...", "####", "....", "...."),
                // Digits
                '0' to arrayOf(".###.", "#..##", "#.#.#", "#.#.#", "##..#", "#...#", ".###."),
                '1' to arrayOf(".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"),
                '2' to arrayOf(".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"),
                '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
                '4' to arrayOf("#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"),
                '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
                '6' to arrayOf(".###.", "#....", "####.", "#...#", "#...#", "#...#", ".###."),
                '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
                '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
                '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
                // Punctuation
                '&' to arrayOf(".##..", "#..#.", ".##..", "#.#.#", "#..#.", "#..#.", ".##.#"),
                '.' to arrayOf(".", ".", ".", ".", ".", "#", "#", ".", "."),
                ',' to arrayOf("..", "..", "..", "..", "..", ".#", ".#", "#.", ".."),
                '\'' to arrayOf("#", "#", ".", ".", ".", ".", ".", ".", "."),
                '-' to arrayOf("....", "....", "....", "####", "....", "....", "....", "....", "...."),
                '!' to arrayOf("#", "#", "#", "#", "#", ".", "#", ".", "."),
                ':' to arrayOf(".", ".", "#", ".", ".", "#", ".", ".", "."),
                '/' to arrayOf("...#", "...#", "..#.", "..#.", ".#..", "#...", "#...", "....", "...."),
                '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....", ".....", "....."),
            ),
        )

        val ALL: List<PixelFont> = listOf(SERIF_BLOCK, ROUNDED, SANS)

        /** Default era face: 1900–1940s serif-block, 1950s–60s rounded, 1970s–today plain sans. */
        fun forEra(year: Int): PixelFont = when {
            year < 1950 -> SERIF_BLOCK
            year < 1970 -> ROUNDED
            else -> SANS
        }

        fun resolve(face: SignFace, era: Int): PixelFont = when (face) {
            SignFace.AUTO -> forEra(era)
            SignFace.SERIF_BLOCK -> SERIF_BLOCK
            SignFace.ROUNDED -> ROUNDED
            SignFace.SANS -> SANS
        }
    }
}
