package com.lifemax.rpg.feature.habits.domain.signs

/**
 * Reusable zero-allocation scratch state for [SignPainter.paint].
 *
 * Callers on a hot thread can either hold one [PaintScratch] instance or rely on [SignPainter]'s
 * default `ThreadLocal` scratch (zero bytes allocated after the first call on that thread).
 */
class PaintScratch(capacity: Int = 256) {
    internal val buf: CharArray = CharArray(capacity)
    internal val tmp: CharArray = CharArray(capacity)
    internal var len: Int = 0

    internal var lineCount: Int = 0
    internal var line1Start: Int = 0
    internal var line1Len: Int = 0
    internal var line2Start: Int = 0
    internal var line2Len: Int = 0
    internal var scale: Int = 1

    /** Last chosen whole-pixel scale (1, 2, or 3) — useful for diagnostics and tests. */
    val lastScale: Int get() = scale

    /** Number of rendered text lines from the most recent [SignPainter.paint] call. */
    val lastLineCount: Int get() = lineCount

    /** Copies the most recently laid-out line (0 or 1) into a new String (for tests/debugging only). */
    fun debugLine(index: Int): String = when {
        index == 0 && lineCount >= 1 -> String(buf, line1Start, line1Len)
        index == 1 && lineCount >= 2 -> String(buf, line2Start, line2Len)
        else -> ""
    }
}

/**
 * Zero-allocation pixel-art sign painter.
 *
 * Paints any business name into an ARGB (`0xAARRGGBB`) [IntArray] pixel buffer at whole-pixel
 * integer scales (1×, 2×, 3×), never blurred:
 * - Covers the full `spec.rect` with `spec.paper` first so baked text is completely erased.
 * - Chooses the largest integer scale that fits (1 line or balanced 2-line wrap).
 * - Abbreviates gracefully as a last resort (`and` → `&`, shorter business nouns, then initials).
 * - Clips strictly to `spec.rect` and buffer bounds so no glyph pixel can ever escape.
 * - Paints the matching emissive glow mask into [glowOut] when `spec.glow` is true.
 */
object SignPainter {
    private val GLOW_OFF: Int = 0xFF000000L.toInt()
    private const val MAX_SCALE = 3

    private val threadScratch: ThreadLocal<PaintScratch> = ThreadLocal.withInitial { PaintScratch() }

    // Ordered suffix contractions (longest / safest first), stored as CharArrays for zero-alloc matching.
    private val SUFFIX_FROM: Array<CharArray> = arrayOf(
        "Funeral Home".toCharArray(),
        "Department Store".toCharArray(),
        "Service Station".toCharArray(),
        "Hardware Store".toCharArray(),
        "General Store".toCharArray(),
        "Barber Shop".toCharArray(),
        "Coffee Shop".toCharArray(),
        "Beauty Salon".toCharArray(),
        "Motor Lodge".toCharArray(),
        "Motor Court".toCharArray(),
        "Gas Station".toCharArray(),
        "Video Store".toCharArray(),
        "Five & Dime".toCharArray(),
        "Laundromat".toCharArray(),
        "Drugstore".toCharArray(),
        "Brothers".toCharArray(),
        "Company".toCharArray(),
        "Pharmacy".toCharArray(),
        "Apothecary".toCharArray(),
    )
    private val SUFFIX_TO: Array<CharArray> = arrayOf(
        "Funeral".toCharArray(),
        "Dept.".toCharArray(),
        "Service".toCharArray(),
        "Hardware".toCharArray(),
        "Store".toCharArray(),
        "Barber".toCharArray(),
        "Coffee".toCharArray(),
        "Salon".toCharArray(),
        "Motel".toCharArray(),
        "Motel".toCharArray(),
        "Gas".toCharArray(),
        "Video".toCharArray(),
        "5 & 10".toCharArray(),
        "Laundry".toCharArray(),
        "Drugs".toCharArray(),
        "Bros.".toCharArray(),
        "Co.".toCharArray(),
        "Drugs".toCharArray(),
        "Drugs".toCharArray(),
    )

