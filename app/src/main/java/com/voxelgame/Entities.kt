package com.voxelgame

import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Item solto no chao, no estilo do Minecraft Java (valores em blocos e segundos; 1 tick = 0,05 s):
 * caixa 0,25; gravidade 0,04/tick; atrito 0,98 (0,588 no chao); some em 5 min; atraso de coleta;
 * itens iguais proximos se juntam em pilhas de ate 64.
 */
class ItemEntity(
    var id: Int, var count: Int,
    var x: Double, var y: Double, var z: Double,
    var vx: Double, var vy: Double, var vz: Double,
    var delay: Float
) {
    var age = 0f
    val bob = Random().nextFloat() * 6.2831855f
    val seed = Random().nextInt()
    var onGround = false

    /** < 0: no chao; >= 0: sendo puxado ate o jogador (animacao de coleta). */
    var collectT = -1f
    var sx = 0.0
    var sy = 0.0
    var sz = 0.0

    companion object {
        const val SIZE = 0.25
        const val HALF = 0.125
        const val LIFE = 300f
        const val MAX_STACK = 64
    }
}

object Drops {
    private const val MAX_ENTITIES = 256

    private fun solid(b: Int): Boolean = b < 0 || (b != 0 && b != Blocks.WATER && !Blocks.isPlant(b))

    private fun hit(w: World, x: Double, y: Double, z: Double): Boolean {
        val x0 = floor(x - ItemEntity.HALF).toInt()
        val x1 = floor(x + ItemEntity.HALF - 1e-7).toInt()
        val y0 = floor(y).toInt()
        val y1 = floor(y + ItemEntity.SIZE - 1e-7).toInt()
        val z0 = floor(z - ItemEntity.HALF).toInt()
        val z1 = floor(z + ItemEntity.HALF - 1e-7).toInt()
        for (bx in x0..x1) for (bz in z0..z1) for (by in y0..y1) {
            if (solid(w.get(bx, by, bz))) return true
        }
        return false
    }

    /** Fisica de um item. Velocidades em blocos/s. */
    fun step(e: ItemEntity, w: World, dt: Float) {
        val n = ceil(dt / 0.02f).toInt().coerceIn(1, 4)
        val h = dt / n
        val tick = h * 20f
        for (i in 0 until n) {
            // soterrado por um bloco colocado em cima: sobe ate sair
            val cb = w.get(floor(e.x).toInt(), floor(e.y + ItemEntity.HALF).toInt(), floor(e.z).toInt())
            if (cb > 0 && cb != Blocks.WATER && !Blocks.isPlant(cb)) {
                e.y = floor(e.y + ItemEntity.HALF) + 1.0 + 1e-3
                e.vy = 0.0
            }
            val inWater = w.get(floor(e.x).toInt(), floor(e.y + 0.1).toInt(), floor(e.z).toInt()) == Blocks.WATER
            if (inWater) {
                val k = 0.99.pow(tick.toDouble())
                e.vx *= k
                e.vz *= k
                if (e.vy < 1.2) e.vy += 0.2 * h
            } else {
                e.vy -= 16.0 * h
            }

            var nx = e.x + e.vx * h
            if (hit(w, nx, e.y, e.z)) { nx = e.x; e.vx = 0.0 }
            e.x = nx
            var nz = e.z + e.vz * h
            if (hit(w, e.x, e.y, nz)) { nz = e.z; e.vz = 0.0 }
            e.z = nz
            val ny = e.y + e.vy * h
            if (hit(w, e.x, ny, e.z)) {
                if (e.vy < 0) {
                    e.y = floor(ny) + 1.0 + 1e-4
                    e.onGround = true
                }
                e.vy = 0.0
            } else {
                e.y = ny
                e.onGround = false
            }

            val fv = 0.98.pow(tick.toDouble())
            val fh = (if (e.onGround) 0.588 else 0.98).pow(tick.toDouble())
            e.vx *= fh
            e.vz *= fh
            e.vy *= fv
        }
    }

    /** Junta itens iguais proximos (a cada chamada, no maximo uma passada). */
    fun merge(list: ArrayList<ItemEntity>) {
        for (i in 0 until list.size) {
            val a = list[i]
            if (a.collectT >= 0f || a.count <= 0 || a.count >= ItemEntity.MAX_STACK) continue
            for (j in i + 1 until list.size) {
                val b = list[j]
                if (b.collectT >= 0f || b.count <= 0 || b.id != a.id) continue
                if (Math.abs(a.x - b.x) > 0.5 || Math.abs(a.y - b.y) > 0.5 || Math.abs(a.z - b.z) > 0.5) continue
                val m = min(b.count, ItemEntity.MAX_STACK - a.count)
                if (m <= 0) break
                a.count += m
                b.count -= m
                a.age = min(a.age, b.age)
                a.delay = max(a.delay, b.delay)
                if (a.count >= ItemEntity.MAX_STACK) break
            }
        }
        list.removeAll { it.count <= 0 }
    }

    fun limit(list: ArrayList<ItemEntity>) {
        while (list.size > MAX_ENTITIES) list.removeAt(0)
    }
}

/**
 * Particulas de bloco como no Minecraft Java: pedacinhos 1/4 da textura (4x4 px),
 * escurecidos (0,6), gravidade, vida de 0,2 a 2 s.
 */
