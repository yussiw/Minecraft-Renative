package com.voxelgame

import kotlin.math.floor

object Noise {
    fun hash(x: Int, z: Int, seed: Int): Int {
        var h = x * 374761393 + z * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    fun rnd(x: Int, z: Int, seed: Int): Float = (hash(x, z, seed) and 0xFFFFFF) / 16777216f

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    fun value(x: Float, z: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fz = smooth(z - z0)
        val a = rnd(x0, z0, seed)
        val b = rnd(x0 + 1, z0, seed)
        val c = rnd(x0, z0 + 1, seed)
        val d = rnd(x0 + 1, z0 + 1, seed)
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fz
    }

    fun fbm(x: Float, z: Float, seed: Int, octaves: Int): Float {
        var sum = 0f
        var amp = 1f
        var norm = 0f
        var f = 1f
        for (i in 0 until octaves) {
            sum += value(x * f, z * f, seed + i * 31) * amp
            norm += amp
            amp *= 0.5f
            f *= 2f
        }
        return sum / norm
    }
}