    private val WORD_AND: CharArray = "and".toCharArray()
    private val WORD_AMP: CharArray = "&".toCharArray()

    /**
     * Paints [text] onto [out] (width [stride]) inside `spec.rect` using the era's pixel font.
     *
     * When `spec.glow` is true and [glowOut] is provided, clears the rect in [glowOut] to unlit
     * black and paints the new letter pixels with `spec.glowColor`.
     *
     * Allocates **0 bytes** after warm-up.
     */
    fun paint(
        spec: SignSpec,
        text: String,
        era: Int,
        out: IntArray,
        stride: Int,
        glowOut: IntArray? = null,
        glowStride: Int = stride,
        scratch: PaintScratch = threadScratch.get(),
    ): Boolean {
        val rect = spec.rect
        if (rect.width <= 0 || rect.height <= 0 || stride <= 0) return false
        val outHeight = out.size / stride
        if (outHeight <= 0) return false

        val clipX0 = if (rect.x < 0) 0 else rect.x
        val clipY0 = if (rect.y < 0) 0 else rect.y
        val clipX1 = minOf(rect.x + rect.width, stride)
        val clipY1 = minOf(rect.y + rect.height, outHeight)
        if (clipX0 >= clipX1 || clipY0 >= clipY1) return false

        // 1. Always paint paper across the full rect to completely cover any old baked name.
        fillRect(out, stride, clipX0, clipY0, clipX1, clipY1, spec.paper)

        val paintGlow = spec.glow && glowOut != null && glowStride > 0
        var glowClipX1 = 0
        var glowClipY1 = 0
        if (paintGlow) {
            val gHeight = glowOut!!.size / glowStride
            glowClipX1 = minOf(rect.x + rect.width, glowStride)
            glowClipY1 = minOf(rect.y + rect.height, gHeight)
            if (clipX0 < glowClipX1 && clipY0 < glowClipY1) {
                fillRect(glowOut, glowStride, clipX0, clipY0, glowClipX1, glowClipY1, GLOW_OFF)
            }
        }

        val font = PixelFont.resolve(spec.face, era)

        // 2. Load & normalize text into scratch.buf with zero allocations.
        loadNormalized(text, font, scratch)
        if (scratch.len == 0) {
            scratch.lineCount = 0
            scratch.scale = 1
            return true
        }

        val hasOutline = spec.outline != null
        val maxLines = spec.clampedLines

        // 3. Respect explicit maxChars via abbreviations before trimming.
        if (spec.maxChars > 0 && scratch.len > spec.maxChars) {
            abbreviateToMaxChars(scratch, font, spec.maxChars)
        }

        // 4. Find largest whole-pixel scale (3, 2, 1) that fits within rect padding.
        var fitted = tryFitAnyScale(scratch, font, rect, maxLines, hasOutline)

        // 5. Abbreviation ladder if text still overflows at 1× scale.
        if (!fitted) {
            if (replaceWordAndWithAmp(scratch)) {
                fitted = tryFitAtScale(scratch, font, rect, maxLines, 1, hasOutline)
            }
        }
        if (!fitted) {
            var idx = 0
            while (idx < SUFFIX_FROM.size && !fitted) {
                if (replacePhraseInPlace(scratch, SUFFIX_FROM[idx], SUFFIX_TO[idx], font)) {
                    fitted = tryFitAtScale(scratch, font, rect, maxLines, 1, hasOutline)
                }
                idx++
            }
        }
        if (!fitted) {
            var guard = 0
            while (guard < 8 && !fitted) {
                if (!abbreviateOneWordToInitial(scratch)) break
                fitted = tryFitAtScale(scratch, font, rect, maxLines, 1, hasOutline)
                guard++
            }
        }
        if (!fitted) {
            // Last-resort clamp at 1× so NO glyph pixel can ever escape spec.rect.
            forceClampToFit(scratch, font, rect, maxLines, hasOutline)
        }

        // 6. Compute vertical & horizontal placement and rasterize crisp integer blocks.
        val s = scratch.scale
        val padX = basePadX(rect.width) + if (hasOutline) 1 else 0
        val innerX0 = rect.x + padX
        val innerW = maxOf(1, rect.width - 2 * padX)

        val lineGapPx = maxOf(1, font.lineGap * s) + if (hasOutline && s == 1) 1 else 0
        val line1VisualH = visualLineHeight(font, scratch.buf, scratch.line1Start, scratch.line1Len) * s
        val totalVisualH = if (scratch.lineCount == 2) {
            val line2VisualH = visualLineHeight(font, scratch.buf, scratch.line2Start, scratch.line2Len) * s
            line1VisualH + lineGapPx + line2VisualH
        } else {
            line1VisualH
        }

        val startY = rect.y + (rect.height - totalVisualH) / 2
        val line1Y = startY
        val line2Y = startY + line1VisualH + lineGapPx

        val line1W = font.measureWidth(scratch.buf, scratch.line1Start, scratch.line1Len, s)
        val line1X = alignedX(innerX0, innerW, line1W, spec.align)

        var line2X = innerX0
        if (scratch.lineCount == 2) {
            val line2W = font.measureWidth(scratch.buf, scratch.line2Start, scratch.line2Len, s)
            line2X = alignedX(innerX0, innerW, line2W, spec.align)
        }

        // Outline pass first (behind letters), strictly clipped to spec.rect.
        val outlineColor = spec.outline
        if (outlineColor != null) {
            drawLineOutline(
                font, scratch.buf, scratch.line1Start, scratch.line1Len,
                line1X, line1Y, s, outlineColor, out, stride, clipX0, clipY0, clipX1, clipY1,
            )
            if (scratch.lineCount == 2) {
                drawLineOutline(
                    font, scratch.buf, scratch.line2Start, scratch.line2Len,
                    line2X, line2Y, s, outlineColor, out, stride, clipX0, clipY0, clipX1, clipY1,
                )
            }
        }

        // Ink pass.
        drawLine(
            font, scratch.buf, scratch.line1Start, scratch.line1Len,
            line1X, line1Y, s, spec.ink, out, stride, clipX0, clipY0, clipX1, clipY1,
        )
        if (scratch.lineCount == 2) {
            drawLine(
                font, scratch.buf, scratch.line2Start, scratch.line2Len,
                line2X, line2Y, s, spec.ink, out, stride, clipX0, clipY0, clipX1, clipY1,
            )
        }

        // Glow mask pass (letters only, crisp against black background).
        if (paintGlow && clipX0 < glowClipX1 && clipY0 < glowClipY1) {
            drawLine(
                font, scratch.buf, scratch.line1Start, scratch.line1Len,
                line1X, line1Y, s, spec.glowColor, glowOut!!, glowStride, clipX0, clipY0, glowClipX1, glowClipY1,
            )
            if (scratch.lineCount == 2) {
                drawLine(
                    font, scratch.buf, scratch.line2Start, scratch.line2Len,
                    line2X, line2Y, s, spec.glowColor, glowOut, glowStride, clipX0, clipY0, glowClipX1, glowClipY1,
                )
            }
        }

        return true
    }

