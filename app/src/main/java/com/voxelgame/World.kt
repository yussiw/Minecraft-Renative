package com.voxelgame

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

const val H = 96
const val SEED = 1337
const val SAND_MAX = 27
const val SEA = 27
const val SNOW_Y = 56

fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
fun keyX(k: Long): Int = (k shr 32).toInt()
fun keyZ(k: Long): Int = k.toInt()

/** Coluna-major: indice = (x*16 + z)*H + y. Colunas contiguas em memoria. */
class Chunk(val cx: Int, val cz: Int) {
    val blocks = ByteArray(16 * 16 * H)
    @Volatile var edited = false
}

class World {
    val chunks = ConcurrentHashMap<Long, Chunk>()
    val remeshQueue = ConcurrentLinkedQueue<Long>()

    fun chunk(cx: Int, cz: Int): Chunk {
        val k = chunkKey(cx, cz)
        return chunks[k] ?: chunks.computeIfAbsent(k) { generate(cx, cz) }
    }

    /** 0 = ar, >0 = bloco, -1 = chunk ainda nao carregado (tratado como solido na fisica). */
    fun get(x: Int, y: Int, z: Int): Int {
        if (y < 0) return 1
        if (y >= H) return 0
        val c = chunks[chunkKey(x shr 4, z shr 4)] ?: return -1
        return c.blocks[((x and 15) * 16 + (z and 15)) * H + y].toInt()
    }

    /** Chamado depois de uma edicao LOCAL (quebrar/colocar). O multijogador usa isso para avisar a rede. */
    @Volatile var onLocalSet: ((Int, Int, Int, Int) -> Unit)? = null

    fun set(x: Int, y: Int, z: Int, id: Int) {
        if (setBlock(x, y, z, id)) onLocalSet?.invoke(x, y, z, id)
    }

    /** Edicao vinda da rede: gera o chunk se preciso e nao avisa a rede de novo. */
    fun setRemote(x: Int, y: Int, z: Int, id: Int) {
        if (y < 0 || y >= H) return
        chunk(x shr 4, z shr 4)
        setBlock(x, y, z, id)
    }

    private fun setBlock(x: Int, y: Int, z: Int, id: Int): Boolean {
        if (y < 0 || y >= H) return false
        val cx = x shr 4
        val cz = z shr 4
        val c = chunks[chunkKey(cx, cz)] ?: return false
        val lx = x and 15
        val lz = z and 15
        c.blocks[(lx * 16 + lz) * H + y] = id.toByte()
        c.edited = true
        remeshQueue.add(chunkKey(cx, cz))
        val ex = if (lx == 0) -1 else if (lx == 15) 1 else 0
        val ez = if (lz == 0) -1 else if (lz == 15) 1 else 0
        if (ex != 0) remeshQueue.add(chunkKey(cx + ex, cz))
        if (ez != 0) remeshQueue.add(chunkKey(cx, cz + ez))
        if (ex != 0 && ez != 0) remeshQueue.add(chunkKey(cx + ex, cz + ez))
        return true
    }

    private fun heightAt(wx: Int, wz: Int): Int {
        val base = Noise.fbm(wx * 0.006f, wz * 0.006f, SEED, 4)
        val detail = Noise.fbm(wx * 0.045f, wz * 0.045f, SEED + 99, 3)
        val h = 34f + (base - 0.5f) * 100f + (detail - 0.5f) * 12f
        return h.toInt().coerceIn(3, H - 12)
    }

