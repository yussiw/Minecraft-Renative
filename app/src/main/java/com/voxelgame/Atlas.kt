package com.voxelgame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Atlas de texturas. Por padrao e gerado por codigo (nenhum asset externo).
 * Se o usuario escolher um resource pack (.zip no formato do Minecraft), as texturas
 * dele substituem as geradas; o que faltar no pack continua usando a versao gerada.
 */
object Atlas {
    const val TILES = 8
    const val COUNT = TILES * TILES   // 64 tiles no atlas
    const val BASE = 20               // 0..18 = blocos/itens do jogo, 19 = textura "faltando"
    const val MISSING = 19            // 20..63 = tiles de blocos de mods
    const val MAX_TS = 128

    private val GRASS_TINT = 0xFF91BD59.toInt()
    private val FOLIAGE_TINT = 0xFF77AB2F.toInt()
    private val WATER_TINT = 0xFF3F76E4.toInt()

    private val procedural: Array<IntArray> = makeProcedural()

    @Volatile var ts = 16
        private set
    @Volatile var bitmap: Bitmap = compose(procedural, 16)
        private set
    val version = AtomicInteger(0)

    // ------------------------------------------------------------ gerado por codigo

    private fun r(x: Int, y: Int, s: Int): Float = (Noise.hash(x, y, s * 7919 + 13) and 0xFFFF) / 65535f

    private fun col(rr: Int, gg: Int, bb: Int, v: Float): Int {
        val f = 1f + v
        return Color.rgb(
            (rr * f).toInt().coerceIn(0, 255),
            (gg * f).toInt().coerceIn(0, 255),
            (bb * f).toInt().coerceIn(0, 255)
        )
    }