    /**
     * Paints [spec] only when [config] allows owner repainting (`naming == NamingMode.OWNER`).
     * A `FIXED` model (landmarks, monuments, courthouse, ballpark) is never repainted.
     */
    fun paintIfAllowed(
        config: ModelSignConfig,
        spec: SignSpec,
        text: String,
        era: Int,
        out: IntArray,
        stride: Int,
        glowOut: IntArray? = null,
        glowStride: Int = stride,
        scratch: PaintScratch = threadScratch.get(),
    ): Boolean {
        if (!config.repaintable) return false
        return paint(spec, text, era, out, stride, glowOut, glowStride, scratch)
    }

    private fun basePadX(rectW: Int): Int = if (rectW >= 24) 2 else 1
    private fun basePadY(rectH: Int): Int = if (rectH >= 14) 2 else 1

    private fun visualLineHeight(font: PixelFont, buf: CharArray, start: Int, len: Int): Int =
        if (font.height > font.capHeight && !font.hasDescenders(buf, start, len)) font.capHeight else font.height

    private fun alignedX(innerX0: Int, innerW: Int, lineW: Int, align: SignAlign): Int = when (align) {
        SignAlign.LEFT -> innerX0
        SignAlign.RIGHT -> innerX0 + (innerW - lineW)
        SignAlign.CENTER -> innerX0 + (innerW - lineW) / 2
    }

