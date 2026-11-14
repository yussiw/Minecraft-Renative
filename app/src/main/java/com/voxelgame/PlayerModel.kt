package com.voxelgame

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Skin do jogador no layout 64x64 do Minecraft Java (modelo "wide").
 * Por padrao e gerada por codigo (estilo Steve); um resource pack pode trocar
 * (assets/minecraft/textures/entity/player/wide/steve.png ou entity/steve.png).
 */
object PlayerSkin {
    val version = AtomicInteger(0)

    private fun c(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)

    private val SKIN = c(190, 138, 104)
    private val SKIN_D = c(160, 112, 84)
    private val HAIR = c(70, 48, 32)
    private val SHIRT = c(40, 170, 175)
    private val SHIRT_D = c(32, 140, 146)
    private val PANTS = c(59, 56, 141)
    private val SHOES = c(98, 98, 98)

    @Volatile var bitmap: Bitmap = procedural()
        private set

    private fun vary(col: Int, x: Int, y: Int, seed: Int): Int {
        val v = ((Noise.hash(x, y, seed) and 0xFF) / 255f - 0.5f) * 0.12f
        val f = 1f + v
        return Color.rgb(
            (Color.red(col) * f).toInt().coerceIn(0, 255),
            (Color.green(col) * f).toInt().coerceIn(0, 255),
            (Color.blue(col) * f).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Pinta as 6 faces de uma caixa (tu,tv = origem da caixa na textura; w,h,d = dimensoes).
     * face: 0 direita, 1 frente, 2 esquerda, 3 costas, 4 topo, 5 base; (x,y) locais na face.
     */
    private fun paintBox(
        px: IntArray, tu: Int, tv: Int, w: Int, h: Int, d: Int,
        f: (face: Int, x: Int, y: Int) -> Int
    ) {
        fun region(face: Int, x0: Int, y0: Int, rw: Int, rh: Int) {
            for (y in 0 until rh) for (x in 0 until rw) {
                val cx = x0 + x
                val cy = y0 + y
                if (cx in 0..63 && cy in 0..63) px[cy * 64 + cx] = vary(f(face, x, y), cx, cy, 77 + face)
            }
        }
        region(0, tu, tv + d, d, h)
        region(1, tu + d, tv + d, w, h)
        region(2, tu + d + w, tv + d, d, h)
        region(3, tu + 2 * d + w, tv + d, w, h)
        region(4, tu + d, tv, w, d)
        region(5, tu + d + w, tv, w, d)
    }

    private fun procedural(): Bitmap {
        val px = IntArray(64 * 64)

        // cabeca
        paintBox(px, 0, 0, 8, 8, 8) { face, x, y ->
            when (face) {
                4, 3 -> HAIR
                1 -> when {
                    y <= 1 -> HAIR
                    y == 2 && (x == 0 || x == 7) -> HAIR
                    y == 4 && (x == 1 || x == 6) -> Color.WHITE
                    y == 4 && (x == 2 || x == 5) -> c(70, 52, 160)
                    y == 5 && (x == 3 || x == 4) -> SKIN_D
                    y == 6 && (x in 2..5) -> c(140, 92, 72)
                    else -> SKIN
                }
                0, 2 -> if (y <= 3 || (y == 4 && x >= 5)) HAIR else SKIN
                else -> SKIN
            }
        }
        // corpo
        paintBox(px, 16, 16, 8, 12, 4) { face, _, y -> if (face == 5 || y >= 10) SHIRT_D else SHIRT }
        // bracos (direito em 40,16; esquerdo em 32,48)
        val arm: (Int, Int, Int) -> Int = { face, _, y -> if (face == 4 || y <= 3) SHIRT else SKIN }
        paintBox(px, 40, 16, 4, 12, 4, arm)
        paintBox(px, 32, 48, 4, 12, 4, arm)
        // pernas (direita em 0,16; esquerda em 16,48)
        val leg: (Int, Int, Int) -> Int = { face, _, y -> if (face == 5 || y >= 9) SHOES else PANTS }
        paintBox(px, 0, 16, 4, 12, 4, leg)
        paintBox(px, 16, 48, 4, 12, 4, leg)

        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, 64, 0, 0, 64, 64)
        return bmp
    }

    /** Skin vinda do pack (quadrada, >= 64). Skins antigas 64x32 sao ignoradas (fica a gerada). */
    fun setFromPack(b: Bitmap?) {
        var out: Bitmap? = null
        if (b != null && b.width == b.height && b.width >= 64) {
            try {
                val w = b.width
                val px = IntArray(w * w)
                b.getPixels(px, 0, w, 0, 0, w, w)
                val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
                bmp.setPixels(px, 0, w, 0, 0, w, w)
                out = bmp
            } catch (t: Throwable) { out = null }
        }
        bitmap = out ?: procedural()
        version.incrementAndGet()
    }
}

/** Posicao/rotacao de uma parte do modelo (pixels do modelo e radianos, como no Minecraft). */
class PartPose {
    var x = 0f; var y = 0f; var z = 0f
    var rx = 0f; var ry = 0f; var rz = 0f
}

/**
 * Modelo do jogador (HumanoidModel/PlayerModel do Minecraft Java 1.20): cabeca, corpo, 2 bracos, 2 pernas
 * + camada externa (chapeu, jaqueta, mangas, calcas). Geometria em coordenadas do proprio modelo
 * (y para baixo, frente = -Z), 1 unidade = 1 px; o renderizador aplica scale(-1,-1,1) e 1/16.
 */
object PlayerModel {
    const val HEAD = 0
    const val HAT = 1
    const val BODY = 2
    const val JACKET = 3
    const val R_ARM = 4
    const val R_SLEEVE = 5
    const val L_ARM = 6
    const val L_SLEEVE = 7
    const val R_LEG = 8
    const val R_PANTS = 9
    const val L_LEG = 10
    const val L_PANTS = 11
    const val PARTS = 12
    const val VERTS_PER_PART = 36

