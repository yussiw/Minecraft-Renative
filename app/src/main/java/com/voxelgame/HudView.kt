package com.voxelgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

interface HudHost {
    fun pickPack()
    fun pickMod()
    fun reloadMods()
    fun pickPlayerModel()
    fun clearPlayerModel()
    fun saveExit()

    // multijogador em rede local
    fun mpHost()
    fun mpStop()
    fun mpJoin(ip: String, port: Int)
    fun mpDiscovery(on: Boolean)
    fun askText(title: String, hint: String, initial: String, ok: (String) -> Unit)
    fun getPref(key: String, def: String): String
    fun setPref(key: String, value: String)
}

enum class Screen { NONE, PAUSE, SETTINGS, CONTROLS, INVENTORY, DEAD, MODS, PLAYER, MULTI, JOIN }

/** HUD no estilo do Minecraft Java (hotbar, coracoes, fome, XP), menus e controles de toque configuraveis. */
class HudView(private val ctx: Context, private val input: Input, private val host: HudHost) : View(ctx) {

    private class UiBtn(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val label: () -> String, val enabled: () -> Boolean, val act: () -> Unit
    )

    private val dp = resources.displayMetrics.density
    private val tf: Typeface = try { Typeface.createFromAsset(ctx.assets, "default.ttf") } catch (t: Throwable) { Typeface.MONOSPACE }
    private val tp = Paint().apply { typeface = tf; isAntiAlias = false }
    private val fp = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val sp = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val ap = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val near = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val face = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val smooth = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    private var gs = 3f
    private var screen = Screen.NONE
    private val ui = ArrayList<UiBtn>()
    private var uiHover: UiBtn? = null

    // icones 9x9
    private val heartFull = makeHeart(true, false)
    private val heartHalf = makeHeart(true, true)
    private val heartEmpty = makeHeart(false, false)
    private val foodFull = makeFood(true, false)
    private val foodHalf = makeFood(true, true)
    private val foodEmpty = makeFood(false, false)
    private val bubble = makeBubble()

