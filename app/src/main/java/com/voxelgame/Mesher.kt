package com.voxelgame

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** buf = blocos opacos; buf2 = vidro e agua (desenhados depois, com transparencia). */
class MeshData(
    val cx: Int, val cz: Int, val ver: Int,
    val buf: ByteBuffer?, val quads: Int, val maxY: Int,
    val buf2: ByteBuffer? = null, val quads2: Int = 0
)

/**
 * Gera a malha de um chunk: so faces visiveis, AO por vertice, 8 bytes por vertice.
 * Vertice = [x, y, z, brilho, tile, canto-de-textura, 0, 0]
 */
object Mesher {
    const val MAXQ = 16383

    private class Scratch {
        val padded = ByteArray(18 * 18 * H)
        val verts = ByteArray(MAXQ * 4 * 8)
        val verts2 = ByteArray(MAXQ * 4 * 8)
    }

    private val tl = object : ThreadLocal<Scratch>() {
        override fun initialValue(): Scratch = Scratch()
    }

    // 0:+X 1:-X 2:+Y 3:-Y 4:+Z 5:-Z
    private val FNX = intArrayOf(1, -1, 0, 0, 0, 0)
    private val FNY = intArrayOf(0, 0, 1, -1, 0, 0)
    private val FNZ = intArrayOf(0, 0, 0, 0, 1, -1)
    private val FUX = intArrayOf(0, 0, 0, 1, 1, 0)
    private val FUY = intArrayOf(1, 0, 0, 0, 0, 1)
    private val FUZ = intArrayOf(0, 1, 1, 0, 0, 0)
    private val FVX = intArrayOf(0, 0, 1, 0, 0, 1)
    private val FVY = intArrayOf(0, 1, 0, 0, 1, 0)
    private val FVZ = intArrayOf(1, 0, 0, 1, 0, 0)
    private val BX = intArrayOf(1, 0, 0, 0, 0, 0)
    private val BY = intArrayOf(0, 0, 1, 0, 0, 0)
    private val BZ = intArrayOf(0, 0, 0, 0, 1, 0)
    private val SWAP = booleanArrayOf(true, false, false, false, false, true)
    private val FS = floatArrayOf(0.75f, 0.75f, 1.0f, 0.55f, 0.88f, 0.88f)
    private val AOB = floatArrayOf(0.45f, 0.65f, 0.82f, 1.0f)

    /** So blocos opacos projetam oclusao ambiente. */
    private fun solid(pad: ByteArray, px: Int, y: Int, pz: Int): Int {
        if (y < 0) return 1
        if (y >= H) return 0
        val v = pad[(px * 18 + pz) * H + y].toInt()
        return if (v != 0 && v != Blocks.GLASS && v != Blocks.WATER && !Blocks.isPlant(v)) 1 else 0
    }

    private fun faceVisible(b: Int, nb: Int): Boolean {
        if (nb == 0 || Blocks.isPlant(nb)) return true
        return when (b) {
            Blocks.WATER -> nb == Blocks.GLASS
            Blocks.GLASS -> nb == Blocks.WATER
            else -> nb == Blocks.GLASS || nb == Blocks.WATER
        }
    }

    /** Planta = 2 planos diagonais, cada um com as duas faces (4 quads). */
    private fun emitPlant(out: ByteArray, o0: Int, x: Int, y: Int, z: Int, tile: Int): Int {
        var o = o0
        for (d in 0 until 2) {
            val ax = if (d == 0) x else x + 1
            val bx = if (d == 0) x + 1 else x
            for (side in 0 until 2) {
                for (k in 0 until 4) {
                    val kk = if (side == 0) k else 3 - k
                    val atB = kk == 1 || kk == 2
                    val top = kk >= 2
                    out[o] = (if (atB) bx else ax).toByte()
                    out[o + 1] = (if (top) y + 1 else y).toByte()
                    out[o + 2] = (if (atB) z + 1 else z).toByte()
                    out[o + 3] = 235.toByte()
                    out[o + 4] = tile.toByte()
                    out[o + 5] = ((if (atB) 1 else 0) or ((if (top) 1 else 0) shl 1)).toByte()
                    out[o + 6] = 0
                    out[o + 7] = 0
                    o += 8
                }
            }
        }
        return o
    }

    private fun pack(src: ByteArray, len: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
        b.put(src, 0, len)
        b.flip()
        return b
    }