    // partes "base" que se movem (a camada externa copia a base)
    const val P_HEAD = 0
    const val P_BODY = 1
    const val P_RARM = 2
    const val P_LARM = 3
    const val P_RLEG = 4
    const val P_LLEG = 5

    /** x, y, z, u, v, brilho (6 floats por vertice), posicoes em pixels do modelo. */
    val verts: FloatArray by lazy { build() }

    private fun build(): FloatArray {
        val out = FloatArray(PARTS * VERTS_PER_PART * 6)
        var o = 0
        fun part(x0: Float, y0: Float, z0: Float, w: Int, h: Int, d: Int, inf: Float, tu: Int, tv: Int) {
            o = box(out, o, x0, y0, z0, w, h, d, inf, tu, tv)
        }
        part(-4f, -8f, -4f, 8, 8, 8, 0f, 0, 0)          // HEAD
        part(-4f, -8f, -4f, 8, 8, 8, 0.5f, 32, 0)       // HAT
        part(-4f, 0f, -2f, 8, 12, 4, 0f, 16, 16)        // BODY
        part(-4f, 0f, -2f, 8, 12, 4, 0.25f, 16, 32)     // JACKET
        part(-3f, -2f, -2f, 4, 12, 4, 0f, 40, 16)       // R_ARM
        part(-3f, -2f, -2f, 4, 12, 4, 0.25f, 40, 32)    // R_SLEEVE
        part(-1f, -2f, -2f, 4, 12, 4, 0f, 32, 48)       // L_ARM
        part(-1f, -2f, -2f, 4, 12, 4, 0.25f, 48, 48)    // L_SLEEVE
        part(-2f, 0f, -2f, 4, 12, 4, 0f, 0, 16)         // R_LEG
        part(-2f, 0f, -2f, 4, 12, 4, 0.25f, 0, 32)      // R_PANTS
        part(-2f, 0f, -2f, 4, 12, 4, 0f, 16, 48)        // L_LEG
        part(-2f, 0f, -2f, 4, 12, 4, 0.25f, 0, 48)      // L_PANTS
        return out
    }

    private fun v(a: FloatArray, o: Int, x: Float, y: Float, z: Float, u: Float, vv: Float, sh: Float): Int {
        a[o] = x; a[o + 1] = y; a[o + 2] = z; a[o + 3] = u / 64f; a[o + 4] = vv / 64f; a[o + 5] = sh
        return o + 6
    }

    /** Quad com 4 cantos (cada um com seu u,v em px da textura); 2 triangulos. */
    private fun quad(
        a: FloatArray, o0: Int, sh: Float,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
        x3: Float, y3: Float, z3: Float, u3: Float, v3: Float
    ): Int {
        var o = o0
        o = v(a, o, x0, y0, z0, u0, v0, sh)
        o = v(a, o, x1, y1, z1, u1, v1, sh)
        o = v(a, o, x2, y2, z2, u2, v2, sh)
        o = v(a, o, x0, y0, z0, u0, v0, sh)
        o = v(a, o, x2, y2, z2, u2, v2, sh)
        o = v(a, o, x3, y3, z3, u3, v3, sh)
        return o
    }