    private fun generate(cx: Int, cz: Int): Chunk {
        val c = Chunk(cx, cz)
        val b = c.blocks
        val x0 = cx * 16
        val z0 = cz * 16
        val rim = 3
        val n = 16 + 2 * rim
        val hm = IntArray(n * n)
        for (i in 0 until n) for (j in 0 until n) hm[i * n + j] = heightAt(x0 - rim + i, z0 - rim + j)

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val h = hm[(x + rim) * n + (z + rim)]
                val base = (x * 16 + z) * H
                val sandy = h <= SAND_MAX
                val snowy = h >= SNOW_Y
                val gravel = sandy && h < SEA - 1 &&
                    Noise.fbm((x0 + x) * 0.07f, (z0 + z) * 0.07f, SEED + 7, 2) > 0.6f
                for (y in 0..h) {
                    val id = when {
                        y == h -> if (sandy) (if (gravel) 10 else 4) else if (snowy) 9 else 1
                        y >= h - 3 -> if (sandy) 4 else if (snowy) 3 else 2
                        else -> 3
                    }
                    b[base + y] = id.toByte()
                }
                if (h < SEA) {
                    for (y in (h + 1)..SEA) b[base + y] = 12.toByte()
                }
            }
        }

        // minerios de mods ("worldgen"): veios aleatorios dentro da pedra, deterministicos por chunk
        for (ore in Blocks.ores) {
            var s = Noise.hash(cx * 73 + ore.id, cz * 91 - ore.id, SEED + 300 + ore.id)
            fun nxt(): Int { s = s * 1103515245 + 12345; return (s ushr 8) and 0xFFFFFF }
            for (t in 0 until ore.tries) {
                var ox = nxt() % 16
                var oy = ore.minY + nxt() % max(1, ore.maxY - ore.minY + 1)
                var oz = nxt() % 16
                for (k in 0 until ore.vein) {
                    if (ox in 0..15 && oz in 0..15 && oy in 1 until H) {
                        val idx = (ox * 16 + oz) * H + oy
                        if (b[idx].toInt() == 3) b[idx] = ore.id.toByte()
                    }
                    when (nxt() % 6) {
                        0 -> ox++
                        1 -> ox--
                        2 -> oy++
                        3 -> oy--
                        4 -> oz++
                        else -> oz--
                    }
                }
            }
        }

        fun put(wx: Int, y: Int, wz: Int, id: Int, onlyAir: Boolean) {
            val lx = wx - x0
            val lz = wz - z0
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || y < 0 || y >= H) return
            val idx = (lx * 16 + lz) * H + y
            if (onlyAir && b[idx].toInt() != 0) return
            b[idx] = id.toByte()
        }

        // arvores deterministicas por coluna do mundo (inclui as vizinhas cujas folhas invadem este chunk)
        for (i in (rim - 2)..(rim + 17)) {
            for (j in (rim - 2)..(rim + 17)) {
                val wx = x0 - rim + i
                val wz = z0 - rim + j
                val h = hm[i * n + j]
                if (h <= SAND_MAX + 1 || h >= SNOW_Y - 4) continue
                val r = Noise.hash(wx, wz, SEED + 5)
                if ((r and 0x3F) != 0) continue
                val t = h + 4 + ((r ushr 8) and 1)
                for (dx in -2..2) for (dz in -2..2) {
                    val corner = Math.abs(dx) == 2 && Math.abs(dz) == 2
                    if (!corner) {
                        put(wx + dx, t - 1, wz + dz, 6, true)
                        put(wx + dx, t, wz + dz, 6, true)
                    }
                }
                for (dx in -1..1) for (dz in -1..1) put(wx + dx, t + 1, wz + dz, 6, true)
                put(wx, t + 2, wz, 6, true)
                put(wx + 1, t + 2, wz, 6, true)
                put(wx - 1, t + 2, wz, 6, true)
                put(wx, t + 2, wz + 1, 6, true)
                put(wx, t + 2, wz - 1, 6, true)
                for (y in (h + 1)..t) put(wx, y, wz, 5, false)
            }
        }

        // grama alta e flores sobre a grama
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val h = hm[(x + rim) * n + (z + rim)]
                if (h <= SAND_MAX || h >= SNOW_Y || h + 1 >= H) continue
                val base = (x * 16 + z) * H
                if (b[base + h + 1].toInt() != 0) continue
                val v = (Noise.hash(x0 + x, z0 + z, SEED + 21) and 0xFFFF) / 65535f
                val id = if (v < 0.18f) 14 else if (v < 0.188f) 15 else if (v < 0.196f) 16 else 0
                if (id != 0) b[base + h + 1] = id.toByte()
            }
        }
        return c
    }
}
