package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The stores' pictures that are not walls: their names (made up, never a real chain's: [StoreNames]), their signs (a
 * hand-painted board, channel letters, a backlit box, a pylon's cabinet) and what shows through their glass (a general
 * store's window display of jars, cans, sacks and lanterns; a market's lit aisles, checkouts and window specials; the
 * old goods of an antiques shop; a chapel's paper "stained glass"), all painted texel by texel at the buildings' density
 * ([TEXEL]) in the pack's palette, like the cinema's ([CinemaKit], [CinemaPosters]).
 */

/** Made-up store names by kind, and the real names that must never appear. */
object StoreNames {
    private val SURNAMES = listOf("Hollis", "Pruett", "Abernathy", "Tolliver", "Kessling", "Marrow", "Dunmore", "Whitlock", "Beaumont", "Carver",
        "Haskell", "Ledbetter", "Oakes", "Pemberton", "Rowley", "Satterly", "Thorne", "Vance", "Wexler", "Yardley", "Corliss", "Fenwick")
    private val GENERAL = listOf("%s General Store", "%s Mercantile", "%s & Sons", "%s Bros. General", "%s Dry Goods", "%s Trading Co.")
    private val MARKET = listOf("Meadowlark Market", "Bluebell Market", "Larkspur Foods", "Sunfair Market", "Kettle Creek Market", "Tallgrass Foods",
        "Silverbell Market", "Orchard Lane Market", "Copperleaf Foods", "Hearthside Market", "Wrenfield Market", "Daybreak Market")
    private val SUPER = listOf("Fieldstone Foods", "Brightbasket", "Sunvale Foods", "Greenmeadow Market", "Harvestwell", "Pinecrest Foods",
        "Daisyfield Market", "Goldengrain Foods", "Larkhaven Market", "Marigold Market", "Bramblewood Market", "Kestrel Foods")
    private val BIG = listOf("Brightwell", "Openfield", "Sunvale Max", "Prairieway", "Wideland", "Allday Depot", "Granary Max", "Northstar Supercenter",
        "Bluewater Supercenter", "Bigsky Supercenter", "Tallpine Max", "Everfield")
    private val ANTIQUE = listOf("%s Antiques", "Old %s Antiques", "%s Attic Antiques", "%s Antiques & Curios")
    private val CHURCH = listOf("%s Community Church", "Grace Chapel", "%s Gospel Chapel", "New Hope Fellowship", "Living Water Chapel")
    private val CAFE = listOf("%s Coffee", "The Old Store Cafe", "%s Coffee House", "Porch Light Cafe")

    /**
     * Real chains, brands and their famous house names (grocers, supermarkets, discount and department stores, the
     * general stores' franchises): no store name, sign or slogan may contain one. Checked whole-word, ignoring case
     * and punctuation.
     */
    val REAL: List<String> = listOf(
        "Walmart", "Wal-Mart", "Wal Mart", "Kroger", "Safeway", "Piggly Wiggly", "A&P", "A & P", "Great Atlantic", "Target", "Costco", "Kmart", "K-Mart",
        "Meijer", "Publix", "Albertsons", "Winn-Dixie", "Winn Dixie", "IGA", "Food Lion", "Giant", "Stop & Shop", "Hy-Vee", "HEB", "H-E-B", "Aldi", "Lidl",
        "Sam's Club", "BJ's", "Ralphs", "Vons", "Jewel", "Red Owl", "Grand Union", "Food Fair", "Thriftway", "Big Bear", "ShopRite", "Wegmans",
        "Whole Foods", "Trader Joe's", "Sears", "Woolworth", "Woolworths", "Ben Franklin", "Dollar General", "Family Dollar", "Fred Meyer", "Harris Teeter",
        "Giant Eagle", "Save-A-Lot", "Food 4 Less", "Smith's", "King Soopers", "Fry's", "Dillons", "Bi-Lo", "Ingles", "Lowes", "Lowe's", "Pathmark", "Acme",
        "Shaw's", "Hannaford", "Market Basket", "Price Chopper", "Tops", "Weis", "Schnucks", "Dierbergs", "Raley's", "Stater Bros", "WinCo", "Sprouts",
        "Harvest Foods", "SuperValu", "Super Valu", "Cub Foods", "Rainbow Foods", "Food City", "Bradlees", "Caldor", "Zayre", "Ames", "Gibson's",
        "Korvettes", "Montgomery Ward", "JCPenney", "Penney", "Tesco", "Sainsbury", "Asda", "Carrefour", "Coles", "Spar", "Home Depot", "Menards",
        "Ace Hardware", "True Value", "Rexall", "Walgreens", "CVS", "Rite Aid", "Kwik", "7-Eleven", "Circle K", "Dollar Tree", "Big Lots", "Shopko",
        "Pamida", "Bi-Mart", "Fareway", "Brookshire", "Buehler", "Marsh", "Kash n Karry", "Jitney Jungle", "Bruno's", "Food World", "Big Star",
        "Colonial Stores", "National Tea", "Kohl's", "Mayfair", "Sunflower", "Super Fresh", "Superfresh", "Thrifty", "Lucky", "Alpha Beta", "Hughes",
        "Fred's", "Bill's", "Western Auto", "Coast to Coast", "Gamble's", "Mercantile Stores", "Dollar Store", "Supercenter Walmart",
    )

    private fun words(s: String) = s.uppercase().replace(Regex("[^A-Z0-9&]+"), " ").trim()

    /** Whether [s] contains a real name ([REAL]) as whole words. */
    fun isReal(s: String): Boolean {
        val t = " ${words(s)} "
        return REAL.any { r -> val w = words(r); w.isNotEmpty() && t.contains(" $w ") }
    }

    private fun pick(list: List<String>, seed: Int, salt: Int) = list[((seed * 31 + salt) and 0x7fffffff) % list.size]

    /** A made-up name for a store of [kind] (seeded; a general store or a reuse takes a family's or the [town]'s name). */
    fun name(kind: StoreKind, seed: Int, town: String = ""): String {
        val sur = pick(SURNAMES, seed, 7)
        val n = when (kind) {
            StoreKind.GENERAL -> pick(GENERAL, seed, 3).format(sur)
            StoreKind.MARKET -> pick(MARKET, seed, 5)
            StoreKind.SUPERMARKET -> pick(SUPER, seed, 11)
            StoreKind.SUPERSTORE -> pick(BIG, seed, 13)
        }
        return if (isReal(n)) "$sur Store" else n
    }

    /** A made-up name for an old store's new use ([StoreReuse]). */
    fun reuseName(reuse: StoreReuse, seed: Int, town: String = ""): String {
        val who = if (town.isNotBlank() && seed % 2 == 0) town.trim().replaceFirstChar { it.uppercase() } else pick(SURNAMES, seed, 17)
        val n = when (reuse) {
            StoreReuse.ANTIQUES -> pick(ANTIQUE, seed, 19).format(who)
            StoreReuse.CHURCH -> pick(CHURCH, seed, 23).format(who)
            StoreReuse.CAFE -> pick(CAFE, seed, 29).format(who)
        }
        return if (isReal(n)) "$who Hall" else n
    }
}

/** Sign and display painting for the stores. */
internal object StoreSigns {
    private fun c(rgb: Long) = Paint.c(rgb)

    // ------------------------------------------------------------------ lettering

    /** The largest BigFont scale (1..[maxScale]) at which [s] fits [w] x [h] texels. */
    fun fit(s: String, w: Int, h: Int, maxScale: Int = 4, gap: Int = 1): Int {
        var sc = maxScale
        while (sc > 1 && (BigFont.width(s, sc, gap * sc.coerceAtMost(2)) > w || BigFont.H * sc > h)) sc--
        return sc
    }

    /** Letters of [s] only (what the fonts draw), upper case. */
    fun clean(s: String) = s.uppercase().map { if (BigFont.has(it)) it else ' ' }.joinToString("").replace(Regex("\\s+"), " ").trim()

    /**
     * [s] centred in the box [x0]..[x1], [y0]..[y1] of [im] in the big font as large as fits (whole words dropped from
     * the end if it never fits), outlined in [out] (null for none) with an optional drop [shadow]; [fill] by the
     * letter's row (0 top) and its height. Returns the scale used.
     */
    fun letters(im: PixImg, s0: String, x0: Int, y0: Int, x1: Int, y1: Int, out: Int?, shadow: Int?, maxScale: Int = 4, fill: (Int, Int) -> Int): Int {
        var s = clean(s0)
        val pad = if (out != null) 2 else 0
        val w = x1 - x0 + 1 - pad * 2; val h = y1 - y0 + 1 - pad * 2
        var sc = fit(s, w, h, maxScale)
        while (s.isNotEmpty() && BigFont.width(s, sc, sc.coerceAtMost(2)) > w) s = if (s.contains(' ')) s.substringBeforeLast(' ') else s.dropLast(1)
        if (s.isEmpty()) return sc
        val gap = sc.coerceAtMost(2)
        val tw = BigFont.width(s, sc, gap); val th = BigFont.H * sc
        val lx = x0 + (x1 - x0 + 1 - tw) / 2; val ly = y0 + (y1 - y0 + 1 - th) / 2
        if (out != null) BigFont.outlined(im, s, lx, ly, sc, gap, out, shadow) { fill(it, th) }
        else {
            if (shadow != null) BigFont.draw(im, s, lx + 1, ly + 1, sc, gap) { shadow }
            BigFont.draw(im, s, lx, ly, sc, gap) { fill(it, th) }
        }
        return sc
    }

    /** Small lettering (the 3x5 font) centred at [cx] with its top at [y]. */
    fun small(im: PixImg, s: String, cx: Int, y: Int, col: Int) { val t = s.uppercase(); im.text(t, cx - PixImg.textWidth(t) / 2, y, col) }
    fun small(im: PixImg, cx: Int, y: Int, s: String, col: Int) = small(im, s, cx, y, col)

    // ------------------------------------------------------------------ goods (a few texels each: a can is 2 x 3)

    private val GOODS = intArrayOf(c(0xC82A2A), c(0xE8B82A), c(0x2A6AB8), c(0x3A9A4A), c(0xE86A2A), c(0xF0F0E0), c(0x8A3A9A), c(0xB8862A), c(0x2AA8A8), c(0xD84A7A))

    /** Shelves of cans, jars, boxes and bottles across [x0]..[x1] from [y0] to [y1], a board every [pitch] texels. */
    fun shelves(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, board: Int, seed: Int, pitch: Int = 9, old: Boolean = false) {
        val r = Paint.Rnd(seed)
        var y = y0 + pitch
        while (y <= y1) {
            im.hline(x0, x1, y, board); im.hline(x0, x1, y + 1, Pix.mix(board, c(0x000000), .4f))
            var x = x0 + 1
            while (x < x1 - 1) {
                val kind = r.int(if (old) 4 else 5)
                val col = GOODS[r.int(GOODS.size)]
                val w = when (kind) { 0 -> 2; 1 -> 2; 2 -> 3 + r.int(2); 3 -> 1; else -> 3 }
                val h = when (kind) { 0 -> 3; 1 -> 4; 2 -> 5 + r.int(2); 3 -> 5; else -> 4 }
                if (x + w > x1) break
                if (r.next() < .08f) { x += w + 1; continue }
                val top = y - h
                when (kind) {
                    0 -> { im.rect(x, top, x + w - 1, y - 1, col); im.hline(x, x + w - 1, top + 1, c(0xF0F0E0)); im[x, top] = Pix.mix(col, c(0xFFFFFF), .4f) } // a can with its label
                    1 -> { im.rect(x, top + 1, x + w - 1, y - 1, Pix.mix(col, c(0xF0E8C8), .5f)); im.hline(x, x + w - 1, top, c(0x8A8A9A)); im[x, top + 2] = c(0xFFFFFF) } // a jar
                    2 -> { im.rect(x, top, x + w - 1, y - 1, col); im.vline(x, top, y - 1, Pix.mix(col, c(0xFFFFFF), .3f)); im.hline(x + 1, x + w - 1, top + 2, c(0xF0F0E0)) } // a box
                    3 -> { im.vline(x, top + 2, y - 1, Pix.mix(col, c(0x2A4A2A), .5f)); im[x, top + 1] = c(0x3A3A2A) } // a bottle
                    else -> { im.rect(x, top, x + w - 1, y - 1, Pix.mix(col, c(0xE8D8B8), .3f)); im.hline(x, x + w - 1, top, Pix.mix(col, c(0x000000), .3f)) } // a packet
                }
                x += w + if (r.next() < .2f) 1 else 0
            }
            y += pitch
        }
    }

    /** A pyramid of cans standing on [base] centred at [cx], [rows] high. */
    fun canPyramid(im: PixImg, cx: Int, base: Int, rows: Int, col: Int) {
        for (row in 0 until rows) {
            val n = rows - row
            val y = base - row * 3
            for (k in 0 until n) {
                val x = cx - n * 3 / 2 + k * 3
                im.rect(x, y - 2, x + 1, y, col); im[x, y - 1] = c(0xF0F0E0); im[x + 1, y - 1] = c(0xF0F0E0); im[x, y - 2] = Pix.mix(col, c(0xFFFFFF), .4f)
            }
        }
    }

    /** A flour sack (a pale bag tied at the top, a stamp on it) with its foot at ([x], [base]). */
    fun sack(im: PixImg, x: Int, base: Int, w: Int = 6, h: Int = 8, cloth: Int = c(0xE8DCC0), stamp: Int = c(0x3A5A9A)) {
        for (y in 0 until h) {
            val inset = if (y < 2) 1 else 0
            im.hline(x + inset, x + w - 1 - inset, base - y, if (y == 0) Pix.mix(cloth, c(0x000000), .25f) else cloth)
        }
        im.hline(x + 1, x + w - 2, base - h, Pix.mix(cloth, c(0x000000), .3f))
        im.rect(x + 1, base - h / 2 - 1, x + w - 2, base - h / 2, stamp)
        im.vline(x + w - 1, base - h + 2, base, Pix.mix(cloth, c(0x000000), .2f))
    }

    /** A barrel's side ([w] x [h]) with its hoops, foot at [base]. */
    fun barrel(im: PixImg, x: Int, base: Int, w: Int = 7, h: Int = 10) {
        val wood = c(0x9A6438); val dark = c(0x6A4428); val hoop = c(0x4A4A5A)
        for (y in 0 until h) { val bulge = if (y in 2 until h - 2) 0 else 1; im.hline(x + bulge, x + w - 1 - bulge, base - y, wood) }
        for (k in 1 until w - 1 step 2) im.vline(x + k, base - h + 2, base - 1, dark)
        for (y in listOf(base - 1, base - h / 2, base - h + 2)) im.hline(x, x + w - 1, y, hoop)
    }

    /** A hanging lantern at ([x], [y]) (its ring at the top). */
    fun lantern(im: PixImg, x: Int, y: Int) {
        im[x + 1, y] = c(0x3A3A4A); im.hline(x, x + 2, y + 1, c(0x3A3A4A))
        im.rect(x, y + 2, x + 2, y + 4, c(0xF6D890)); im[x + 1, y + 3] = c(0xFFF6D0); im.hline(x, x + 2, y + 5, c(0x3A3A4A))
    }

    /** A card propped in a window: [s] lettered on it. */
    fun card(im: PixImg, x: Int, y: Int, s: String, paper: Int = c(0xF6F0DC), ink: Int = c(0xA82A2A)) {
        val w = PixImg.textWidth(s.uppercase()) + 4
        im.rect(x, y, x + w - 1, y + 8, paper); im.border2(x, y, x + w - 1, y + 8, Pix.mix(paper, c(0x000000), .3f))
        im.text(s.uppercase(), x + 2, y + 2, ink)
    }

    private fun PixImg.border2(x0: Int, y0: Int, x1: Int, y1: Int, col: Int) { hline(x0, x1, y0, col); hline(x0, x1, y1, col); vline(x0, y0, y1, col); vline(x1, y0, y1, col) }

    /** Glass: dark blue-grey with a pale streak (no room behind it shows by day). */
    fun glass(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int = 0) {
        im.rect(x0, y0, x1, y1, c(0x2A3A54))
        // two pale streaks of reflected sky, kept inside the pane
        for (k in 0..(x1 - x0 + (y1 - y0) / 2) step 23) for (y in y0..y1) {
            val x = x0 + k + seed % 7 - (y - y0) / 2
            if (x in x0..x1) im[x, y] = c(0x3E5470)
        }
    }

    /**
     * A general store's window display in [x0]..[x1] x [y0]..[y1]: the room's shelves behind (dim warm), a table of
     * goods in the window (a can pyramid, sacks, a barrel, bolts of cloth, a lantern), a card or two; [old] for the early
     * decades (more sacks and barrels). Lit in the lamp's colour at night.
     */
    fun generalWindow(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int, old: Boolean) {
        val wall = c(0x6A4A30); val floor = c(0x4A3424)
        im.rect(x0, y0, x1, y1, wall)
        for (x in x0 until x1 step 5) im.vline(x, y0, y1, Pix.mix(wall, c(0x000000), .15f))
        shelves(im, x0 + 1, y0 + 1, x1 - 1, y0 + (y1 - y0) * 6 / 10, c(0xA8784A), seed, 8, old)
        im.rect(x0, y0 + (y1 - y0) * 7 / 10, x1, y1, floor)
        val r = Paint.Rnd(seed + 5)
        val base = y1 - 2
        // the window table and its goods
        im.hline(x0 + 1, x1 - 1, base - 9, c(0x8A5A34)); im.hline(x0 + 1, x1 - 1, base - 8, c(0x5A3A24))
        canPyramid(im, x0 + (x1 - x0) / 3, base - 10, 3 + r.int(2), GOODS[r.int(GOODS.size)])
        // bolts of cloth standing on the table
        for (k in 0 until 3) { val bx = x0 + (x1 - x0) * 6 / 10 + k * 3; im.rect(bx, base - 16, bx + 1, base - 10, GOODS[(seed + k * 3) % GOODS.size]); im[bx, base - 16] = c(0xFFFFFF) }
        // sacks and a barrel on the floor below
        sack(im, x0 + 2, base); if (old) sack(im, x0 + 8, base, cloth = c(0xD8C8A0), stamp = c(0xA83A2A))
        barrel(im, x1 - 9, base)
        if (old) barrel(im, x1 - 17, base, 6, 9)
        lantern(im, x0 + (x1 - x0) / 2, y0 + 1)
        lantern(im, x0 + (x1 - x0) / 5, y0 + 2)
        if (x1 - x0 > 40) card(im, x0 + (x1 - x0) / 2 + 2, base - 22, listOf("FRESH EGGS", "FEED", "NOTIONS", "SEED", "TOBACCO", "COLD POP")[r.int(6)])
        // a frame of sash bars over it
        im.vline((x0 + x1) / 2, y0, y1, c(0x3A2A1E))
        im.glow(x0, y0, x1, y1, 120)
    }

    /** An antiques shop's window: a lamp with a shade, a clock, a chair, quilts and china on a dresser; lit warm at night. */
    fun antiquesWindow(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int) {
        val wall = c(0x5A3A3A)
        im.rect(x0, y0, x1, y1, wall)
        val base = y1 - 2
        val r = Paint.Rnd(seed)
        // a dresser with china on it
        val dx = x0 + 3; val dw = (x1 - x0) / 3
        im.rect(dx, base - 14, dx + dw, base, c(0x6A3A1E)); im.hline(dx, dx + dw, base - 14, c(0x9A5A2E))
        for (k in 0 until 3) im.hline(dx + 1, dx + dw - 1, base - 11 + k * 4, c(0x4A2814))
        for (k in 0 until dw / 3) { val px = dx + 1 + k * 3; im.rect(px, base - 17, px + 1, base - 15, if (k % 2 == 0) c(0xF0F0F6) else c(0x5A7AC8)) }
        // a tall clock
        val cx = x0 + (x1 - x0) / 2
        im.rect(cx, base - 26, cx + 4, base, c(0x5A2E14)); Pix.disc(im, cx + 2f, base - 22f, 2f, c(0xF0E8C8)); im[cx + 2, base - 22] = c(0x1A1A1A)
        // a chair
        val chx = x1 - 12
        im.vline(chx, base - 14, base, c(0x7A4A24)); im.vline(chx + 5, base - 6, base, c(0x7A4A24)); im.hline(chx, chx + 5, base - 6, c(0x9A6A3A))
        for (y in base - 14 until base - 6 step 3) im.hline(chx, chx + 1, y, c(0x9A6A3A))
        // a lamp with a fringed shade
        val lx = dx + dw / 2
        im.vline(lx, base - 22, base - 17, c(0xB8862A)); im.rect(lx - 2, base - 26, lx + 2, base - 23, c(0xE8A0A0)); im.hline(lx - 3, lx + 3, base - 23, c(0xC86A6A))
        // a quilt hung at the back
        for (y in y0 + 2 until y0 + 12) for (x in x1 - 14 until x1 - 2) im[x, y] = GOODS[((x / 3) + (y / 3) + seed) % GOODS.size].let { Pix.mix(it, c(0xF0E8D8), .35f) }
        card(im, x0 + 2, y0 + 2, if (r.next() < .5f) "OPEN" else "BUY SELL")
        im.vline((x0 + x1) / 2, y0, y1, c(0x2A1E14))
        im.glow(x0, y0, x1, y1, 120)
    }

    /** A chapel's window: panes of coloured paper "stained glass" in a cross pattern, lit in their own colours at night. */
    fun chapelWindow(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int) {
        val cols = intArrayOf(c(0xC83A3A), c(0x3A6AC8), c(0xE8C84A), c(0x3A9A5A), c(0x8A4AB8))
        for (y in y0..y1) for (x in x0..x1) im[x, y] = Pix.mix(cols[((x - x0) / 5 + (y - y0) / 6 * 3 + seed) % cols.size], c(0x2A2A3A), .25f)
        for (x in x0..x1 step 5) im.vline(x, y0, y1, c(0x1A1A22))
        for (y in y0..y1 step 6) im.hline(x0, x1, y, c(0x1A1A22))
        val cx = (x0 + x1) / 2; val cy = y0 + (y1 - y0) * 2 / 5
        im.rect(cx - 1, y0 + 3, cx + 1, y1 - 3, c(0xF6E8B0)); im.rect(cx - 6, cy - 1, cx + 6, cy + 1, c(0xF6E8B0))
        im.glow(x0, y0, x1, y1, 160)
    }

    /** A cafe's window: a counter with a cake stand and cups, a chalk menu, hanging bulbs; lit warm at night. */
    fun cafeWindow(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int) {
        im.rect(x0, y0, x1, y1, c(0x7A5A3E))
        val base = y1 - 2
        im.rect(x0 + 2, y0 + 4, x0 + (x1 - x0) / 2, y0 + 18, c(0x23302A)); for (k in 0 until 4) im.hline(x0 + 4, x0 + (x1 - x0) / 2 - 3, y0 + 7 + k * 3, c(0xD8D8C8))
        im.rect(x0 + 1, base - 10, x1 - 1, base, c(0x4A3022)); im.hline(x0 + 1, x1 - 1, base - 10, c(0xC8A070))
        val cx = x1 - (x1 - x0) / 3
        im.hline(cx - 4, cx + 4, base - 11, c(0xE8E8F0)); im.vline(cx, base - 13, base - 11, c(0xE8E8F0)); im.rect(cx - 3, base - 17, cx + 3, base - 14, c(0xF0C8D8)); im.hline(cx - 3, cx + 3, base - 17, c(0xFFF0F6))
        for (k in 0 until 3) { val px = x0 + 4 + k * 4; im.rect(px, base - 13, px + 2, base - 11, c(0xF6F6F0)); im[px + 3, base - 12] = c(0xF6F6F0) }
        for (x in x0 + 3 until x1 - 2 step 6) { im[x, y0 + 1] = c(0x2A2A2A); im[x, y0 + 2] = c(0xFFE8A0) }
        im.vline((x0 + x1) / 2, y0, y1, c(0x2A1E14))
        im.glow(x0, y0, x1, y1, 130)
    }

    /** Boards nailed over a window or door: weathered planks, an X of two more, nail heads. */
    fun boarded(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int) {
        val r = Paint.Rnd(seed)
        val tones = intArrayOf(c(0x8A7A62), c(0x9A8A70), c(0x7A6A54), c(0xA89A80))
        var y = y0
        while (y <= y1) {
            val h = 4 + r.int(2)
            val t = tones[r.int(tones.size)]
            im.rect(x0, y, x1, min(y1, y + h - 1), t)
            im.hline(x0, x1, min(y1, y + h - 1), Pix.mix(t, c(0x000000), .35f))
            for (k in 0 until 3) im[x0 + r.int(max(1, x1 - x0)), y + 1 + r.int(max(1, h - 2))] = Pix.mix(t, c(0x000000), .2f)
            im[x0 + 1, y + 1] = c(0x3A3A3A); im[x1 - 1, y + 1] = c(0x3A3A3A)
            y += h
        }
        // gaps between some boards (the dark inside)
        for (k in 0 until 2) { val gy = y0 + 3 + r.int(max(1, y1 - y0 - 6)); im.hline(x0, x1, gy, c(0x1A1A1E)) }
        val w = x1 - x0; val h = y1 - y0
        for (t in 0..max(w, h)) {
            val fx = x0 + t * w / max(w, h); val fy = y0 + t * h / max(w, h)
            for (d in 0..2) { im[fx + d, fy] = c(0xB0A07E); im[x1 - (fx - x0) - d, fy] = c(0xA8987A) }
        }
    }

    // ------------------------------------------------------------------ supermarkets

    /**
     * A supermarket seen through its glass ([x0]..[x1] x [y0]..[y1]): a white ceiling with rows of fluorescent tubes, the
     * aisles' ends of stacked goods, checkout lanes with their lights and a few shoppers, the floor's tiles; lit in its
     * own colours (cool white) at night. [era] 0 (1950s), 1 (1970s-80s), 2 (1990s on, a big box's taller room).
     */
    fun aisles(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, seed: Int, era: Int, glow: Int = 110) {
        val ceil = when (era) { 0 -> c(0xC8C4B4); 1 -> c(0xB8B098); else -> c(0xB0B4BC) }
        val wall = when (era) { 0 -> c(0x88A898); 1 -> c(0xA88A58); else -> c(0xA8AAB0) }
        val floor = when (era) { 0 -> c(0x9A8C70); 1 -> c(0x8A7C64); else -> c(0xA0A098) }
        val h = y1 - y0
        val ct = y0 + max(2, h / 6)
        val ft = y1 - max(3, h / 5)
        im.rect(x0, y0, x1, ct, ceil)
        im.rect(x0, ct + 1, x1, ft, wall)
        im.rect(x0, ft + 1, x1, y1, floor)
        for (x in x0 until x1 step 4) for (y in ft + 1..y1) if ((x / 4 + y) % 2 == 0) im[x, y] = Pix.mix(floor, c(0x000000), .1f)
        // the tubes in rows
        for (x in x0 + 2 until x1 - 3 step 9) { im.hline(x, x + 4, y0 + 1, c(0xFFFFFF)); if (h > 30) im.hline(x + 2, x + 5, ct - 1, c(0xF6FAFF)) }
        // aisle ends: tall gondolas of goods, a hanging aisle number over each
        val r = Paint.Rnd(seed)
        var x = x0 + 2
        var k = 1
        val pitch = if (era == 2) 13 else 11
        while (x + 8 < x1) {
            val gw = 7
            val gt = ct + 3 + (if (era == 2) 0 else 1)
            im.rect(x, gt, x + gw, ft, c(0xE8E8EC))
            shelves(im, x + 1, gt, x + gw - 1, ft - 1, c(0xB8B8C0), seed + k * 13, 5)
            if (era >= 1) { im.rect(x + 1, y0 + 2, x + gw - 1, y0 + 5, if (era == 1) c(0xE86A2A) else c(0x2A5AA8)); im.text("${k % 10}", x + 3, y0 + 1, c(0xFFFFFF)) }
            x += pitch; k++
        }
        // checkout lanes along the front: a counter, a lit lane number, a cashier, a shopper with a cart
        var cx = x0 + 3
        var lane = 1
        while (cx + 10 < x1) {
            val top = ft - 5
            im.rect(cx, top, cx + 8, ft + 2, c(0x6A6A78)); im.hline(cx, cx + 8, top, c(0x9A9AA8))
            im.rect(cx + 1, top - 3, cx + 3, top - 1, c(0x2A2A34)); im[cx + 2, top - 2] = c(0x5AF07A)
            im.rect(cx + 6, top - 11, cx + 8, top - 9, c(0xF6F6F0)); im.text("${lane % 10}", cx + 6, top - 11, c(0xC82A2A))
            if (r.next() < .6f) Pix.figure(im, cx + 11f, ft + 2f, (h * .22f).coerceIn(6f, 14f), GOODS[r.int(GOODS.size)].let { Pix.mix(it, c(0x2A2A3A), .55f) }, pose = r.int(2) * 4)
            cx += 17; lane++
        }
        for (yy in y0..y1) for (xx in x0..x1) if (xx in 0 until im.w && yy in 0 until im.h) im.mask[yy * im.w + xx] = (255 shl 24) or (glow shl 16) or 255
        im.glow(x0, y0, x0, y0, glow)
    }

    /** A paper special stuck on the glass: a coloured sheet with a big word and a price line. */
    fun special(im: PixImg, x: Int, y: Int, w: Int, h: Int, word: String, line: String, paper: Int, ink: Int) {
        im.rect(x, y, x + w - 1, y + h - 1, paper)
        letters(im, word, x + 1, y + 1, x + w - 2, y + h * 6 / 10, null, null, 2) { _, _ -> ink }
        small(im, line, x + w / 2, y + h - 7, ink)
        im.glow(x, y, x + w - 1, y + h - 1, 110)
    }

    /** A shopping cart's side as a cut-out ([CART_W] x [CART_H] texels): the wire basket (see-through), its frame, wheels. */
    fun cartSide(handle: Int): PixImg = PixImg(CART_W, CART_H).also { im ->
        val wire = c(0xB8BCC8); val frame = c(0x8A8E9A)
        for (y in 2 until 11) for (x in 2 + (11 - y) / 4 until CART_W - 1) if (x % 2 == 0 || y % 3 == 0) im[x, y] = wire
        im.hline(1, CART_W - 1, 2, frame); im.hline(3, CART_W - 1, 11, frame)
        im.vline(CART_W - 1, 2, 11, frame)
        Pix.line(im, 1, 2, 3, 11, frame)
        // the lower rack, the legs, the wheels
        im.hline(4, CART_W - 2, 15, frame); Pix.line(im, 3, 11, 4, 15, frame); im.vline(CART_W - 2, 11, 15, frame)
        im.rect(3, 16, 4, 17, c(0x1A1A22)); im.rect(CART_W - 3, 16, CART_W - 2, 17, c(0x1A1A22))
        // the handle (in the store's colour), standing up behind
        im.vline(0, 0, 3, frame); im.hline(0, 3, 0, handle); im[0, 1] = handle
    }

    const val CART_W = 18
    const val CART_H = 18
}