    private fun box(
        a: FloatArray, o0: Int, bx: Float, by: Float, bz: Float,
        w: Int, h: Int, d: Int, inf: Float, tu: Int, tv: Int
    ): Int {
        val xa = bx - inf; val xb = bx + w + inf
        val ya = by - inf; val yb = by + h + inf
        val za = bz - inf; val zb = bz + d + inf
        val e = 0.01f // evita sangrar para o vizinho na textura
        // regioes (px)
        val rU0 = tu + e; val rU1 = tu + d - e
        val fU0 = tu + d + e; val fU1 = tu + d + w - e
        val lU0 = tu + d + w + e; val lU1 = tu + 2 * d + w - e
        val bU0 = tu + 2 * d + w + e; val bU1 = tu + 2 * d + 2 * w - e
        val sV0 = tv + d + e; val sV1 = tv + d + h - e
        val tV0 = tv + e; val tV1 = tv + d - e
        val bsU0 = tu + d + w + e; val bsU1 = tu + d + 2 * w - e
        var o = o0
        // frente (z = za): u cresce com +X
        o = quad(a, o, 0.8f, xa, ya, za, fU0, sV0, xb, ya, za, fU1, sV0, xb, yb, za, fU1, sV1, xa, yb, za, fU0, sV1)
        // costas (z = zb): u cresce com -X
        o = quad(a, o, 0.8f, xb, ya, zb, bU0, sV0, xa, ya, zb, bU1, sV0, xa, yb, zb, bU1, sV1, xb, yb, zb, bU0, sV1)
        // direita do jogador (x = xa): u cresce com -Z
        o = quad(a, o, 0.65f, xa, ya, zb, rU0, sV0, xa, ya, za, rU1, sV0, xa, yb, za, rU1, sV1, xa, yb, zb, rU0, sV1)
        // esquerda do jogador (x = xb): u cresce com +Z
        o = quad(a, o, 0.65f, xb, ya, za, lU0, sV0, xb, ya, zb, lU1, sV0, xb, yb, zb, lU1, sV1, xb, yb, za, lU0, sV1)
        // topo (y = ya): u cresce com +X, v cresce em direcao a frente (-Z)
        o = quad(a, o, 1.0f, xa, ya, zb, fU0, tV0, xb, ya, zb, fU1, tV0, xb, ya, za, fU1, tV1, xa, ya, za, fU0, tV1)
        // base (y = yb)
        o = quad(a, o, 0.5f, xa, yb, za, bsU0, tV0, xb, yb, za, bsU1, tV0, xb, yb, zb, bsU1, tV1, xa, yb, zb, bsU0, tV1)
        return o
    }

    /**
     * Calcula a pose (HumanoidModel.setupAnim): 6 partes base.
     * limbSwing/limbAmount = andar; age em ticks; headPitch em rad (positivo = olhando para baixo);
     * attack 0..1 (progresso do golpe, 0 = parado); holding = item na mao direita.
     */
    fun pose(
        p: Array<PartPose>, limbSwing: Float, limbAmount: Float, age: Float,
        headPitch: Float, sneaking: Boolean, attack: Float, holding: Boolean
    ) {
        val head = p[P_HEAD]; val body = p[P_BODY]
        val ra = p[P_RARM]; val la = p[P_LARM]
        val rl = p[P_RLEG]; val ll = p[P_LLEG]

        head.x = 0f; head.y = 0f; head.z = 0f; head.rx = headPitch; head.ry = 0f; head.rz = 0f
        body.x = 0f; body.y = 0f; body.z = 0f; body.rx = 0f; body.ry = 0f; body.rz = 0f

        val sw = limbSwing * 0.6662f
        ra.rx = cos(sw + Math.PI.toFloat()) * 2.0f * limbAmount * 0.5f
        la.rx = cos(sw) * 2.0f * limbAmount * 0.5f
        ra.ry = 0f; la.ry = 0f; ra.rz = 0f; la.rz = 0f
        ra.x = -5f; ra.y = 2f; ra.z = 0f
        la.x = 5f; la.y = 2f; la.z = 0f
        rl.rx = cos(sw) * 1.4f * limbAmount
        ll.rx = cos(sw + Math.PI.toFloat()) * 1.4f * limbAmount
        rl.ry = 0.005f; ll.ry = -0.005f; rl.rz = 0.005f; ll.rz = -0.005f
        rl.x = -1.9f; rl.y = 12f; rl.z = 0.1f
        ll.x = 1.9f; ll.y = 12f; ll.z = 0.1f

        // segurando item: o braco direito levanta um pouco
        if (holding) ra.rx = ra.rx * 0.5f - Math.PI.toFloat() / 10f

        // golpe (setupAttackAnimation)
        if (attack > 0f) {
            body.ry = sin(sqrt(attack) * 6.2831855f) * 0.2f
            ra.z = sin(body.ry) * 5f
            ra.x = -cos(body.ry) * 5f
            la.z = -sin(body.ry) * 5f
            la.x = cos(body.ry) * 5f
            ra.ry += body.ry
            la.ry += body.ry
            la.rx += body.ry
            var f = 1f - attack
            f *= f
            f *= f
            f = 1f - f
            val f1 = sin(f * Math.PI.toFloat())
            val f2 = sin(attack * Math.PI.toFloat()) * -(head.rx - 0.7f) * 0.75f
            ra.rx -= f1 * 1.2f + f2
            ra.ry += body.ry * 2f
            ra.rz += sin(attack * Math.PI.toFloat()) * -0.4f
        }

        if (sneaking) {
            body.rx = 0.5f
            ra.rx += 0.4f
            la.rx += 0.4f
            rl.z = 4f; ll.z = 4f
            rl.y = 12.2f; ll.y = 12.2f
            head.y = 4.2f
            body.y = 3.2f
            la.y = 5.2f; ra.y = 5.2f
        }

        // balanco leve dos bracos (AnimationUtils.bobArms)
        ra.rz += cos(age * 0.09f) * 0.05f + 0.05f
        la.rz -= cos(age * 0.09f) * 0.05f + 0.05f
        ra.rx += sin(age * 0.067f) * 0.05f
        la.rx -= sin(age * 0.067f) * 0.05f
    }
}