    private fun loadNormalized(text: String, font: PixelFont, scratch: PaintScratch) {
        val buf = scratch.buf
        val cap = buf.size
        val n = text.length
        var outLen = 0
        var prevSpace = true
        var i = 0
        while (i < n && outLen < cap) {
            var ch = text[i]
            if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' '
            if (ch == ' ') {
                if (!prevSpace) {
                    buf[outLen++] = ' '
                    prevSpace = true
                }
            } else {
                if (!font.supportsLowercase && ch in 'a'..'z') {
                    ch = (ch.code - 32).toChar()
                }
                buf[outLen++] = ch
                prevSpace = false
            }
            i++
        }
        while (outLen > 0 && buf[outLen - 1] == ' ') outLen--
        scratch.len = outLen
    }

    private fun tryFitAnyScale(
        scratch: PaintScratch,
        font: PixelFont,
        rect: SignRect,
        maxLines: Int,
        hasOutline: Boolean,
    ): Boolean {
        var s = MAX_SCALE
        while (s >= 1) {
            if (tryFitAtScale(scratch, font, rect, maxLines, s, hasOutline)) return true
            s--
        }
        return false
    }

    private fun tryFitAtScale(
        scratch: PaintScratch,
        font: PixelFont,
        rect: SignRect,
        maxLines: Int,
        scale: Int,
        hasOutline: Boolean,
    ): Boolean {
        val padX = basePadX(rect.width) + if (hasOutline) 1 else 0
        val padY = basePadY(rect.height) + if (hasOutline) 1 else 0
        val availW = rect.width - 2 * padX
        val availH = rect.height - 2 * padY
        if (availW <= 0 || availH <= 0) return false

        val singleH = visualLineHeight(font, scratch.buf, 0, scratch.len) * scale
        if (singleH <= availH) {
            val w = font.measureWidth(scratch.buf, 0, scratch.len, scale)
            if (w <= availW) {
                scratch.scale = scale
                scratch.lineCount = 1
                scratch.line1Start = 0
                scratch.line1Len = scratch.len
                scratch.line2Start = 0
                scratch.line2Len = 0
                return true
            }
        }

        if (maxLines >= 2) {
            val bestSplit = findBestTwoLineSplit(scratch, font, availW, availH, scale, hasOutline)
            if (bestSplit >= 0) {
                scratch.scale = scale
                scratch.lineCount = 2
                scratch.line1Start = 0
                scratch.line1Len = bestSplit
                scratch.line2Start = bestSplit + 1
                scratch.line2Len = scratch.len - bestSplit - 1
                return true
            }
        }
        return false
    }

    private fun findBestTwoLineSplit(
        scratch: PaintScratch,
        font: PixelFont,
        availW: Int,
        availH: Int,
        scale: Int,
        hasOutline: Boolean,
    ): Int {
        val buf = scratch.buf
        val len = scratch.len
        var bestIdx = -1
        var bestMaxW = Int.MAX_VALUE
        var bestDiff = Int.MAX_VALUE

        var i = 1
        while (i < len - 1) {
            if (buf[i] == ' ') {
                val l1Len = i
                val l2Start = i + 1
                val l2Len = len - l2Start
                if (l1Len > 0 && l2Len > 0) {
                    val l1VisH = visualLineHeight(font, buf, 0, l1Len) * scale
                    val l2VisH = visualLineHeight(font, buf, l2Start, l2Len) * scale
                    val gapPx = maxOf(1, font.lineGap * scale) + if (hasOutline && scale == 1) 1 else 0
                    val totalH = l1VisH + gapPx + l2VisH
                    if (totalH <= availH) {
                        val w1 = font.measureWidth(buf, 0, l1Len, scale)
                        if (w1 <= availW) {
                            val w2 = font.measureWidth(buf, l2Start, l2Len, scale)
                            if (w2 <= availW) {
                                val maxW = if (w1 > w2) w1 else w2
                                val diff = if (w1 > w2) w1 - w2 else w2 - w1
                                if (maxW < bestMaxW || (maxW == bestMaxW && diff < bestDiff)) {
                                    bestMaxW = maxW
                                    bestDiff = diff
                                    bestIdx = i
                                }
                            }
                        }
                    }
                }
            }
            i++
        }
        return bestIdx
    }

