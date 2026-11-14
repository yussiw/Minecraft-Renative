package com.voxelgame

/**
 * Receitas no formato do Minecraft (crafting_shaped / crafting_shapeless).
 * Cada ingrediente aceita varias alternativas (arrays e tags simples como #planks).
 */
class Recipe(
    val shaped: Boolean, val w: Int, val h: Int,
    val grid: Array<IntArray?>,      // shaped: w*h ingredientes (null = vazio)
    val list: List<IntArray>,        // shapeless
    val result: Int, val count: Int
)

object Recipes {
    @Volatile private var all: List<Recipe> = builtin()

    private fun builtin(): List<Recipe> = listOf(
        Recipe(false, 0, 0, emptyArray(), listOf(intArrayOf(5)), 7, 4)
    )

    fun reset() { all = builtin() }

    fun add(r: Recipe) { all = all + r }

    /** Ids que uma tag simples representa. */
    fun tag(name: String): IntArray? = when (name.removePrefix("#").removePrefix("minecraft:").removePrefix("c:")) {
        "planks" -> intArrayOf(7)
        "logs", "oak_logs" -> intArrayOf(5)
        "sand" -> intArrayOf(4)
        "stone_crafting_materials", "cobblestone" -> intArrayOf(8)
        else -> null
    }

    /** grade 3x3 (id por celula, 0 = vazio) -> receita, ou null. */
    fun find(cells: IntArray): Recipe? {
        var minX = 3; var minY = 3; var maxX = -1; var maxY = -1
        var n = 0
        for (y in 0 until 3) for (x in 0 until 3) {
            if (cells[y * 3 + x] != 0) {
                n++
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (n == 0) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        for (r in all) {
            if (r.shaped) {
                if (r.w != bw || r.h != bh) continue
                if (matchShaped(r, cells, minX, minY, false) || matchShaped(r, cells, minX, minY, true)) return r
            } else {
                if (r.list.size != n) continue
                if (matchShapeless(r, cells)) return r
            }
        }
        return null
    }

    private fun matchShaped(r: Recipe, cells: IntArray, ox: Int, oy: Int, mirror: Boolean): Boolean {
        for (y in 0 until r.h) for (x in 0 until r.w) {
            val gx = if (mirror) r.w - 1 - x else x
            val need = r.grid[y * r.w + gx]
            val have = cells[(oy + y) * 3 + (ox + x)]
            if (need == null) { if (have != 0) return false }
            else if (have == 0 || !need.contains(have)) return false
        }
        return true
    }

    private fun matchShapeless(r: Recipe, cells: IntArray): Boolean {
        val used = BooleanArray(r.list.size)
        for (c in cells) {
            if (c == 0) continue
            var ok = false
            for (i in r.list.indices) {
                if (!used[i] && r.list[i].contains(c)) { used[i] = true; ok = true; break }
            }
            if (!ok) return false
        }
        return true
    }
}