    // desenho de cubos isometricos
    private val mat = Matrix()
    private val srcPts = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    private val dstPts = FloatArray(6)
    private val srcR = Rect()
    private val dstF = RectF()
    private val f80: ColorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(.8f, 0f, 0f, 0f, 0f, 0f, .8f, 0f, 0f, 0f, 0f, 0f, .8f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    private val f60: ColorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(.6f, 0f, 0f, 0f, 0f, 0f, .6f, 0f, 0f, 0f, 0f, 0f, .6f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    // toque
    private val K_BTN = 1
    private val K_LOOK = 2
    private val K_DRAG = 3
    private val K_UI = 4
    private val K_SLOT = 5
    private val K_LIST = 6
    private val owner = HashMap<Int, Int>()
    private val ownerBtn = HashMap<Int, ControlButton>()
    private val ownerUi = HashMap<Int, UiBtn>()
    private var stickBtn: ControlButton? = null
    private var stickKx = 0f
    private var stickKy = 0f
    private var dpadBtn: ControlButton? = null
    private var lookId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var lookMoved = false
    private var lookFired = false
    private var lookDownAt = 0L
    private var touchAttack = false
    private val slop = 12f * dp
    private val holdMs = 300L
    private var lastTx = 0f
    private var lastTy = 0f
    private var dragOffX = 0f
    private var dragOffY = 0f

    // menu de mods
    private val ROW = 28f
    private var modSel = -1
    private var modScroll = 0f
    private var modDelArmed = false
    private var modDragY = 0f
    private var modDragScroll = 0f
    private var modDragMoved = false
    private var wrapKey = ""
    private var wrapVal: List<String> = emptyList()

    // edicao / inventario
    private var sel: ControlButton? = null
    private var curId = 0
    private var curCount = 0

    // tooltip do item
    private var lastSel = -1
    private var lastSelId = -1
    private var nameUntil = 0L

    private val longPress = Runnable {
        if (lookId != -1 && !lookMoved && Controls.screenTap) {
            touchAttack = true
            lookFired = true
            syncInput()
        }
    }

    // ------------------------------------------------------------ utilitarios

    private fun col(v: Long): Int = v.toInt()

    private fun pat(rows: Array<String>, pal: (Char, Int) -> Int): Bitmap {
        val b = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        for (y in 0 until 9) for (x in 0 until 9) {
            val ch = rows[y][x]
            if (ch != '.') b.setPixel(x, y, pal(ch, x))
        }
        return b
    }

    private fun makeHeart(full: Boolean, half: Boolean): Bitmap {
        val rows = arrayOf(
            ".OOO.OOO.", "OHHRORRRO", "OHRRRRRRO", "ORRRRRRRO",
            ".ORRRRRO.", "..ORRRO..", "...ORO...", "....O....", "........."
        )
        return pat(rows) { ch, x ->
            when (ch) {
                'O' -> col(0xFF000000)
                'H' -> if (full && (!half || x <= 4)) col(0xFFFF9595) else col(0xFF3D3D3D)
                else -> if (full && (!half || x <= 4)) col(0xFFFF1313) else col(0xFF3D3D3D)
            }
        }
    }

    private fun makeFood(full: Boolean, half: Boolean): Bitmap {
        val rows = arrayOf(
            ".....OOO.", "...OOBBBO", "..OBBBBBO", "..OBBHBBO", "..OBBBBO.",
            ".OOBBOO..", "OWWOO....", "OWOO.....", ".OO......"
        )
        return pat(rows) { ch, x ->
            val on = full && (!half || x >= 4)
            when (ch) {
                'O' -> col(0xFF2B1608)
                'B' -> if (on) col(0xFFB5651D) else col(0xFF3D3D3D)
                'H' -> if (on) col(0xFFE39A52) else col(0xFF3D3D3D)
                else -> if (on) col(0xFFEFEFEF) else col(0xFF4A4A4A)
            }
        }
    }

    private fun makeBubble(): Bitmap {
        val rows = arrayOf(
            "..OOOOO..", ".OBBBBBO.", "OBHBBBBBO", "OBBBBBBBO", "OBBBBBBBO",
            "OBBBBBBBO", ".OBBBBBO.", "..OOOOO..", "........."
        )
        return pat(rows) { ch, _ ->
            when (ch) {
                'O' -> col(0xFF2A5FA8)
                'H' -> col(0xFFFFFFFF)
                else -> col(0xFF7FB8FF)
            }
        }
    }

    private fun computeScale() {
        val auto = max(1, min((width / 320f).toInt(), (height / 240f).toInt()))
        var g = if (Controls.guiScale > 0) Controls.guiScale.toFloat() else auto.toFloat()
        g = min(g, height / 200f)
        gs = max(1f, floor(g))
    }

    private fun box(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fp.color = color
        c.drawRect(x * gs, y * gs, (x + w) * gs, (y + h) * gs, fp)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, color: Int, shadow: Boolean = true, align: Int = 0) {
        tp.textSize = 8f * gs
        val w = tp.measureText(s)
        var px = x * gs
        if (align == 1) px -= w / 2f else if (align == 2) px -= w
        val py = y * gs + 7f * gs
        if (shadow) {
            tp.color = Color.argb(Color.alpha(color), Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4)
            c.drawText(s, px + gs, py + gs, tp)
        }
        tp.color = color
        c.drawText(s, px, py, tp)
    }

    private fun textW(s: String): Float {
        tp.textSize = 8f * gs
        return tp.measureText(s) / gs
    }

    private fun icon9(c: Canvas, b: Bitmap, x: Float, y: Float) {
        dstF.set(x * gs, y * gs, (x + 9f) * gs, (y + 9f) * gs)
        c.drawBitmap(b, null, dstF, near)
    }

    /** Desenha um sprite do pack esticado em w x h pixels de GUI (qualquer resolucao, sem suavizar). */
    private fun blit(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        dstF.set(x * gs, y * gs, (x + w) * gs, (y + h) * gs)
        c.drawBitmap(b, null, dstF, near)
    }

    private fun drawFace(
        c: Canvas, atlas: Bitmap, tile: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, filter: ColorFilter?
    ) {
        val ats = atlas.width / Atlas.TILES
        dstPts[0] = x0; dstPts[1] = y0; dstPts[2] = x1; dstPts[3] = y1; dstPts[4] = x2; dstPts[5] = y2
        mat.setPolyToPoly(srcPts, 0, dstPts, 0, 3)
        face.colorFilter = filter
        c.save()
        c.concat(mat)
        val tx = (tile % Atlas.TILES) * ats
        val ty = (tile / Atlas.TILES) * ats
        srcR.set(tx, ty, tx + ats, ty + ats)
        dstF.set(0f, 0f, 1f, 1f)
        c.drawBitmap(atlas, srcR, dstF, face)
        c.restore()
    }

    /** Desenha o icone de um item/bloco (GUI px) como no inventario do Minecraft. */
    private fun itemIcon(c: Canvas, id: Int, gx: Float, gy: Float, gsize: Float) {
        val atlas = Atlas.bitmap
        val x = gx * gs
        val y = gy * gs
        val s = gsize * gs
        if (Blocks.isCube(id)) {
            val tt = Blocks.top[id]
            val ts = Blocks.side[id]
            val tx = x + s * 0.5f
            drawFace(c, atlas, ts, x, y + s * 0.25f, tx, y + s * 0.5f, x, y + s * 0.75f, f80)
            drawFace(c, atlas, ts, tx, y + s * 0.5f, x + s, y + s * 0.25f, tx, y + s, f60)
            drawFace(c, atlas, tt, x, y + s * 0.25f, tx, y, tx, y + s * 0.5f, null)
        } else {
            val ats = atlas.width / Atlas.TILES
            val tile = Blocks.iconTile(id)
            val tx = (tile % Atlas.TILES) * ats
            val ty = (tile / Atlas.TILES) * ats
            srcR.set(tx, ty, tx + ats, ty + ats)
            dstF.set(x, y, x + s, y + s)
            face.colorFilter = null
            c.drawBitmap(atlas, srcR, dstF, face)
        }
    }

    // ------------------------------------------------------------ telas

    private fun setScreen(s: Screen) {
        if (screen == Screen.INVENTORY && s != Screen.INVENTORY) returnCursor()
        if (s == Screen.JOIN && screen != Screen.JOIN) host.mpDiscovery(true)
        if (screen == Screen.JOIN && s != Screen.JOIN) host.mpDiscovery(false)
        screen = s
        input.paused = s == Screen.PAUSE || s == Screen.SETTINGS || s == Screen.MODS || s == Screen.PLAYER || s == Screen.CONTROLS ||
            s == Screen.DEAD || s == Screen.MULTI || s == Screen.JOIN
        if (s != Screen.CONTROLS) sel = null
        uiSig = uiSignature()
        releaseAll()
        buildUi()
        invalidate()
    }

    /** Ao perder o foco o jogo abre o menu de pausa, como no Java. */
    fun pauseIfPlaying() {
        if (screen == Screen.NONE) setScreen(Screen.PAUSE)
    }

    fun back(): Boolean {
        when (screen) {
            Screen.NONE -> setScreen(Screen.PAUSE)
            Screen.PAUSE -> setScreen(Screen.NONE)
            Screen.PLAYER -> setScreen(Screen.SETTINGS)
            Screen.MULTI -> setScreen(Screen.PAUSE)
            Screen.JOIN -> setScreen(Screen.MULTI)
            Screen.SETTINGS, Screen.CONTROLS -> { Controls.save(ctx); setScreen(Screen.PAUSE) }
            Screen.MODS -> leaveMods()
            Screen.INVENTORY -> setScreen(Screen.NONE)
            Screen.DEAD -> {}
        }
        return true
    }

    private fun returnCursor() {
        for (i in 0 until 9) if (craftN[i] > 0) {
            input.inv.add(craft[i], craftN[i])
            craftN[i] = 0
            craft[i] = 0
        }
        if (curCount > 0) {
            input.inv.add(curId, curCount)
            curCount = 0
            curId = 0
        }
    }

    private fun releaseAll() {
        owner.clear()
        ownerBtn.clear()
        ownerUi.clear()
        removeCallbacks(longPress)
        lookId = -1
        touchAttack = false
        stickBtn = null
        dpadBtn = null
        stickKx = 0f
        stickKy = 0f
        uiHover = null
        for (b in Controls.buttons) {
            b.pressed = false
            if (!b.toggle) b.on = false
        }
        input.moveX = 0f
        input.moveY = 0f
        input.stickFlick = false
        syncInput()
    }

    private fun <T> next(list: List<T>, cur: T): T {
        val i = list.indexOf(cur)
        return list[(i + 1) % list.size]
    }

    private fun buildUi() {
        ui.clear()
        if (width == 0) return
        val w = width / gs
        val h = height / gs
        val bw = 200f
        val bh = 20f
        val x = (w - bw) / 2f
        fun add(bx: Float, by: Float, bwid: Float, label: () -> String, enabled: () -> Boolean = { true }, act: () -> Unit) {
            ui.add(UiBtn(bx, by, bwid, bh, label, enabled, act))
        }
        when (screen) {
            Screen.PAUSE -> {
                var y = h / 2f - 80f
                val st = 22f
                add(x, y, bw, { "Voltar ao jogo" }) { setScreen(Screen.NONE) }; y += st
                add(x, y, bw, { if (Stats.creative) "Modo de jogo: Criativo" else "Modo de jogo: Sobrevivência" }) { input.modeToggle = true }; y += st
                add(x, y, bw, { "Controles..." }) { setScreen(Screen.CONTROLS) }; y += st
                add(x, y, bw, { "Opções..." }) { setScreen(Screen.SETTINGS) }; y += st
                add(x, y, bw, { "Pacote de recursos..." }) { host.pickPack() }; y += st
                add(x, y, bw, { "Mods..." }) { modSel = -1; modScroll = 0f; modDelArmed = false; setScreen(Screen.MODS) }; y += st
                add(x, y, bw, {
                    when (Net.mode) {
                        Net.Mode.OFF -> "Multijogador (Wi-Fi)..."
                        Net.Mode.HOST -> "Multijogador: hospedando"
                        Net.Mode.CLIENT -> "Multijogador: conectado"
                    }
                }) { setScreen(Screen.MULTI) }; y += st
                add(x, y, bw, { "Salvar e sair" }) { host.saveExit() }
            }
            Screen.SETTINGS -> {
                var y = h / 2f - 54f
                add(x, y, bw, { "Escala da interface: " + (if (Controls.guiScale == 0) "Auto" else Controls.guiScale.toString()) }) {
                    Controls.guiScale = next(listOf(0, 2, 3, 4, 5, 6), Controls.guiScale)
                    computeScale(); buildUi(); Controls.save(ctx)
                }; y += 24f
                add(x, y, bw, { "Sensibilidade: " + (Controls.sens * 100f).toInt() + "%" }) {
                    Controls.sens = next(listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f), Controls.sens)
                    Controls.save(ctx)
                }; y += 24f
                add(x, y, bw, { "Toque na tela: " + (if (Controls.screenTap) "SIM" else "NÃO") }) {
                    Controls.screenTap = !Controls.screenTap
                    Controls.save(ctx)
                }; y += 32f
                add(x, y, bw, { "Modelo 3D do jogador..." }) { setScreen(Screen.PLAYER) }; y += 24f
                add(x, y, bw, { "Concluído" }) { Controls.save(ctx); setScreen(Screen.PAUSE) }
            }
            Screen.PLAYER -> {
                add(x, h / 2f + 8f, bw, { "Importar modelo (.glb / .json / .zip)..." }) { host.pickPlayerModel() }
                add(x, h / 2f + 32f, bw, { "Voltar à skin padrão" }, { CustomModel.asset != null }) { host.clearPlayerModel() }
                add(x, h / 2f + 56f, bw, { "Concluído" }) { setScreen(Screen.SETTINGS) }
            }
            Screen.MODS -> {
                val g = modsGeom()
                val half = (g.dw - 4f) / 2f
                val by = g.dy + g.dh - 22f
                add(g.dx + 2f, by, half, {
                    val m = selMod()
                    if (m != null && m.enabled) "Desativar" else "Ativar"
                }, { selMod() != null }) {
                    val m = selMod()
                    if (m != null) { ModManager.setEnabled(ctx.filesDir, m, !m.enabled); modDelArmed = false }
                }
                add(g.dx + 2f + half + 4f, by, half - 2f, { if (modDelArmed) "Confirmar?" else "Excluir" }, { selMod() != null }) {
                    val m = selMod()
                    if (m != null) {
                        if (!modDelArmed) modDelArmed = true
                        else { ModManager.delete(ctx.filesDir, m); modSel = -1; modDelArmed = false }
                    }
                }
                val bb = h - 24f
                val bh2 = (g.total - 4f) / 2f
                add(g.x0, bb, bh2, { "Adicionar mod..." }) { host.pickMod() }
                add(g.x0 + bh2 + 4f, bb, bh2, { if (ModManager.needsReload()) "Aplicar e reiniciar" else "Concluído" }) { leaveMods() }
            }
            Screen.MULTI -> {
                when (Net.mode) {
                    Net.Mode.OFF -> {
                        var y = h / 2f - 46f
                        add(x, y, bw, { "Hospedar minha partida" }) { host.mpHost(); buildUi() }; y += 24f
                        add(x, y, bw, { "Procurar partidas na rede..." }) { setScreen(Screen.JOIN) }; y += 24f
                        add(x, y, bw, { "Entrar digitando o IP..." }) { askIp() }; y += 24f
                        add(x, y, bw, { "Seu nome: " + Net.myName }) { askName() }; y += 30f
                        add(x, y, bw, { "Concluído" }) { setScreen(Screen.PAUSE) }
                    }
                    Net.Mode.HOST -> {
                        add(x, h / 2f + 22f, bw, { "Parar de hospedar" }) { host.mpStop(); buildUi() }
                        add(x, h / 2f + 46f, bw, { "Concluído" }) { setScreen(Screen.PAUSE) }
                    }
                    Net.Mode.CLIENT -> {
                        add(x, h / 2f + 22f, bw, { "Sair da partida" }) { host.mpStop() }
                        add(x, h / 2f + 46f, bw, { "Concluído" }) { setScreen(Screen.PAUSE) }
                    }
                }
            }
            Screen.JOIN -> {
                val list = Net.hosts()
                var y = h / 2f - 70f
                for (i in 0 until min(4, list.size)) {
                    val f = list[i]
                    add(x, y, bw, { f.name + "  (" + f.host + ")" }) {
                        host.setPref("ip", f.host)
                        host.mpJoin(f.host, f.port)
                    }
                    y += 24f
                }
                add(x, h / 2f + 32f, bw, { "Digitar o IP..." }) { askIp() }
                add(x, h / 2f + 56f, bw, { "Voltar" }) { setScreen(Screen.MULTI) }
            }
            Screen.DEAD -> {
                add(x, h / 2f, bw, { "Renascer" }) { input.respawnReq = true }
                add(x, h / 2f + 24f, bw, { "Salvar e sair" }) { host.saveExit() }
            }
            Screen.CONTROLS -> {
                val y0 = max(28f, h * 0.2f)
                val cx = w / 2f
                add(cx - 139f, y0, 90f, { "+ Botão" }) {
                    val nb = ControlButton(Act.JUMP, 0.5f, 0.5f, 56f)
                    Controls.buttons.add(nb)
                    sel = nb
                    buildUi()
                }
                add(cx - 45f, y0, 90f, { "Padrão" }) { Controls.defaults(); sel = null; releaseAll(); buildUi() }
                add(cx + 49f, y0, 90f, { "Concluído" }) { Controls.save(ctx); setScreen(Screen.PAUSE) }
                val b = sel
                if (b != null) {
                    val acts = Act.values()
                    add(cx - 139f, y0 + 24f, 24f, { "<" }) {
                        b.act = acts[(b.act.ordinal + acts.size - 1) % acts.size]; b.on = false; buildUi()
                    }
                    add(cx - 111f, y0 + 24f, 222f, { b.act.label }) {
                        b.act = acts[(b.act.ordinal + 1) % acts.size]; b.on = false; buildUi()
                    }
                    add(cx + 115f, y0 + 24f, 24f, { ">" }) {
                        b.act = acts[(b.act.ordinal + 1) % acts.size]; b.on = false; buildUi()
                    }
                    add(cx - 138f, y0 + 48f, 66f, { "Tam. -" }) { b.size = max(24f, b.size - 6f) }
                    add(cx - 68f, y0 + 48f, 66f, { "Tam. +" }) { b.size = min(220f, b.size + 6f) }
                    add(cx + 2f, y0 + 48f, 66f, { "Opac. -" }) { b.alpha = max(10, b.alpha - 10) }
                    add(cx + 72f, y0 + 48f, 66f, { "Opac. +" }) { b.alpha = min(100, b.alpha + 10) }
                    add(cx - 138f, y0 + 72f, 136f, { if (b.toggle) "Modo: Alternar" else "Modo: Segurar" }, { b.act.hold }) {
                        b.toggle = !b.toggle; b.on = false
                    }
                    add(cx + 2f, y0 + 72f, 136f, { "Excluir botão" }) {
                        Controls.buttons.remove(b); sel = null; releaseAll(); buildUi()
                    }
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ multijogador

    /** Muda quando a lista de partidas achadas / o modo de rede muda, para refazer os botoes. */
    private var uiSig = ""

    private fun uiSignature(): String = when (screen) {
        Screen.JOIN -> Net.hosts().joinToString(",") { it.host + it.name }
        Screen.MULTI -> Net.mode.name
        else -> ""
    }

    private fun askIp() {
        host.askText("IP do anfitrião", "ex.: 192.168.43.1", host.getPref("ip", "")) { t ->
            val s = t.trim()
            if (s.isNotEmpty()) {
                val i = s.lastIndexOf(':')
                val ip = if (i > 0) s.substring(0, i).trim() else s
                val port = if (i > 0) (s.substring(i + 1).trim().toIntOrNull() ?: Net.PORT) else Net.PORT
                host.setPref("ip", ip)
                host.mpJoin(ip, port)
            }
        }
    }

    private fun askName() {
        host.askText("Seu nome (até 16 letras)", "Jogador", Net.myName) { t ->
            val n = t.trim().take(16)
            if (n.isNotEmpty()) {
                Net.myName = n
                host.setPref("name", n)
            }
            buildUi()
        }
    }

    private var ipsCache: List<String> = emptyList()
    private var ipsAt = 0L

    private fun localIps(): List<String> {
        val now = System.currentTimeMillis()
        if (now - ipsAt > 3000L) { ipsAt = now; ipsCache = Net.localIps() }
        return ipsCache
    }

    private fun drawMulti(c: Canvas) {
        fp.color = Color.argb(140, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val w = width / gs
        val h = height / gs
        val gray = col(0xFFC0C0C0)
        txt(c, "Multijogador (Wi-Fi)", w / 2f, h / 2f - 84f, Color.WHITE, true, 1)
        fun players(y0: Float) {
            val names = Net.playerNames()
            var y = y0
            for (i in 0 until min(names.size, 6)) {
                txt(c, (if (i == 0) "> " else "- ") + names[i], w / 2f, y, Color.WHITE, true, 1)
                y += 10f
            }
            if (names.size > 6) txt(c, "+ " + (names.size - 6) + " jogadores", w / 2f, y, gray, true, 1)
        }
        when (Net.mode) {
            Net.Mode.OFF -> {
                txt(c, "Os dois aparelhos na mesma rede Wi-Fi", w / 2f, h / 2f - 70f, gray, true, 1)
                txt(c, "(ou um ligado ao ponto de acesso do outro).", w / 2f, h / 2f - 60f, gray, true, 1)
            }
            Net.Mode.HOST -> {
                val ips = localIps()
                txt(c, "Hospedando! Peça ao outro para entrar.", w / 2f, h / 2f - 70f, Color.WHITE, true, 1)
                txt(c, (if (ips.isEmpty()) "IP: (sem Wi-Fi?)" else "IP: " + ips.joinToString(" / ")) + "  porta " + Net.PORT,
                    w / 2f, h / 2f - 58f, gray, true, 1)
                players(h / 2f - 42f)
            }
            Net.Mode.CLIENT -> {
                txt(c, Net.status.ifEmpty { "Conectando..." }, w / 2f, h / 2f - 70f, Color.WHITE, true, 1)
                players(h / 2f - 54f)
            }
        }
        drawUi(c)
    }

    private fun drawJoin(c: Canvas) {
        fp.color = Color.argb(140, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val w = width / gs
        val h = height / gs
        val gray = col(0xFFC0C0C0)
        txt(c, "Partidas na rede", w / 2f, h / 2f - 84f, Color.WHITE, true, 1)
        if (Net.hosts().isEmpty()) {
            txt(c, "Procurando...", w / 2f, h / 2f - 52f, Color.WHITE, true, 1)
            txt(c, "O outro aparelho precisa tocar em", w / 2f, h / 2f - 36f, gray, true, 1)
            txt(c, "\"Hospedar minha partida\".", w / 2f, h / 2f - 26f, gray, true, 1)
        }
        drawUi(c)
    }

    /** Nome flutuando sobre a cabeca dos outros jogadores (posicao calculada pelo renderizador). */
    private fun drawNames(c: Canvas) {
        for (r in Net.remotes.values) {
            if (!r.sv) continue
            val gx = r.sx / gs
            val gy = r.sy / gs - 10f
            val tw = textW(r.name)
            box(c, gx - tw / 2f - 2f, gy - 1f, tw + 4f, 10f, Color.argb(110, 0, 0, 0))
            txt(c, r.name, gx, gy, Color.WHITE, true, 1)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        computeScale()
        buildUi()
    }

    // ------------------------------------------------------------ entrada

    private fun syncInput() {
        var j = false
        var s = false
        var sp = false
        var fd = false
        var at = false
        var us = false
        for (b in Controls.buttons) {
            if (!b.on) continue
            when (b.act) {
                Act.JUMP -> j = true
                Act.SNEAK -> s = true
                Act.SPRINT -> sp = true
                Act.FLY_DOWN -> fd = true
                Act.ATTACK -> at = true
                Act.USE -> us = true
                else -> {}
            }
        }
        input.jump = j
        input.sneak = s
        input.sprint = sp
        input.down = fd
        input.attack = at || touchAttack
        input.useHeld = us
    }

    private fun fire(a: Act) {
        when (a) {
            Act.PICK -> input.pickReq = true
            Act.FLY_TOGGLE -> input.flyToggle = true
            Act.INVENTORY -> if (screen == Screen.NONE) setScreen(Screen.INVENTORY) else if (screen == Screen.INVENTORY) setScreen(Screen.NONE)
            Act.DROP -> input.dropReq = true
            Act.HOTBAR_PREV -> input.hotbarDelta = input.hotbarDelta - 1
            Act.HOTBAR_NEXT -> input.hotbarDelta = input.hotbarDelta + 1
            Act.PAUSE -> back()
            Act.BACK -> back()
            Act.F1 -> input.hideHud = !input.hideHud
            Act.F2 -> input.shotReq = true
            Act.F3 -> Stats.debug = !Stats.debug
            Act.F4 -> input.modeToggle = true
            Act.F5 -> input.camToggle = true
            else -> {}
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i), true)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    private fun uiAt(x: Float, y: Float): UiBtn? {
        val gx = x / gs
        val gy = y / gs
        for (b in ui) if (gx >= b.x && gx <= b.x + b.w && gy >= b.y && gy <= b.y + b.h && b.enabled()) return b
        return null
    }

    private fun hitBtn(b: ControlButton, x: Float, y: Float): Boolean {
        val s = b.size * dp
        val cx = b.fx * width
        val cy = b.fy * height
        return when (b.act) {
            Act.STICK -> hypot(x - cx, y - cy) <= s * 0.65f
            Act.DPAD -> abs(x - cx) <= s / 2f && abs(y - cy) <= s / 2f
            else -> abs(x - cx) <= s * 0.55f && abs(y - cy) <= s * 0.55f
        }
    }

    private fun hotbarHit(x: Float, y: Float): Int {
        if (input.hideHud) return -1
        val w = width / gs
        val h = height / gs
        val x0 = (w / 2f - 91f) * gs
        val y0 = (h - 22f) * gs
        if (y < y0 - 2f * gs || x < x0 || x > x0 + 182f * gs) return -1
        return (((x - x0) / gs - 1f) / 20f).toInt().coerceIn(0, 8)
    }

    private fun down(id: Int, x: Float, y: Float) {
        lastTx = x
        lastTy = y
        when (screen) {
            Screen.NONE -> gameDown(id, x, y)
            Screen.CONTROLS -> {
                val u = uiAt(x, y)
                if (u != null) { owner[id] = K_UI; ownerUi[id] = u; uiHover = u; return }
                for (b in Controls.buttons.asReversed()) {
                    if (hitBtn(b, x, y)) {
                        if (sel !== b) { sel = b; buildUi() }
                        owner[id] = K_DRAG
                        ownerBtn[id] = b
                        dragOffX = b.fx * width - x
                        dragOffY = b.fy * height - y
                        return
                    }
                }
                if (sel != null) { sel = null; buildUi() }
            }
            Screen.INVENTORY -> invDown(id, x, y)
            Screen.MODS -> {
                val u = uiAt(x, y)
                if (u != null) { owner[id] = K_UI; ownerUi[id] = u; uiHover = u; return }
                val g = modsGeom()
                val gx = x / gs
                val gy = y / gs
                if (gx >= g.lx && gx <= g.lx + g.lw && gy >= g.ly && gy <= g.ly + g.lh) {
                    owner[id] = K_LIST
                    modDragY = y
                    modDragScroll = modScroll
                    modDragMoved = false
                }
            }
            else -> {
                val u = uiAt(x, y)
                if (u != null) { owner[id] = K_UI; ownerUi[id] = u; uiHover = u }
            }
        }
    }

    private fun gameDown(id: Int, x: Float, y: Float) {
        val hb = hotbarHit(x, y)
        if (hb >= 0) { input.selected = hb; owner[id] = K_UI; return }
        for (b in Controls.buttons.asReversed()) {
            if (!hitBtn(b, x, y)) continue
            owner[id] = K_BTN
            ownerBtn[id] = b
            when (b.act) {
                Act.STICK -> { stickBtn = b; moveStick(b, x, y) }
                Act.DPAD -> { dpadBtn = b; moveDpad(b, x, y) }
                else -> {
                    b.pressed = true
                    if (b.act.hold) {
                        b.on = if (b.toggle) !b.on else true
                        syncInput()
                    } else fire(b.act)
                }
            }
            return
        }
        if (lookId == -1) {
            lookId = id
            owner[id] = K_LOOK
            lastX = x; lastY = y
            startX = x; startY = y
            lookMoved = false
            lookFired = false
            lookDownAt = System.currentTimeMillis()
            if (Controls.screenTap) postDelayed(longPress, holdMs)
        }
    }

    private fun moveStick(b: ControlButton, x: Float, y: Float) {
        val r = b.size * dp / 2f
        var dx = x - b.fx * width
        var dy = y - b.fy * height
        val d = hypot(dx, dy)
        if (d > r) { dx = dx / d * r; dy = dy / d * r }
        stickKx = dx
        stickKy = dy
        input.moveX = dx / r
        input.moveY = -dy / r
        input.stickFlick = hypot(dx, dy) >= r * 0.92f
    }

    private fun moveDpad(b: ControlButton, x: Float, y: Float) {
        val s = b.size * dp
        val dx = x - b.fx * width
        val dy = y - b.fy * height
        val thr = s * 0.15f
        input.moveX = if (dx > thr) 1f else if (dx < -thr) -1f else 0f
        input.moveY = if (dy < -thr) 1f else if (dy > thr) -1f else 0f
        input.stickFlick = false
    }

    private fun move(id: Int, x: Float, y: Float) {
        lastTx = x
        lastTy = y
        when (owner[id]) {
            K_BTN -> {
                val b = ownerBtn[id] ?: return
                if (b === stickBtn) moveStick(b, x, y) else if (b === dpadBtn) moveDpad(b, x, y)
            }
            K_LOOK -> {
                if (!lookMoved && hypot(x - startX, y - startY) > slop) {
                    lookMoved = true
                    removeCallbacks(longPress)
                }
                if (lookMoved) input.addLook((x - lastX) * 0.0085f / dp * Controls.sens, (y - lastY) * 0.0085f / dp * Controls.sens)
                lastX = x
                lastY = y
            }
            K_DRAG -> {
                val b = ownerBtn[id] ?: return
                b.fx = ((x + dragOffX) / width).coerceIn(0.02f, 0.98f)
                b.fy = ((y + dragOffY) / height).coerceIn(0.03f, 0.97f)
            }
            K_UI -> uiHover = ownerUi[id]?.let { if (uiAt(x, y) === it) it else null }
            K_LIST -> {
                if (!modDragMoved && abs(y - modDragY) > slop) modDragMoved = true
                if (modDragMoved) {
                    modScroll = modDragScroll - (y - modDragY) / gs
                    clampModScroll(modsGeom())
                }
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float, normal: Boolean) {
        when (owner[id]) {
            K_BTN -> {
                val b = ownerBtn[id]
                if (b != null) {
                    if (b === stickBtn || b === dpadBtn) {
                        stickBtn = null
                        dpadBtn = null
                        stickKx = 0f
                        stickKy = 0f
                        input.moveX = 0f
                        input.moveY = 0f
                        input.stickFlick = false
                    } else {
                        b.pressed = false
                        if (b.act.hold && !b.toggle) { b.on = false; syncInput() }
                    }
                }
            }
            K_LOOK -> {
                removeCallbacks(longPress)
                if (normal && !lookMoved && !lookFired && Controls.screenTap &&
                    System.currentTimeMillis() - lookDownAt < holdMs
                ) input.useReq = true
                if (touchAttack) { touchAttack = false; syncInput() }
                lookId = -1
            }
            K_UI -> {
                val u = ownerUi[id]
                uiHover = null
                if (normal && u != null && uiAt(x, y) === u) u.act()
            }
            K_LIST -> {
                if (normal && !modDragMoved) modTap(y)
            }
        }
        owner.remove(id)
        ownerBtn.remove(id)
        ownerUi.remove(id)
    }

    // ------------------------------------------------------------ inventario

    private var invScroll = 0

    // fabricacao 3x3 (so sobrevivencia): grade, receita atual e cursor compartilhado com o inventario
    private val CRAFT_TOP = 75f
    private val craft = IntArray(9)
    private val craftN = IntArray(9)

    private fun craftCells(): IntArray = IntArray(9) { if (craftN[it] > 0) craft[it] else 0 }

    private fun craftTap(i: Int) {
        if (curCount == 0) {
            if (craftN[i] > 0) { curId = craft[i]; curCount = craftN[i]; craftN[i] = 0; craft[i] = 0 }
        } else if (craftN[i] == 0) {
            craft[i] = curId; craftN[i] = 1
            curCount--
            if (curCount == 0) curId = 0
        } else if (craft[i] == curId && craftN[i] < 64) {
            craftN[i]++
            curCount--
            if (curCount == 0) curId = 0
        }
    }

    private fun takeResult() {
        val r = Recipes.find(craftCells()) ?: return
        if (curCount > 0 && (curId != r.result || curCount + r.count > 64)) return
        for (i in 0 until 9) if (craftN[i] > 0) {
            craftN[i]--
            if (craftN[i] == 0) craft[i] = 0
        }
        curId = r.result
        curCount += r.count
    }

    private fun craftDown(lx: Float, ly: Float) {
        val cc = floor((lx - 22f) / 18f).toInt()
        val rr = floor((ly - 17f) / 18f).toInt()
        if (lx >= 22f && ly >= 17f && cc in 0..2 && rr in 0..2) { craftTap(rr * 3 + cc); return }
        if (lx in 102f..120f && ly in 35f..53f) takeResult()
    }

    private fun creativeRows() = (Blocks.creativeItems.size + 8) / 9
    private fun visRows() = min(creativeRows(), 5)

    private fun clampScroll() {
        invScroll = invScroll.coerceIn(0, max(0, creativeRows() - visRows()))
    }

    private fun invGeom(): FloatArray {
        val w = width / gs
        val h = height / gs
        val pw = 176f
        val ph = if (Stats.creative) {
            val rows = visRows()
            20f + rows * 18f + 6f + 18f + 8f
        } else 102f + CRAFT_TOP
        return floatArrayOf((w - pw) / 2f, (h - ph) / 2f, pw, ph)
    }

    private fun hotbarY(): Float = if (Stats.creative) {
        20f + visRows() * 18f + 6f
    } else 76f + CRAFT_TOP

    private fun invDown(id: Int, x: Float, y: Float) {
        val g = invGeom()
        val gx = x / gs
        val gy = y / gs
        if (gx < g[0] || gx > g[0] + g[2] || gy < g[1] || gy > g[1] + g[3]) {
            setScreen(Screen.NONE)
            return
        }
        owner[id] = K_SLOT
        val lx = gx - g[0] - 8f
        val ly = gy - g[1]
        clampScroll()
        if (Stats.creative && creativeRows() > visRows() && ly in 4f..17f) {
            if (lx in 126f..140f) { invScroll = max(0, invScroll - 1); return }
            if (lx in 144f..158f) { invScroll = min(creativeRows() - visRows(), invScroll + 1); return }
        }
        if (!Stats.creative && ly < CRAFT_TOP) { craftDown(lx, ly); return }
        val col = floor(lx / 18f).toInt()
        if (lx < 0f || col !in 0..8) return
        val hy = hotbarY()
        // hotbar
        if (ly >= hy && ly < hy + 18f) {
            if (Stats.creative) input.selected = col else invTap(col)
            return
        }
        if (Stats.creative) {
            val row = floor((ly - 20f) / 18f).toInt()
            val idx = (invScroll + row) * 9 + col
            if (ly >= 20f && row in 0 until visRows() && idx < Blocks.creativeItems.size) {
                input.inv.set(input.selected.coerceIn(0, 8), Blocks.creativeItems[idx], 64)
            }
        } else {
            val row = floor((ly - 18f - CRAFT_TOP) / 18f).toInt()
            if (ly >= 18f + CRAFT_TOP && row in 0..2) invTap(9 + row * 9 + col)
        }
    }

    private fun invTap(slot: Int) {
        val inv = input.inv
        if (curCount == 0) {
            if (inv.count[slot] > 0) {
                curId = inv.id[slot]
                curCount = inv.count[slot]
                inv.set(slot, 0, 0)
            }
        } else if (inv.count[slot] == 0) {
            inv.set(slot, curId, curCount)
            curCount = 0
        } else if (inv.id[slot] == curId) {
            val m = min(64 - inv.count[slot], curCount)
            inv.count[slot] += m
            curCount -= m
        } else {
            val oi = inv.id[slot]
            val oc = inv.count[slot]
            inv.set(slot, curId, curCount)
            curId = oi
            curCount = oc
        }
    }

    // ------------------------------------------------------------ desenho

    override fun onDraw(c: Canvas) {
        if (Stats.dead && screen != Screen.DEAD) setScreen(Screen.DEAD)
        if (!Stats.dead && screen == Screen.DEAD) setScreen(Screen.NONE)
        val w = width.toFloat()
        val h = height.toFloat()

        if (Stats.underwater) { fp.color = Color.argb(85, 10, 50, 150); c.drawRect(0f, 0f, w, h, fp) }
        if (Stats.flash > 0f) { fp.color = Color.argb((Stats.flash * 160f).toInt().coerceIn(0, 120), 200, 0, 0); c.drawRect(0f, 0f, w, h, fp) }

        if (screen != Screen.INVENTORY && screen != Screen.DEAD && !input.hideHud) drawHud(c)
        if (Stats.debug && screen != Screen.INVENTORY) drawDebug(c)
        if (screen == Screen.NONE || screen == Screen.CONTROLS) drawControls(c)
        if (screen == Screen.NONE && Net.mode != Net.Mode.OFF && !input.hideHud) drawNames(c)

        val sig = uiSignature()
        if (sig != uiSig) { uiSig = sig; buildUi() }

        when (screen) {
            Screen.MULTI -> drawMulti(c)
            Screen.JOIN -> drawJoin(c)
            Screen.PAUSE -> drawMenu(c, "Menu do jogo", -12f)
            Screen.MODS -> drawMods(c)
            Screen.PLAYER -> drawPlayerModel(c)
            Screen.SETTINGS -> drawMenu(c, "Opções", 0f)
            Screen.CONTROLS -> drawEditor(c)
            Screen.INVENTORY -> drawInventory(c)
            Screen.DEAD -> {
                fp.color = Color.argb(150, 120, 0, 0)
                c.drawRect(0f, 0f, w, h, fp)
                txt(c, "Você morreu!", width / gs / 2f, height / gs / 2f - 40f, Color.WHITE, true, 1)
                drawUi(c)
            }
            Screen.NONE -> {}
        }
        // com outros jogadores na tela os nomes precisam acompanhar a camera: redesenha ~30x por segundo
        postInvalidateDelayed(if (screen == Screen.NONE && Net.remotes.isNotEmpty()) 33L else 100L)
    }

    private fun drawHud(c: Canvas) {
        val w = width / gs
        val h = height / gs
        val cx = w / 2f
        val x0 = cx - 91f
        val y0 = h - 22f
        val inv = input.inv
        val sel = input.selected.coerceIn(0, 8)

        val sk = HudSkin.current

        // hotbar
        if (sk.hotbar != null) blit(c, sk.hotbar, x0, y0, 182f, 22f)
        else box(c, x0, y0, 182f, 22f, Color.argb(165, 20, 20, 20))
        for (i in 0 until 9) {
            val sx = x0 + 1f + 20f * i
            val sy = y0 + 1f
            if (sk.hotbar == null) {
                box(c, sx, sy, 20f, 20f, Color.argb(110, 80, 80, 80))
                box(c, sx, sy, 20f, 1f, Color.argb(150, 30, 30, 30))
                box(c, sx, sy, 1f, 20f, Color.argb(150, 30, 30, 30))
                box(c, sx, sy + 19f, 20f, 1f, Color.argb(150, 130, 130, 130))
                box(c, sx + 19f, sy, 1f, 20f, Color.argb(150, 130, 130, 130))
            }
            val cnt = inv.count[i]
            if (cnt > 0) {
                itemIcon(c, inv.id[i], sx + 2f, sy + 2f, 16f)
                if (cnt > 1) {
                    val s = cnt.toString()
                    txt(c, s, sx + 19f - textW(s), sy + 11f, Color.WHITE)
                }
            }
        }
        val rx = x0 - 1f + 20f * sel
        val ry = y0 - 1f
        if (sk.hotbarSel != null) {
            blit(c, sk.hotbarSel, rx, ry, 24f, 24f)
        } else {
            val k = Color.BLACK
            box(c, rx, ry, 24f, 1f, k); box(c, rx, ry + 23f, 24f, 1f, k)
            box(c, rx, ry, 1f, 24f, k); box(c, rx + 23f, ry, 1f, 24f, k)
            val wt = Color.WHITE
            box(c, rx + 1f, ry + 1f, 22f, 2f, wt); box(c, rx + 1f, ry + 21f, 22f, 2f, wt)
            box(c, rx + 1f, ry + 1f, 2f, 22f, wt); box(c, rx + 21f, ry + 1f, 2f, 22f, wt)
        }

        // nome do item selecionado
        val sid = if (inv.count[sel] > 0) inv.id[sel] else 0
        val now = System.currentTimeMillis()
        if (sel != lastSel || sid != lastSelId) {
            lastSel = sel
            lastSelId = sid
            nameUntil = now + 2000L
        }
        if (sid != 0 && now < nameUntil) {
            val a = min(1f, (nameUntil - now) / 500f)
            val nm = Blocks.name(sid)
            txt(c, nm, cx, h - (if (Stats.creative) 40f else 59f), Color.argb((a * 255f).toInt(), 255, 255, 255), true, 1)
        }

        // vida, fome, XP e ar (so sobrevivencia)
        if (!Stats.creative) {
            val hp = ceil(Stats.health).toInt()
            for (i in 0 until 10) {
                val hx = x0 + 8f * i
                val hy = h - 39f
                icon9(c, sk.heartC ?: heartEmpty, hx, hy)
                val v = hp - 2 * i
                if (v >= 2) icon9(c, sk.heartF ?: heartFull, hx, hy) else if (v == 1) icon9(c, sk.heartH ?: heartHalf, hx, hy)
                val fx = x0 + 182f - 9f - 8f * i
                icon9(c, sk.foodE ?: foodEmpty, fx, hy)
                val fv = Stats.food - 2 * i
                if (fv >= 2) icon9(c, sk.foodF ?: foodFull, fx, hy) else if (fv == 1) icon9(c, sk.foodH ?: foodHalf, fx, hy)
            }
            if (sk.xpBg != null) {
                blit(c, sk.xpBg, x0, h - 29f, 182f, 5f)
            } else {
                box(c, x0, h - 29f, 182f, 5f, col(0xFF1E1E1E))
                box(c, x0 + 1f, h - 28f, 180f, 3f, col(0xFF4D4D4D))
            }
            if (Stats.underwater || Stats.air < 10) {
                for (i in 0 until Stats.air) icon9(c, sk.air ?: bubble, x0 + 182f - 9f - 8f * i, h - 49f)
            }
        }

        // mensagem de blocos de mod
        if (Stats.messageUntil > now) txt(c, Stats.message, cx, h - 72f, Color.WHITE, true, 1)

        // mira (some em terceira pessoa, como no Java)
        if (Stats.camMode != 0) {
            // sem mira
        } else if (sk.cross != null) {
            blit(c, sk.cross, cx - 7.5f, h / 2f - 7.5f, 15f, 15f)
        } else {
            box(c, cx - 7f, h / 2f - 0.5f, 15f, 1f, Color.argb(220, 255, 255, 255))
            box(c, cx - 0.5f, h / 2f - 7f, 1f, 15f, Color.argb(220, 255, 255, 255))
        }
    }

    private fun drawDebug(c: Canvas) {
        val lines = arrayOf(
            "Blocos 0.3 (estilo Java) - OpenGL ES 3.0",
            "${Stats.fps.toInt()} fps | chunks ${Stats.chunks} | dist ${Stats.radius} | quads ${Stats.quads}",
            String.format("XYZ: %.3f / %.3f / %.3f", Stats.fx, Stats.fy, Stats.fz),
            "Bloco: ${Stats.x} ${Stats.y} ${Stats.z}  Chunk: ${Stats.x shr 4} ${Stats.z shr 4}",
            String.format("Direção: %s (%.1f / %.1f)", Stats.facing, Math.toDegrees(Stats.yaw), Math.toDegrees(Stats.pitch)),
            "Hora: ${Stats.clock} | Modo: ${if (Stats.creative) "Criativo" else "Sobrevivência"}"
        )
        for (i in lines.indices) {
            val y = 2f + 10f * i
            box(c, 1f, y - 1f, textW(lines[i]) + 3f, 10f, Color.argb(144, 80, 80, 80))
            txt(c, lines[i], 2f, y, col(0xFFE0E0E0), true)
        }
    }

    private fun drawControls(c: Canvas) {
        val editing = screen == Screen.CONTROLS
        val line = max(2f, gs)
        for (b in Controls.buttons) {
            val s = b.size * dp
            val cx = b.fx * width
            val cy = b.fy * height
            val a = (b.alpha / 100f * 255f).toInt().coerceIn(20, 255)
            val lit = b.on || b.pressed
            when (b.act) {
                Act.STICK -> {
                    ap.color = Color.argb(a / 2, 255, 255, 255)
                    c.drawCircle(cx, cy, s / 2f, ap)
                    ap.color = Color.argb(min(255, a + 90), 255, 255, 255)
                    c.drawCircle(cx + stickKx, cy + stickKy, s * 0.22f, ap)
                }
                Act.DPAD -> {
                    val arm = s / 3f
                    val dirs = arrayOf(floatArrayOf(0f, -1f), floatArrayOf(0f, 1f), floatArrayOf(-1f, 0f), floatArrayOf(1f, 0f))
                    for (d in dirs) {
                        val ax = cx + d[0] * arm
                        val ay = cy + d[1] * arm
                        val active = (d[1] < 0 && input.moveY > 0.3f) || (d[1] > 0 && input.moveY < -0.3f) ||
                            (d[0] < 0 && input.moveX < -0.3f) || (d[0] > 0 && input.moveX > 0.3f)
                        fp.color = Color.argb(if (active) min(255, a + 100) else a, if (active) 150 else 40, if (active) 150 else 40, if (active) 150 else 40)
                        c.drawRect(ax - arm / 2f + line, ay - arm / 2f + line, ax + arm / 2f - line, ay + arm / 2f - line, fp)
                        arrow(c, ax, ay, arm * 0.28f, d[0], d[1], Color.argb(min(255, a + 140), 255, 255, 255))
                    }
                    fp.color = Color.argb(a / 2, 40, 40, 40)
                    c.drawRect(cx - arm / 2f + line, cy - arm / 2f + line, cx + arm / 2f - line, cy + arm / 2f - line, fp)
                }
                else -> {
                    val r = s / 2f
                    fp.color = Color.argb(if (lit) min(255, a + 110) else a, if (lit) 140 else 30, if (lit) 140 else 30, if (lit) 140 else 30)
                    c.drawRect(cx - r, cy - r, cx + r, cy + r, fp)
                    fp.color = Color.argb(min(255, a + 120), 210, 210, 210)
                    c.drawRect(cx - r, cy - r, cx + r, cy - r + line, fp)
                    c.drawRect(cx - r, cy + r - line, cx + r, cy + r, fp)
                    c.drawRect(cx - r, cy - r, cx - r + line, cy + r, fp)
                    c.drawRect(cx + r - line, cy - r, cx + r, cy + r, fp)
                    val label = b.act.short
                    tp.textSize = s * 0.3f
                    val tw = tp.measureText(label)
                    if (tw > s * 0.82f) tp.textSize = tp.textSize * s * 0.82f / tw
                    val w2 = tp.measureText(label)
                    tp.color = Color.argb(min(255, a + 150), 255, 255, 255)
                    c.drawText(label, cx - w2 / 2f, cy + tp.textSize * 0.35f, tp)
                    if (b.toggle && b.act.hold) {
                        fp.color = if (b.on) Color.argb(255, 90, 255, 90) else Color.argb(160, 120, 120, 120)
                        c.drawRect(cx + r - line * 4f, cy - r + line * 2f, cx + r - line * 2f, cy - r + line * 4f, fp)
                    }
                }
            }
            if (editing) {
                sp.strokeWidth = if (b === sel) line * 1.5f else line * 0.6f
                sp.color = if (b === sel) Color.YELLOW else Color.argb(170, 255, 255, 255)
                val r = when (b.act) { Act.STICK -> s * 0.5f; else -> s * 0.5f }
                c.drawRect(cx - r, cy - r, cx + r, cy + r, sp)
            }
        }
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, color: Int) {
        val p = Path()
        if (dy < 0) { p.moveTo(cx, cy - r); p.lineTo(cx - r, cy + r); p.lineTo(cx + r, cy + r) }
        else if (dy > 0) { p.moveTo(cx, cy + r); p.lineTo(cx - r, cy - r); p.lineTo(cx + r, cy - r) }
        else if (dx < 0) { p.moveTo(cx - r, cy); p.lineTo(cx + r, cy - r); p.lineTo(cx + r, cy + r) }
        else { p.moveTo(cx + r, cy); p.lineTo(cx - r, cy - r); p.lineTo(cx - r, cy + r) }
        p.close()
        ap.color = color
        c.drawPath(p, ap)
    }

    private fun drawUi(c: Canvas) {
        for (b in ui) {
            val en = b.enabled()
            val hov = b === uiHover
            box(c, b.x, b.y, b.w, b.h, Color.BLACK)
            box(c, b.x + 1f, b.y + 1f, b.w - 2f, b.h - 2f, if (!en) col(0xFF3C3C3C) else if (hov) col(0xFF7B86C4) else col(0xFF707070))
            if (en) {
                box(c, b.x + 1f, b.y + 1f, b.w - 2f, 1f, col(0xFFA8A8A8))
                box(c, b.x + 1f, b.y + 1f, 1f, b.h - 2f, col(0xFFA8A8A8))
                box(c, b.x + 1f, b.y + b.h - 3f, b.w - 2f, 2f, col(0xFF3C3C3C))
            }
            txt(c, b.label(), b.x + b.w / 2f, b.y + (b.h - 8f) / 2f, if (en) Color.WHITE else col(0xFFA0A0A0), true, 1)
        }
    }

    private fun drawMenu(c: Canvas, title: String, dy: Float) {
        fp.color = Color.argb(140, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        txt(c, title, width / gs / 2f, height / gs / 2f - 84f + dy, Color.WHITE, true, 1)
        drawUi(c)
    }

    private fun drawPlayerModel(c: Canvas) {
        fp.color = Color.argb(140, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val w = width / gs
        val h = height / gs
        val gray = col(0xFFC0C0C0)
        txt(c, "Modelo 3D do jogador", w / 2f, h / 2f - 62f, Color.WHITE, true, 1)
        txt(c, "Escolha um .glb (e, se quiser, o model.json junto)", w / 2f, h / 2f - 50f, gray, true, 1)
        txt(c, "ou um .zip com os dois. Aparece na 3ª pessoa.", w / 2f, h / 2f - 40f, gray, true, 1)
        val a = CustomModel.asset
        txt(c, if (a == null) "Atual: skin padrão" else "Atual: modelo 3D (" + a.glb.clips.size + " animações)", w / 2f, h / 2f - 22f, Color.WHITE, true, 1)
        drawUi(c)
    }

    // ------------------------------------------------------------ menu de mods

    private class MG(
        val x0: Float, val total: Float,
        val lx: Float, val ly: Float, val lw: Float, val lh: Float,
        val dx: Float, val dy: Float, val dw: Float, val dh: Float
    )

    private fun modsGeom(): MG {
        val w = width / gs
        val h = height / gs
        val total = min(w - 12f, 470f)
        val x0 = (w - total) / 2f
        val ly = 30f
        val lh = max(60f, h - 60f)
        val lw = floor(total * 0.42f)
        return MG(x0, total, x0, ly, lw, lh, x0 + lw + 6f, ly, total - lw - 6f, lh)
    }

    private fun selMod(): ModManager.ModInfo? = ModManager.mods.getOrNull(modSel)

    private fun clampModScroll(g: MG) {
        val maxS = max(0f, ModManager.mods.size * ROW + 2f - g.lh)
        modScroll = modScroll.coerceIn(0f, maxS)
    }

    private fun modTap(y: Float) {
        val g = modsGeom()
        val rel = y / gs - g.ly - 1f + modScroll
        val idx = (rel / ROW).toInt()
        if (rel >= 0f && idx in ModManager.mods.indices) {
            modSel = idx
            modDelArmed = false
        }
    }

    private fun leaveMods() {
        modSel = -1
        modScroll = 0f
        modDelArmed = false
        if (ModManager.needsReload()) host.reloadMods() else setScreen(Screen.PAUSE)
    }

    private fun fit(s: String, maxW: Float): String {
        if (textW(s) <= maxW) return s
        var n = s.length
        while (n > 1 && textW(s.substring(0, n) + "...") > maxW) n--
        return s.substring(0, n) + "..."
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val key = maxW.toString() + "|" + s
        if (key == wrapKey) return wrapVal
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                if (word.isEmpty()) continue
                var wd = word
                while (textW(wd) > maxW && wd.length > 1) {
                    var n = wd.length
                    while (n > 1 && textW(wd.substring(0, n)) > maxW) n--
                    if (line.isNotEmpty()) { out.add(line); line = "" }
                    out.add(wd.substring(0, n))
                    wd = wd.substring(n)
                }
                val t = if (line.isEmpty()) wd else "$line $wd"
                if (textW(t) <= maxW) line = t else { out.add(line); line = wd }
            }
            out.add(line)
        }
        wrapKey = key
        wrapVal = out
        return out
    }

    private fun drawModIcon(c: Canvas, m: ModManager.ModInfo, x: Float, y: Float, s: Float) {
        box(c, x - 1f, y - 1f, s + 2f, s + 2f, Color.BLACK)
        val b = m.icon
        if (b != null) {
            val p = if (b.width >= 48) smooth else near
            dstF.set(x * gs, y * gs, (x + s) * gs, (y + s) * gs)
            p.alpha = if (m.enabled) 255 else 110
            c.drawBitmap(b, null, dstF, p)
            p.alpha = 255
        } else {
            val hue = (abs(m.id.hashCode()) % 360).toFloat()
            fp.color = Color.HSVToColor(floatArrayOf(hue, 0.45f, if (m.enabled) 0.6f else 0.3f))
            c.drawRect(x * gs, y * gs, (x + s) * gs, (y + s) * gs, fp)
            val ch = m.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            txt(c, ch, x + s / 2f, y + s / 2f - 4f, Color.WHITE, true, 1)
        }
    }

    private fun statusColor(m: ModManager.ModInfo): Int = when {
        m.currentError != null && m.enabled -> col(0xFFE04040)
        m.pending -> col(0xFFE0C040)
        m.enabled -> col(0xFF50D050)
        else -> col(0xFF707070)
    }

    private fun drawMods(c: Canvas) {
        fp.color = Color.argb(175, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val w = width / gs
        val g = modsGeom()
        val list = ModManager.mods
        val gray = col(0xFFC0C0C0)
        val dim = col(0xFF8A8A8A)
        txt(c, "Mods", w / 2f, 6f, Color.WHITE, true, 1)
        txt(c, list.size.toString() + " instalado(s) - " + list.count { it.enabled } + " ativo(s)", w / 2f, 17f, gray, true, 1)

        // ---- lista
        box(c, g.lx, g.ly, g.lw, g.lh, Color.BLACK)
        box(c, g.lx + 1f, g.ly + 1f, g.lw - 2f, g.lh - 2f, col(0xFF1E1E1E))
        if (list.isEmpty()) {
            val cx = g.lx + g.lw / 2f
            txt(c, "Nenhum mod", cx, g.ly + 20f, Color.WHITE, true, 1)
            txt(c, "Toque em", cx, g.ly + 36f, gray, true, 1)
            txt(c, "Adicionar mod...", cx, g.ly + 46f, gray, true, 1)
        } else {
            clampModScroll(g)
            val needBar = list.size * ROW + 2f > g.lh
            val rowW = g.lw - 2f - (if (needBar) 4f else 0f)
            c.save()
            c.clipRect((g.lx + 1f) * gs, (g.ly + 1f) * gs, (g.lx + g.lw - 1f) * gs, (g.ly + g.lh - 1f) * gs)
            for (i in list.indices) {
                val ry = g.ly + 1f + i * ROW - modScroll
                if (ry + ROW < g.ly || ry > g.ly + g.lh) continue
                val m = list[i]
                box(c, g.lx + 1f, ry, rowW, ROW - 1f, if (i == modSel) col(0xFF4A5290) else col(0xFF2E2E2E))
                drawModIcon(c, m, g.lx + 5f, ry + 2f, 22f)
                val tx = g.lx + 32f
                val maxW = rowW - 31f - 10f
                txt(c, fit(m.name, maxW), tx, ry + 4f, if (m.enabled) Color.WHITE else dim, true)
                val sub = if (m.version.isNotEmpty()) "v" + m.version else if (m.langs.isNotEmpty()) m.langs.joinToString("/") else "dados"
                txt(c, fit(sub, maxW), tx, ry + 15f, dim, false)
                box(c, g.lx + rowW - 8f, ry + ROW / 2f - 4f, 6f, 6f, statusColor(m))
            }
            c.restore()
            if (needBar) {
                val total = list.size * ROW + 2f
                val bh = max(10f, (g.lh - 2f) * (g.lh / total))
                val by = g.ly + 1f + (g.lh - 2f - bh) * (modScroll / max(1f, total - g.lh))
                box(c, g.lx + g.lw - 4f, by, 3f, bh, col(0xFF808080))
            }
        }

        // ---- detalhes
        box(c, g.dx, g.dy, g.dw, g.dh, Color.BLACK)
        box(c, g.dx + 1f, g.dy + 1f, g.dw - 2f, g.dh - 2f, col(0xFF1E1E1E))
        val m = selMod()
        if (m == null) {
            val cx = g.dx + g.dw / 2f
            txt(c, "Toque num mod para ver os detalhes", cx, g.dy + 20f, gray, true, 1)
            txt(c, "Aceita .zip/.jar (mod.json + Lua/JS)", cx, g.dy + 40f, dim, false, 1)
            txt(c, "ou um arquivo .lua / .js solto.", cx, g.dy + 50f, dim, false, 1)
            drawUi(c)
            return
        }
        val ix = g.dx + 5f
        drawModIcon(c, m, ix, g.dy + 5f, 32f)
        val tx = g.dx + 43f
        val maxW = g.dw - 48f
        txt(c, fit(m.name, maxW), tx, g.dy + 5f, Color.WHITE, true)
        val who = (if (m.version.isNotEmpty()) "v" + m.version + "  " else "") + (if (m.author.isNotEmpty()) "por " + m.author else "")
        txt(c, fit(who.ifEmpty { "(sem versão/autor)" }, maxW), tx, g.dy + 16f, gray, false)
        val st = if (m.enabled) "Ativo" else "Desativado"
        txt(c, st, tx, g.dy + 27f, if (m.enabled) col(0xFF50D050) else dim, true)
        if (m.pending) txt(c, "(vale ao reiniciar)", tx + textW(st) + 4f, g.dy + 27f, col(0xFFE0C040), false)

        // bloco de informacoes, ancorado acima dos botoes
        val info = ArrayList<Pair<String, Int>>()
        val parts = ArrayList<String>()
        if (m.blocks > 0) parts.add(m.blocks.toString() + " bloco(s)")
        if (m.items > 0) parts.add(m.items.toString() + " item(ns)")
        if (m.recipes > 0) parts.add(m.recipes.toString() + " receita(s)")
        info.add(Pair(if (parts.isEmpty()) "Sem blocos/itens" else parts.joinToString(", "), gray))
        if (m.langs.isNotEmpty()) info.add(Pair("Scripts: " + m.langs.joinToString(", "), gray))
        if (m.hasJavaCode) info.add(Pair("Código Java não é executado", col(0xFFE0A040)))
        if (m.skipped > 0) info.add(Pair(m.skipped.toString() + " ignorado(s)", col(0xFFE0A040)))
        val err = m.currentError
        val errLines = if (err != null) wrap("Erro: " + err, g.dw - 10f).take(3) else emptyList()
        val infoH = info.size * 9f + errLines.size * 9f + (if (info.isNotEmpty()) 3f else 0f)
        val bottom = g.dy + g.dh - 26f
        val infoTop = bottom - infoH
        var iy = infoTop + 3f
        for (p in info) { txt(c, fit(p.first, g.dw - 10f), ix, iy, p.second, false); iy += 9f }
        for (l in errLines) { txt(c, l, ix, iy, col(0xFFFF6060), false); iy += 9f }

        // descricao com quebra de linha, no espaco que sobrou
        val dTop = g.dy + 42f
        val fitLines = max(0, ((infoTop - dTop) / 9f).toInt())
        val desc = wrap(m.description.ifEmpty { "Sem descrição." }, g.dw - 10f)
        var dy = dTop
        for (i in 0 until min(desc.size, fitLines)) {
            var line = desc[i]
            if (i == fitLines - 1 && desc.size > fitLines) line = fit(line + "...", g.dw - 10f)
            txt(c, line, ix, dy, col(0xFFE0E0E0), false)
            dy += 9f
        }
        drawUi(c)
    }

    private fun drawEditor(c: Canvas) {
        fp.color = Color.argb(70, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val w = width / gs
        val y0 = max(28f, height / gs * 0.2f)
        txt(c, "Editar controles", w / 2f, y0 - 24f, Color.WHITE, true, 1)
        txt(c, "Arraste os botões. Toque num botão para editá-lo.", w / 2f, y0 - 12f, col(0xFFC0C0C0), true, 1)
        drawUi(c)
        val b = sel
        if (b != null) txt(c, "Tamanho ${b.size.toInt()}  |  Opacidade ${b.alpha}%", w / 2f, y0 + 96f, Color.WHITE, true, 1)
    }

    private fun slot(c: Canvas, x: Float, y: Float) {
        val sb = HudSkin.current.slot
        if (sb != null) { blit(c, sb, x, y, 18f, 18f); return }
        box(c, x, y, 18f, 18f, col(0xFF373737))
        box(c, x + 1f, y + 1f, 17f, 17f, col(0xFFFFFFFF))
        box(c, x + 1f, y + 1f, 16f, 16f, col(0xFF8B8B8B))
    }

    private fun drawInventory(c: Canvas) {
        fp.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fp)
        val g = invGeom()
        val px = g[0]
        val py = g[1]
        box(c, px, py, g[2], g[3], Color.BLACK)
        box(c, px + 1f, py + 1f, g[2] - 2f, g[3] - 2f, col(0xFFC6C6C6))
        box(c, px + 1f, py + 1f, g[2] - 3f, 1f, Color.WHITE)
        box(c, px + 1f, py + 1f, 1f, g[3] - 3f, Color.WHITE)
        box(c, px + 2f, py + g[3] - 2f, g[2] - 3f, 1f, col(0xFF555555))
        box(c, px + g[2] - 2f, py + 2f, 1f, g[3] - 3f, col(0xFF555555))
        val dark = col(0xFF404040)
        val inv = input.inv
        val hy = py + hotbarY()
        if (Stats.creative) {
            txt(c, "Itens", px + 8f, py + 7f, dark, false)
            val items = Blocks.creativeItems
            clampScroll()
            for (r in 0 until visRows()) for (cc in 0 until 9) {
                val i = (invScroll + r) * 9 + cc
                if (i >= items.size) break
                val sx = px + 8f + 18f * cc
                val sy = py + 20f + 18f * r
                slot(c, sx, sy)
                itemIcon(c, items[i], sx + 1f, sy + 1f, 16f)
            }
            if (creativeRows() > visRows()) {
                box(c, px + 134f, py + 5f, 14f, 12f, dark)
                box(c, px + 152f, py + 5f, 14f, 12f, dark)
                txt(c, "^", px + 141f, py + 6f, Color.WHITE, false, 1)
                txt(c, "v", px + 159f, py + 6f, Color.WHITE, false, 1)
            }
        } else {
            txt(c, "Fabricação", px + 8f, py + 6f, dark, false)
            val cells = craftCells()
            for (r in 0 until 3) for (cc in 0 until 3) {
                val sx = px + 30f + 18f * cc
                val sy = py + 17f + 18f * r
                slot(c, sx, sy)
                if (craftN[r * 3 + cc] > 0) drawStack(c, craft[r * 3 + cc], craftN[r * 3 + cc], sx, sy)
            }
            txt(c, ">", px + 94f, py + 40f, dark, false)
            slot(c, px + 110f, py + 35f)
            val rec = Recipes.find(cells)
            if (rec != null) drawStack(c, rec.result, rec.count, px + 110f, py + 35f)
            txt(c, "Inventário", px + 8f, py + 7f + CRAFT_TOP, dark, false)
            for (r in 0 until 3) for (cc in 0 until 9) {
                val sx = px + 8f + 18f * cc
                val sy = py + 18f + CRAFT_TOP + 18f * r
                slot(c, sx, sy)
                val s = 9 + r * 9 + cc
                if (inv.count[s] > 0) drawStack(c, inv.id[s], inv.count[s], sx, sy)
            }
        }
        for (i in 0 until 9) {
            val sx = px + 8f + 18f * i
            slot(c, sx, hy)
            if (inv.count[i] > 0) drawStack(c, inv.id[i], inv.count[i], sx, hy)
            if (Stats.creative && i == input.selected) {
                box(c, sx, hy, 18f, 1f, Color.WHITE); box(c, sx, hy + 17f, 18f, 1f, Color.WHITE)
                box(c, sx, hy, 1f, 18f, Color.WHITE); box(c, sx + 17f, hy, 1f, 18f, Color.WHITE)
            }
        }
        if (curCount > 0) {
            itemIcon(c, curId, lastTx / gs - 8f, lastTy / gs - 8f, 16f)
            if (curCount > 1) {
                val s = curCount.toString()
                txt(c, s, lastTx / gs + 8f - textW(s), lastTy / gs + 1f, Color.WHITE)
            }
        }
    }

    private fun drawStack(c: Canvas, id: Int, count: Int, sx: Float, sy: Float) {
        itemIcon(c, id, sx + 1f, sy + 1f, 16f)
        if (count > 1) {
            val s = count.toString()
            txt(c, s, sx + 17f - textW(s), sy + 9f, Color.WHITE)
        }
    }
}