    private fun abbreviateToMaxChars(scratch: PaintScratch, font: PixelFont, maxChars: Int) {
        if (scratch.len <= maxChars) return
        replaceWordAndWithAmp(scratch)
        if (scratch.len <= maxChars) return
        var idx = 0
        while (idx < SUFFIX_FROM.size && scratch.len > maxChars) {
            replacePhraseInPlace(scratch, SUFFIX_FROM[idx], SUFFIX_TO[idx], font)
            idx++
        }
        var guard = 0
        while (guard < 8 && scratch.len > maxChars) {
            if (!abbreviateOneWordToInitial(scratch)) break
            guard++
        }
        if (scratch.len > maxChars) {
            var n = maxChars
            while (n > 0 && scratch.buf[n - 1] == ' ') n--
            scratch.len = n
        }
    }

    /** Replaces standalone `"and"` (any case) with `"&"` in-place. Returns true if changed. */
    private fun replaceWordAndWithAmp(scratch: PaintScratch): Boolean {
        return replacePhraseInPlace(scratch, WORD_AND, WORD_AMP, null)
    }

    /** Case-insensitive whole-word phrase replacement in-place using `scratch.tmp`. */
    private fun replacePhraseInPlace(
        scratch: PaintScratch,
        from: CharArray,
        to: CharArray,
        font: PixelFont?,
    ): Boolean {
        val buf = scratch.buf
        val tmp = scratch.tmp
        val len = scratch.len
        val fLen = from.size
        if (fLen == 0 || len < fLen) return false

        var changed = false
        var outLen = 0
        var i = 0
        while (i < len) {
            val atWordStart = i == 0 || buf[i - 1] == ' '
            val atWordEnd = i + fLen == len || (i + fLen < len && buf[i + fLen] == ' ')
            if (atWordStart && atWordEnd && i + fLen <= len && regionEqualsIgnoreCase(buf, i, from)) {
                var k = 0
                while (k < to.size && outLen < tmp.size) {
                    var ch = to[k]
                    if (font != null && !font.supportsLowercase && ch in 'a'..'z') {
                        ch = (ch.code - 32).toChar()
                    }
                    tmp[outLen++] = ch
                    k++
                }
                i += fLen
                changed = true
            } else {
                if (outLen < tmp.size) tmp[outLen++] = buf[i]
                i++
            }
        }
        if (changed) {
            var k = 0
            while (k < outLen) {
                buf[k] = tmp[k]
                k++
            }
            scratch.len = outLen
        }
        return changed
    }

    private fun regionEqualsIgnoreCase(buf: CharArray, offset: Int, target: CharArray): Boolean {
        var k = 0
        while (k < target.size) {
            if (toLowerAscii(buf[offset + k]) != toLowerAscii(target[k])) return false
            k++
        }
        return true
    }

    private fun toLowerAscii(ch: Char): Char =
        if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch

    /**
     * Contracts one full word (`>= 3` letters) to its initial (`"Hollis"` → `"H."`), prioritizing
     * earlier words first while preserving `&` and short tokens. Returns true if a word changed.
     */
    private fun abbreviateOneWordToInitial(scratch: PaintScratch): Boolean {
        val buf = scratch.buf
        val tmp = scratch.tmp
        val len = scratch.len
        if (len <= 3) return false

        // Count words first so we prefer contracting non-final words before the last word.
        var wordCount = 0
        var pos = 0
        while (pos < len) {
            while (pos < len && buf[pos] == ' ') pos++
            if (pos >= len) break
            wordCount++
            while (pos < len && buf[pos] != ' ') pos++
        }

        var targetStart = -1
        var targetEnd = -1
        var wordIdx = 0
        pos = 0
        while (pos < len) {
            while (pos < len && buf[pos] == ' ') pos++
            if (pos >= len) break
            val wStart = pos
            while (pos < len && buf[pos] != ' ') pos++
            val wEnd = pos
            val wLen = wEnd - wStart
            val firstCh = buf[wStart]
            val isLetter = (firstCh in 'A'..'Z') || (firstCh in 'a'..'z')
            if (isLetter && wLen >= 3) {
                targetStart = wStart
                targetEnd = wEnd
                if (wordIdx < wordCount - 1) break
            }
            wordIdx++
        }

        if (targetStart < 0) return false

        var outLen = 0
        var i = 0
        while (i < targetStart && outLen < tmp.size) tmp[outLen++] = buf[i++]
        if (outLen < tmp.size) tmp[outLen++] = buf[targetStart]
        if (outLen < tmp.size) tmp[outLen++] = '.'
        i = targetEnd
        while (i < len && outLen < tmp.size) tmp[outLen++] = buf[i++]

        var k = 0
        while (k < outLen) {
            buf[k] = tmp[k]
            k++
        }
        scratch.len = outLen
        return true
    }

    /** Guarantees every remaining character fits inside `spec.rect` at 1× scale. */
    private fun forceClampToFit(
        scratch: PaintScratch,
        font: PixelFont,
        rect: SignRect,
        maxLines: Int,
        hasOutline: Boolean,
    ) {
        val scale = 1
        val padX = basePadX(rect.width) + if (hasOutline) 1 else 0
        val padY = basePadY(rect.height) + if (hasOutline) 1 else 0
        val availW = maxOf(1, rect.width - 2 * padX)
        val availH = maxOf(1, rect.height - 2 * padY)
        val canTwoLine = maxLines >= 2 && (font.height + font.lineGap + font.capHeight) <= availH

        scratch.scale = 1
        val buf = scratch.buf
        val len = scratch.len

        // First line: longest prefix fitting availW
        var l1Len = 0
        var px = 0
        while (l1Len < len) {
            val gw = font.glyphOrSpace(buf[l1Len]).width
            val nextPx = if (l1Len == 0) gw else px + font.tracking + gw
            if (nextPx > availW) break
            px = nextPx
            l1Len++
        }

        if (!canTwoLine || l1Len >= len) {
            while (l1Len > 0 && buf[l1Len - 1] == ' ') l1Len--
            scratch.lineCount = if (l1Len > 0) 1 else 0
            scratch.line1Start = 0
            scratch.line1Len = l1Len
            scratch.line2Start = 0
            scratch.line2Len = 0
            return
        }

        // Prefer breaking line 1 at a space if one exists in the second half.
        var breakAt = l1Len
        var scan = l1Len
        while (scan > l1Len / 2) {
            if (buf[scan - 1] == ' ') {
                breakAt = scan - 1
                break
            }
            scan--
        }

        var l2Start = breakAt
        while (l2Start < len && buf[l2Start] == ' ') l2Start++
        var l2Len = 0
        var px2 = 0
        while (l2Start + l2Len < len) {
            val gw = font.glyphOrSpace(buf[l2Start + l2Len]).width
            val nextPx = if (l2Len == 0) gw else px2 + font.tracking + gw
            if (nextPx > availW) break
            px2 = nextPx
            l2Len++
        }
        while (l2Len > 0 && buf[l2Start + l2Len - 1] == ' ') l2Len--

        scratch.line1Start = 0
        scratch.line1Len = breakAt
        if (l2Len > 0) {
            scratch.lineCount = 2
            scratch.line2Start = l2Start
            scratch.line2Len = l2Len
        } else {
            scratch.lineCount = if (breakAt > 0) 1 else 0
            scratch.line2Start = 0
            scratch.line2Len = 0
        }
    }