class Particles {
    companion object {
        const val MAX = 700
    }

    val x = DoubleArray(MAX)
    val y = DoubleArray(MAX)
    val z = DoubleArray(MAX)
    val vx = FloatArray(MAX)
    val vy = FloatArray(MAX)
    val vz = FloatArray(MAX)
    val age = FloatArray(MAX)
    val life = FloatArray(MAX)
    val size = FloatArray(MAX)   // meia largura do quad
    val tile = IntArray(MAX)
    val uo = FloatArray(MAX)     // deslocamento dentro do tile, em fracao do tile (0..0.75)
    val vo = FloatArray(MAX)
    var count = 0
    private val rnd = Random()

    private fun add(
        px: Double, py: Double, pz: Double, dx: Float, dy: Float, dz: Float,
        tileId: Int, power: Float, scale: Float
    ) {
        if (count >= MAX) {
            // recicla a mais velha (a primeira da lista)
            for (i in 1 until count) copy(i, i - 1)
            count--
        }
        val i = count++
        x[i] = px; y[i] = py; z[i] = pz
        // mesma formula do Particle(...) do Java
        var ax = dx + (rnd.nextFloat() * 2f - 1f) * 0.4f
        var ay = dy + (rnd.nextFloat() * 2f - 1f) * 0.4f
        var az = dz + (rnd.nextFloat() * 2f - 1f) * 0.4f
        val f = (rnd.nextFloat() + rnd.nextFloat() + 1f) * 0.15f
        val d = max(1e-4f, sqrt(ax * ax + ay * ay + az * az))
        ax = ax / d * f * 0.4f
        ay = ay / d * f * 0.4f + 0.1f
        az = az / d * f * 0.4f
        // setPower
        ax *= power
        ay = (ay - 0.1f) * power + 0.1f
        az *= power
        vx[i] = ax * 20f; vy[i] = ay * 20f; vz[i] = az * 20f
        age[i] = 0f
        life[i] = 4f / (rnd.nextFloat() * 0.9f + 0.1f) * 0.05f
        size[i] = 0.1f * (rnd.nextFloat() * 0.5f + 0.5f) * scale   // (0,1..0,2)/2 * 2 -> meia largura
        tile[i] = tileId
        uo[i] = rnd.nextFloat() * 3f / 4f
        vo[i] = rnd.nextFloat() * 3f / 4f
    }

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; z[to] = z[from]
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        age[to] = age[from]; life[to] = life[from]; size[to] = size[from]
        tile[to] = tile[from]; uo[to] = uo[from]; vo[to] = vo[from]
    }

    /** Explosao 4x4x4 ao quebrar um bloco. */
    fun burst(bx: Int, by: Int, bz: Int, tileId: Int) {
        for (i in 0 until 4) for (j in 0 until 4) for (k in 0 until 4) {
            val fx = (i + 0.5) / 4.0
            val fy = (j + 0.5) / 4.0
            val fz = (k + 0.5) / 4.0
            add(
                bx + fx, by + fy, bz + fz,
                (fx - 0.5).toFloat(), (fy - 0.5).toFloat(), (fz - 0.5).toFloat(),
                tileId, 1f, 1f
            )
        }
    }

    /** Uma particula na face que esta sendo minerada (a cada 4 ticks). */
    fun hit(bx: Int, by: Int, bz: Int, nx: Int, ny: Int, nz: Int, tileId: Int) {
        val ox = if (nx != 0) (if (nx > 0) 1.1 else -0.1) else 0.1 + rnd.nextDouble() * 0.8
        val oy = if (ny != 0) (if (ny > 0) 1.1 else -0.1) else 0.1 + rnd.nextDouble() * 0.8
        val oz = if (nz != 0) (if (nz > 0) 1.1 else -0.1) else 0.1 + rnd.nextDouble() * 0.8
        add(bx + ox, by + oy, bz + oz, 0f, 0f, 0f, tileId, 0.2f, 0.6f)
    }

    private fun solid(w: World, px: Double, py: Double, pz: Double): Boolean {
        val b = w.get(floor(px).toInt(), floor(py).toInt(), floor(pz).toInt())
        return b < 0 || (b != 0 && b != Blocks.WATER && !Blocks.isPlant(b))
    }

    fun update(w: World, dt: Float) {
        val tick = dt * 20f
        val fr = 0.98f.pow(tick)
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                if (i < count - 1) copy(count - 1, i)
                count--
                continue
            }
            vy[i] -= 16f * dt
            val nx = x[i] + vx[i] * dt
            if (solid(w, nx, y[i], z[i])) vx[i] = 0f else x[i] = nx
            val nz = z[i] + vz[i] * dt
            if (solid(w, x[i], y[i], nz)) vz[i] = 0f else z[i] = nz
            val ny = y[i] + vy[i] * dt
            var ground = false
            if (solid(w, x[i], ny, z[i])) {
                if (vy[i] < 0f) ground = true
                vy[i] = 0f
            } else y[i] = ny
            vx[i] *= fr; vy[i] *= fr; vz[i] *= fr
            if (ground) {
                val g = 0.7f.pow(tick)
                vx[i] *= g; vz[i] *= g
            }
            i++
        }
    }
}