    private fun makeProcedural(): Array<IntArray> {
        val t = Array(COUNT) { IntArray(16 * 16) }
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val i = y * 16 + x
                t[MISSING][i] = if (((x / 4) + (y / 4)) % 2 == 0) Color.rgb(248, 0, 248) else Color.BLACK
                t[0][i] = col(95, 165, 60, (r(x, y, 2) - 0.5f) * 0.35f)
                val fringe = 3 + (r(x, 0, 3) * 3f).toInt()
                t[1][i] = if (y < fringe) col(95, 165, 60, (r(x, y, 4) - 0.5f) * 0.35f)
                else col(134, 96, 67, (r(x, y, 5) - 0.5f) * 0.3f)
                t[2][i] = col(134, 96, 67, (r(x, y, 6) - 0.5f) * 0.3f)
                t[3][i] = col(125, 125, 125, (r(x, y, 7) - 0.5f) * 0.28f)
                t[4][i] = col(219, 207, 150, (r(x, y, 8) - 0.5f) * 0.10f)
                t[5][i] = col(104, 82, 50, (r(x, 0, 9) - 0.5f) * 0.45f + (r(x, y, 10) - 0.5f) * 0.12f)
                val dd = sqrt((x - 7.5f) * (x - 7.5f) + (y - 7.5f) * (y - 7.5f))
                val edge = x == 0 || y == 0 || x == 15 || y == 15
                t[6][i] = if (edge) col(104, 82, 50, (r(x, y, 11) - 0.5f) * 0.2f)
                else col(176, 140, 84, sin(dd * 1.7f) * 0.10f + (r(x, y, 12) - 0.5f) * 0.06f)
                t[7][i] = if (r(x, y, 13) > 0.82f) col(28, 80, 22, 0f)
                else col(54, 124, 38, (r(x, y, 14) - 0.5f) * 0.45f)
                val joint = ((y / 4) % 2) * 8
                val dark = (y % 4 == 3) || x == joint
                t[8][i] = col(164, 131, 79, (if (dark) -0.22f else 0f) + (r(x, y, 15) - 0.5f) * 0.10f)
                val cell = Noise.hash(x / 4, y / 4, 99)
                val shade = ((cell and 0xFF) / 255f - 0.5f) * 0.35f
                val e2 = (x % 4 == 0) || (y % 4 == 0)
                t[9][i] = col(118, 118, 118, shade + (if (e2) -0.22f else 0f) + (r(x, y, 16) - 0.5f) * 0.08f)
                // neve
                t[10][i] = col(240, 245, 252, (r(x, y, 17) - 0.5f) * 0.07f)
                // cascalho
                val gv = Noise.hash(x / 2, y / 2, 55)
                t[11][i] = col(132, 128, 124, ((gv and 0xFF) / 255f - 0.5f) * 0.55f + (r(x, y, 18) - 0.5f) * 0.12f)
                // vidro (borda opaca, miolo transparente)
                val gEdge = x == 0 || y == 0 || x == 15 || y == 15
                val gShine = (x + y == 6) || (x + y == 7) || (x + y == 12)
                t[12][i] = if (gEdge) Color.argb(255, 205, 232, 240)
                else if (gShine && x > 1 && y > 1 && x < 14 && y < 14) Color.argb(120, 255, 255, 255)
                else 0
                // agua (semi-transparente)
                val wv = col(36, 92, 205, (r(x, y, 19) - 0.5f) * 0.18f)
                t[13][i] = (175 shl 24) or (wv and 0xFFFFFF)
                // tijolos
                val mortar = (y % 4 == 3) || (x + (if ((y / 4) % 2 == 0) 0 else 4)) % 8 == 7
                t[14][i] = if (mortar) col(176, 170, 160, (r(x, y, 20) - 0.5f) * 0.10f)
                else col(152, 76, 58, (r(x, y, 21) - 0.5f) * 0.22f)
                // maca (item)
                val ax = x - 7.5f
                val ay = y - 9f
                val ad = Math.sqrt((ax * ax + ay * ay * 1.1f).toDouble()).toFloat()
                t[15][i] = if (x == 8 && y in 2..4) col(110, 78, 40, 0f)
                else if ((x in 9..11 && y == 3) || (x in 10..11 && y == 2)) col(70, 150, 40, 0f)
                else if (ad < 5.3f) {
                    val hl = if (ax < -1.5f && ay < -1f) 0.22f else if (ax > 1.5f && ay > 1.5f) -0.2f else 0f
                    col(205, 32, 32, hl + (r(x, y, 22) - 0.5f) * 0.1f)
                } else 0
                // grama alta (planta)
                val bh = 5 + (r(x, 0, 30) * 9f).toInt()
                val blade = y >= 16 - bh && r(x, 1, 31) > 0.2f
                t[16][i] = if (blade) col(98, 170, 58, ((16 - y) / 16f) * 0.25f - 0.1f + (r(x, y, 32) - 0.5f) * 0.15f) else 0
                // dente-de-leao
                val dStem = x == 8 && y in 8..15
                val dLeaf = (x == 7 && y == 12) || (x == 9 && y == 11)
                val dCenter = x in 7..9 && y in 5..6
                val dPetal = x in 6..10 && y in 4..7 && !((x == 6 || x == 10) && (y == 4 || y == 7))
                t[17][i] = if (dCenter) col(235, 160, 20, 0f)
                else if (dPetal) col(250, 215, 30, (r(x, y, 33) - 0.5f) * 0.15f)
                else if (dStem || dLeaf) col(60, 130, 40, 0f) else 0
                // margarida
                val mx = Math.abs(x - 8)
                val my = Math.abs(y - 5)
                val mCenter = mx <= 1 && my <= 1
                val mRing = mx <= 3 && my <= 2 && !(mx == 3 && my == 2)
                t[18][i] = if (mCenter) col(240, 190, 30, 0f)
                else if (mRing) col(245, 245, 245, (r(x, y, 34) - 0.5f) * 0.08f)
                else if (dStem || dLeaf) col(60, 130, 40, 0f) else 0
            }
        }
        return t
    }

    // ------------------------------------------------------------ montagem do atlas

    private fun scale(src: IntArray, sw: Int, dw: Int): IntArray {
        if (sw == dw) return src
        val out = IntArray(dw * dw)
        for (y in 0 until dw) {
            val sy = y * sw / dw
            for (x in 0 until dw) out[y * dw + x] = src[sy * sw + x * sw / dw]
        }
        return out
    }

    private fun compose(tiles: Array<IntArray>, size: Int): Bitmap {
        val side = TILES * size
        val pix = IntArray(side * side)
        for (t in 0 until COUNT) {
            val tx = (t % TILES) * size
            val ty = (t / TILES) * size
            val px = tiles[t]
            for (y in 0 until size) {
                System.arraycopy(px, y * size, pix, (ty + y) * side + tx, size)
            }
        }
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pix, 0, side, 0, 0, side, side)
        return bmp
    }

    // ------------------------------------------------------------ estado (pack + mods)

    class Tex(val px: IntArray, val size: Int)

    /** Resultado de carregar um pack: texturas de bloco/item, sprites do HUD e texturas de mods trocadas. */
    class PackInfo(val blocks: Int, val hud: Int, val mods: Int) {
        val total: Int get() = blocks + hud + mods
    }

    private val packTex = arrayOfNulls<Tex>(COUNT)   // vindo do resource pack
    private val modTex = arrayOfNulls<Tex>(COUNT)    // vindo dos .jar de mods
    private val modKey = arrayOfNulls<String>(COUNT) // "namespace:block/nome" (para o pack poder sobrescrever)
    private val modKeep = BooleanArray(COUNT)        // true = sprite de item (mantem a transparencia)

    /** Junta pack > mod > textura gerada e sobe um atlas novo. */
    @Synchronized
    fun rebuild() {
        var size = 16
        for (i in 0 until COUNT) {
            val f = packTex[i] ?: modTex[i]
            if (f != null) size = max(size, f.size)
        }
        var p2 = 16
        while (p2 < size && p2 < MAX_TS) p2 *= 2
        val tiles = Array(COUNT) { i ->
            val f = packTex[i] ?: modTex[i]
            if (f != null) scale(f.px, f.size, p2) else scale(procedural[i], 16, p2)
        }
        val bmp = compose(tiles, p2)
        ts = p2
        bitmap = bmp
        version.incrementAndGet()
    }

    @Synchronized
    fun clearMods() {
        for (i in BASE until COUNT) { modTex[i] = null; modKey[i] = null; packTex[i] = null; modKeep[i] = false }
    }

    /**
     * Reserva um tile para uma textura de mod. Devolve o indice do tile, ou -1 se o atlas encheu.
     * keepAlpha = sprite de item: mantem a transparencia (senao o fundo vira um quadrado escuro).
     */
    @Synchronized
    fun allocModTile(key: String, tex: Tex?, keepAlpha: Boolean = false): Int {
        for (i in BASE until COUNT) if (modKey[i] == key && modKeep[i] == keepAlpha) return i
        for (i in BASE until COUNT) {
            if (modKey[i] != null) continue
            modKey[i] = key
            modKeep[i] = keepAlpha
            val t = tex ?: Tex(procedural[MISSING], 16)
            modTex[i] = if (keepAlpha && tex != null) t else prepMod(t)
            return i
        }
        return -1
    }

    /** Blocos de mod sao cubos opacos: transparencia vira fundo escuro. */
    private fun prepMod(t: Tex): Tex {
        val px = t.px.copyOf()
        for (i in px.indices) if ((px[i] ushr 24) != 255) px[i] = over(Color.rgb(48, 48, 48), px[i])
        return Tex(px, t.size)
    }

    // ------------------------------------------------------------ decodificacao

    private fun opts() = BitmapFactory.Options().apply {
        inScaled = false          // nunca reescalar por densidade da tela
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /** PNG -> primeiro quadro quadrado (texturas animadas sao uma tira vertical). */
    fun decodeTex(ins: InputStream): Tex? {
        val bmp = BitmapFactory.decodeStream(ins, null, opts()) ?: return null
        val w = bmp.width
        if (w < 4 || bmp.height < w) { bmp.recycle(); return null }
        val px = IntArray(w * w)
        bmp.getPixels(px, 0, w, 0, 0, w, w)
        bmp.recycle()
        return Tex(px, w)
    }

    private fun tint(px: IntArray, color: Int) {
        val tr = Color.red(color)
        val tg = Color.green(color)
        val tb = Color.blue(color)
        for (i in px.indices) {
            val p = px[i]
            px[i] = Color.argb(
                p ushr 24,
                Color.red(p) * tr / 255,
                Color.green(p) * tg / 255,
                Color.blue(p) * tb / 255
            )
        }
    }

    private fun over(base: Int, top: Int): Int {
        val a = top ushr 24
        if (a == 255) return top
        if (a == 0) return base
        val ia = 255 - a
        return Color.rgb(
            (Color.red(top) * a + Color.red(base) * ia) / 255,
            (Color.green(top) * a + Color.green(base) * ia) / 255,
            (Color.blue(top) * a + Color.blue(base) * ia) / 255
        )
    }

    // ------------------------------------------------------------ resource pack

    private val PACK_RE = Regex("(?:^|/)assets/([^/]+)/textures/(.+)\\.png$")

    /**
     * Indexa todos os PNG de assets/<namespace>/textures/ como "namespace:caminho" (minusculo, sem .png).
     * Aceita pack dentro de uma pasta extra e ignora pastas "overlay*" quando ha duplicata.
     */
    private fun indexPack(zf: ZipFile): HashMap<String, ZipEntry> {
        val out = HashMap<String, ZipEntry>()
        val rank = HashMap<String, Int>()
        val en = zf.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val n = e.name.replace('\\', '/').lowercase()
            if (n.contains("__macosx") || n.substringAfterLast('/').startsWith("._")) continue
            val m = PACK_RE.find(n) ?: continue
            val key = m.groupValues[1] + ":" + m.groupValues[2]
            val pre = n.substring(0, m.range.first)
            val r = if (pre.isEmpty()) 0 else if (pre.startsWith("overlay") || pre.contains("/overlay")) 2 else 1
            val old = rank[key]
            if (old == null || r < old) { out[key] = e; rank[key] = r }
        }
        return out
    }

    private fun crop(sheet: Bitmap?, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        if (sheet == null || sheet.width < 256) return null
        val s = sheet.width / 256f
        val sx = (x * s).toInt()
        val sy = (y * s).toInt()
        val sw = (w * s).toInt()
        val sh = (h * s).toInt()
        if (sw < 1 || sh < 1 || sx + sw > sheet.width || sy + sh > sheet.height) return null
        return try { Bitmap.createBitmap(sheet, sx, sy, sw, sh) } catch (ex: Throwable) { null }
    }

    /** Carrega o pack. So troca o estado se achar algo util (total 0 = pack invalido). */
    @Synchronized
    fun loadPack(path: String): PackInfo {
        ZipFile(path).use { zf ->
            val idx = indexPack(zf)

            fun tex(vararg paths: String): Tex? {
                for (p in paths) {
                    val e = idx["minecraft:$p"] ?: continue
                    val t = try { zf.getInputStream(e).use { decodeTex(it) } } catch (ex: Throwable) { null }
                    if (t != null) return t
                }
                return null
            }

            fun bmp(vararg paths: String): Bitmap? {
                for (p in paths) {
                    val e = idx["minecraft:$p"] ?: continue
                    val b = try { zf.getInputStream(e).use { BitmapFactory.decodeStream(it, null, opts()) } } catch (ex: Throwable) { null }
                    if (b != null) return b
                }
                return null
            }

            val found = arrayOfNulls<Tex>(COUNT)

            // Como no Java: grama, folhas e agua SEMPRE recebem a cor do bioma (a textura e cinza)
            found[0] = tex("block/grass_block_top", "blocks/grass_top")?.also { tint(it.px, GRASS_TINT) }

            val side = tex("block/grass_block_side", "blocks/grass_side")
            val overlay = tex("block/grass_block_side_overlay", "blocks/grass_side_overlay")
            if (side != null) {
                if (overlay != null) {
                    tint(overlay.px, GRASS_TINT)
                    val o = scale(overlay.px, overlay.size, side.size)
                    for (i in side.px.indices) side.px[i] = over(side.px[i], o[i])
                }
                found[1] = side
            }
            found[2] = tex("block/dirt", "blocks/dirt")
            found[3] = tex("block/stone", "blocks/stone")
            found[4] = tex("block/sand", "blocks/sand")
            found[5] = tex("block/oak_log", "blocks/log_oak")
            found[6] = tex("block/oak_log_top", "blocks/log_oak_top")
            found[7] = tex("block/oak_leaves", "blocks/leaves_oak")?.also {
                tint(it.px, FOLIAGE_TINT)
                for (i in it.px.indices) it.px[i] = over(Color.rgb(28, 70, 20), it.px[i]) // furos viram sombra
            }
            found[8] = tex("block/oak_planks", "blocks/planks_oak")
            found[9] = tex("block/cobblestone", "blocks/cobblestone")
            found[10] = tex("block/snow", "blocks/snow")
            found[11] = tex("block/gravel", "blocks/gravel")
            found[12] = tex("block/glass", "blocks/glass")
            found[13] = tex("block/water_still", "blocks/water_still", "block/water_flow", "blocks/water_flow")?.also {
                tint(it.px, WATER_TINT)
                for (i in it.px.indices) {
                    val a = it.px[i] ushr 24
                    it.px[i] = ((if (a >= 255) 175 else a) shl 24) or (it.px[i] and 0xFFFFFF)
                }
            }
            found[14] = tex("block/bricks", "blocks/brick")
            // item: a maca vive em textures/item (ou textures/items nos packs antigos)
            found[15] = tex("item/apple", "items/apple")
            // 1.20.3+ = short_grass, 1.13-1.20.2 = grass, antigo = tallgrass
            found[16] = tex("block/short_grass", "block/grass", "blocks/tallgrass")?.also { tint(it.px, GRASS_TINT) }
            found[17] = tex("block/dandelion", "blocks/flower_dandelion")
            found[18] = tex("block/oxeye_daisy", "blocks/flower_oxeye_daisy")
            val blocks = found.count { it != null }

            // texturas de blocos de mods trocadas pelo pack (assets/<mod>/textures/block/...)
            var mods = 0
            for (i in BASE until COUNT) {
                val k = modKey[i] ?: continue
                val e = idx[k] ?: continue
                val t = try { zf.getInputStream(e).use { decodeTex(it) } } catch (ex: Throwable) { null }
                if (t != null) { found[i] = t; mods++ }
            }

            // HUD: sprites novos (1.20.2+) e, se faltar, icons.png / widgets.png antigos
            val icons = bmp("gui/icons")
            val widgets = bmp("gui/widgets")
            val skin = Skin(
                heartC = bmp("gui/sprites/hud/heart/container") ?: crop(icons, 16, 0, 9, 9),
                heartF = bmp("gui/sprites/hud/heart/full") ?: crop(icons, 52, 0, 9, 9),
                heartH = bmp("gui/sprites/hud/heart/half") ?: crop(icons, 61, 0, 9, 9),
                foodE = bmp("gui/sprites/hud/food_empty") ?: crop(icons, 16, 27, 9, 9),
                foodF = bmp("gui/sprites/hud/food_full") ?: crop(icons, 52, 27, 9, 9),
                foodH = bmp("gui/sprites/hud/food_half") ?: crop(icons, 61, 27, 9, 9),
                air = bmp("gui/sprites/hud/air") ?: crop(icons, 16, 18, 9, 9),
                hotbar = bmp("gui/sprites/hud/hotbar") ?: crop(widgets, 0, 0, 182, 22),
                hotbarSel = bmp("gui/sprites/hud/hotbar_selection") ?: crop(widgets, 0, 22, 24, 24),
                xpBg = bmp("gui/sprites/hud/experience_bar_background") ?: crop(icons, 0, 64, 182, 5),
                cross = bmp("gui/sprites/hud/crosshair") ?: crop(icons, 0, 0, 15, 15),
                slot = bmp("gui/sprites/container/slot")
            )
            val hud = skin.count()

            // skin do jogador (entity/player/wide/steve.png ou entity/steve.png)
            val skinBmp = bmp("entity/player/wide/steve", "entity/steve", "entity/player/steve")
            PlayerSkin.setFromPack(skinBmp)

            if (blocks + mods + hud == 0) return PackInfo(0, 0, 0)

            for (i in 0 until COUNT) packTex[i] = found[i]
            HudSkin.current = skin
            rebuild()
            return PackInfo(blocks, hud, mods)
        }
    }
}

/** Sprites do HUD vindos do resource pack. Null = usa o desenho gerado por codigo. */
class Skin(
    val heartC: Bitmap? = null, val heartF: Bitmap? = null, val heartH: Bitmap? = null,
    val foodE: Bitmap? = null, val foodF: Bitmap? = null, val foodH: Bitmap? = null,
    val air: Bitmap? = null, val hotbar: Bitmap? = null, val hotbarSel: Bitmap? = null,
    val xpBg: Bitmap? = null, val cross: Bitmap? = null, val slot: Bitmap? = null
) {
    fun count(): Int = listOfNotNull(
        heartC, heartF, heartH, foodE, foodF, foodH, air, hotbar, hotbarSel, xpBg, cross, slot
    ).size
}

object HudSkin {
    @Volatile var current: Skin = Skin()
}