    private fun fillRect(
        out: IntArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ) {
        var y = y0
        while (y < y1) {
            val rowOff = y * stride
            var x = x0
            while (x < x1) {
                out[rowOff + x] = color
                x++
            }
            y++
        }
    }

    private fun drawBlock(
        out: IntArray,
        stride: Int,
        px: Int,
        py: Int,
        scale: Int,
        color: Int,
        clipX0: Int,
        clipY0: Int,
        clipX1: Int,
        clipY1: Int,
    ) {
        val x0 = if (px < clipX0) clipX0 else px
        val y0 = if (py < clipY0) clipY0 else py
        val x1 = minOf(px + scale, clipX1)
        val y1 = minOf(py + scale, clipY1)
        if (x0 >= x1 || y0 >= y1) return
        var y = y0
        while (y < y1) {
            val rowOff = y * stride
            var x = x0
            while (x < x1) {
                out[rowOff + x] = color
                x++
            }
            y++
        }
    }

    private fun drawLineOutline(
        font: PixelFont,
        buf: CharArray,
        start: Int,
        length: Int,
        originX: Int,
        originY: Int,
        scale: Int,
        color: Int,
        out: IntArray,
        stride: Int,
        clipX0: Int,
        clipY0: Int,
        clipX1: Int,
        clipY1: Int,
    ) {
        var cursorX = originX
        var i = 0
        while (i < length) {
            val g = font.glyphOrSpace(buf[start + i])
            val gw = g.width
            val gh = g.height
            val rows = g.rows
            var gy = 0
            while (gy < gh) {
                val bits = rows[gy]
                if (bits != 0) {
                    val py = originY + gy * scale
                    var gx = 0
                    while (gx < gw) {
                        if (((bits ushr (gw - 1 - gx)) and 1) != 0) {
                            val px = cursorX + gx * scale
                            if (scale == 1) {
                                // Crisp 1-texel vintage drop shadow at 1x so adjacent letters stay separated
                                drawBlock(out, stride, px + 1, py, 1, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px, py + 1, 1, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px + 1, py + 1, 1, color, clipX0, clipY0, clipX1, clipY1)
                            } else {
                                // Crisp 1-texel full outline at 2x/3x scale
                                drawBlock(out, stride, px - 1, py, scale, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px + 1, py, scale, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px, py - 1, scale, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px, py + 1, scale, color, clipX0, clipY0, clipX1, clipY1)
                                drawBlock(out, stride, px + 1, py + 1, scale, color, clipX0, clipY0, clipX1, clipY1)
                            }
                        }
                        gx++
                    }
                }
                gy++
            }
            cursorX += (gw + font.tracking) * scale
            i++
        }
    }

    private fun drawLine(
        font: PixelFont,
        buf: CharArray,
        start: Int,
        length: Int,
        originX: Int,
        originY: Int,
        scale: Int,
        color: Int,
        out: IntArray,
        stride: Int,
        clipX0: Int,
        clipY0: Int,
        clipX1: Int,
        clipY1: Int,
    ) {
        var cursorX = originX
        var i = 0
        while (i < length) {
            val g = font.glyphOrSpace(buf[start + i])
            val gw = g.width
            val gh = g.height
            val rows = g.rows
            var gy = 0
            while (gy < gh) {
                val bits = rows[gy]
                if (bits != 0) {
                    val py = originY + gy * scale
                    var gx = 0
                    while (gx < gw) {
                        if (((bits ushr (gw - 1 - gx)) and 1) != 0) {
                            drawBlock(out, stride, cursorX + gx * scale, py, scale, color, clipX0, clipY0, clipX1, clipY1)
                        }
                        gx++
                    }
                }
                gy++
            }
            cursorX += (gw + font.tracking) * scale
            i++
        }
    }
}