    fun build(w: World, cx: Int, cz: Int, ver: Int): MeshData {
        val s = tl.get()!!
        val pad = s.padded
        val outA = s.verts
        val outB = s.verts2

        val ch = arrayOfNulls<Chunk>(9)
        for (dz in -1..1) for (dx in -1..1) ch[(dz + 1) * 3 + (dx + 1)] = w.chunk(cx + dx, cz + dz)

        for (px in 0 until 18) {
            val lx = px - 1
            val ncx = if (lx < 0) 0 else if (lx > 15) 2 else 1
            val nlx = lx and 15
            for (pz in 0 until 18) {
                val lz = pz - 1
                val ncz = if (lz < 0) 0 else if (lz > 15) 2 else 1
                val nlz = lz and 15
                System.arraycopy(ch[ncz * 3 + ncx]!!.blocks, (nlx * 16 + nlz) * H, pad, (px * 18 + pz) * H, H)
            }
        }

        var oA = 0
        var oB = 0
        var quadsA = 0
        var quadsB = 0
        var maxY = 0
        val ao = IntArray(4)

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val col = ((x + 1) * 18 + (z + 1)) * H
                for (y in 0 until H) {
                    val b = pad[col + y].toInt()
                    if (b == 0) continue
                    if (Blocks.isPlant(b)) {
                        if (quadsA + 4 > MAXQ) continue
                        oA = emitPlant(outA, oA, x, y, z, Blocks.top[b])
                        quadsA += 4
                        if (y + 1 > maxY) maxY = y + 1
                        continue
                    }
                    val trans = b == Blocks.GLASS || b == Blocks.WATER
                    for (f in 0 until 6) {
                        val nx = FNX[f]
                        val ny = FNY[f]
                        val nz = FNZ[f]
                        val yy = y + ny
                        val nb = if (yy < 0) 1 else if (yy >= H) 0 else pad[col + nx * 18 * H + nz * H + yy].toInt()
                        if (!faceVisible(b, nb)) continue
                        if (trans) { if (quadsB >= MAXQ) continue } else { if (quadsA >= MAXQ) continue }

                        val ux = FUX[f]; val uy = FUY[f]; val uz = FUZ[f]
                        val vx = FVX[f]; val vy = FVY[f]; val vz = FVZ[f]
                        val bx = x + 1 + nx
                        val by = y + ny
                        val bz = z + 1 + nz
                        if (trans) {
                            ao.fill(3)
                        } else {
                            for (c in 0 until 4) {
                                val su = if (c == 1 || c == 2) 1 else -1
                                val sv = if (c >= 2) 1 else -1
                                val s1 = solid(pad, bx + su * ux, by + su * uy, bz + su * uz)
                                val s2 = solid(pad, bx + sv * vx, by + sv * vy, bz + sv * vz)
                                val cr = solid(pad, bx + su * ux + sv * vx, by + su * uy + sv * vy, bz + su * uz + sv * vz)
                                ao[c] = if (s1 == 1 && s2 == 1) 0 else 3 - (s1 + s2 + cr)
                            }
                        }

                        val tile = when (f) {
                            2 -> Blocks.top[b]
                            3 -> Blocks.bottom[b]
                            else -> Blocks.side[b]
                        }
                        val out = if (trans) outB else outA
                        var o = if (trans) oB else oA
                        val start = if (ao[0] + ao[2] >= ao[1] + ao[3]) 0 else 1
                        for (k in 0 until 4) {
                            val c = (start + k) and 3
                            val cu = if (c == 1 || c == 2) 1 else 0
                            val cv = if (c >= 2) 1 else 0
                            var tu = cu
                            var tv = cv
                            if (SWAP[f]) { tu = cv; tv = cu }
                            out[o] = (x + BX[f] + cu * ux + cv * vx).toByte()
                            out[o + 1] = (y + BY[f] + cu * uy + cv * vy).toByte()
                            out[o + 2] = (z + BZ[f] + cu * uz + cv * vz).toByte()
                            out[o + 3] = (FS[f] * AOB[ao[c]] * 255f).toInt().toByte()
                            out[o + 4] = (tile or (if (Blocks.glow[b]) 64 else 0)).toByte()
                            out[o + 5] = (tu or (tv shl 1)).toByte()
                            out[o + 6] = 0
                            out[o + 7] = 0
                            o += 8
                        }
                        if (trans) { oB = o; quadsB++ } else { oA = o; quadsA++ }
                        if (y + 1 > maxY) maxY = y + 1
                    }
                }
            }
        }

        // chunk sem faces: malha vazia (buffer nao nulo, para marcar o chunk como carregado)
        val bufA = if (quadsA > 0) pack(outA, oA) else ByteBuffer.allocateDirect(0)
        val bufB = if (quadsB > 0) pack(outB, oB) else null
        return MeshData(cx, cz, ver, bufA, quadsA, maxY, bufB, quadsB)
    }
}
