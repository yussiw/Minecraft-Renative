package com.voxelgame

import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------- ajudantes de argumentos

private fun List<Any?>.num(i: Int, fn: String): Double {
    val v = getOrNull(i)
    if (v is Double) return v
    if (v is String) v.toDoubleOrNull()?.let { return it }
    throw ScriptError("$fn: o argumento ${i + 1} precisa ser um número")
}

private fun List<Any?>.numOr(i: Int, d: Double): Double = (getOrNull(i) as? Double) ?: d

private fun List<Any?>.int(i: Int, fn: String): Int = floor(num(i, fn)).toInt()

private fun List<Any?>.intOr(i: Int, d: Int): Int = (getOrNull(i) as? Double)?.let { floor(it).toInt() } ?: d

private fun List<Any?>.str(i: Int, fn: String): String {
    val v = getOrNull(i)
    if (v is String) return v
    throw ScriptError("$fn: o argumento ${i + 1} precisa ser um texto")
}

private fun List<Any?>.boolOr(i: Int, d: Boolean): Boolean = (getOrNull(i) as? Boolean) ?: d

@Suppress("UNCHECKED_CAST")
private fun List<Any?>.tbl(i: Int, fn: String): Map<String, Any?> {
    val v = getOrNull(i)
    if (v is Map<*, *>) return v as Map<String, Any?>
    throw ScriptError("$fn: o argumento ${i + 1} precisa ser uma tabela/objeto")
}

private fun text(v: Any?): String = when (v) {
    null -> "nil"
    is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    is Map<*, *> -> "{...}"
    is List<*> -> "[...]"
    is ScriptCallback -> "<função>"
    else -> v.toString()
}

private fun game(fn: String): Game = ScriptHost.game
    ?: throw ScriptError("$fn só funciona depois que o jogo começa (use events.on(\"ready\", ...) ou outros eventos), não no carregamento do mod")

/**
 * A API que os mods enxergam. E definida UMA vez aqui e exposta igual para Lua e JavaScript:
 * os motores (LuaEngine / JsEngine) criam tabelas/objetos para cada nome de NAMES e encaminham as
 * chamadas para call(). Valores trocados: null, Boolean, Double, String, List, Map e ScriptCallback.
 */
object ScriptApi {
    val NAMES = arrayOf(
        "log",
        "mod.info", "mod.read",
        "blocks.register", "blocks.exists",
        "items.register", "items.exists",
        "recipes.add", "recipes.shaped", "recipes.shapeless",
        "events.on",
        "timer.after", "timer.every", "timer.cancel",
        "storage.get", "storage.set", "storage.delete",
        "world.get_block", "world.set_block", "world.fill", "world.explode", "world.top_y",
        "world.is_loaded", "world.get_time", "world.set_time", "world.drop",
        "player.pos", "player.set_pos", "player.velocity", "player.set_velocity",
        "player.look", "player.target",
        "player.health", "player.set_health", "player.damage", "player.heal",
        "player.food", "player.set_food",
        "player.gamemode", "player.set_gamemode", "player.set_flying",
        "player.give", "player.take", "player.count", "player.held",
        "player.slot", "player.set_slot", "player.selected",
        "player.message"
    )

    private val rnd = Random()

    /** "ruby" -> bloco/item do proprio mod; "minecraft:dirt" / "dirt" -> jogo base; -1 = desconhecido. */
    fun resolveId(mod: ScriptMod, s: String): Int {
        val k = s.trim().lowercase()
        if (k == "air" || k == "minecraft:air") return 0
        if (!k.contains(':')) {
            val own = Blocks.keyToId[mod.id + ":" + k]
            if (own != null) return own
        }
        val id = Blocks.idOf(k)
        return if (id > 0) id else -1
    }

    private fun blockId(mod: ScriptMod, s: String, fn: String): Int {
        val id = resolveId(mod, s)
        if (id < 0 || id > Blocks.MOD_LAST) throw ScriptError("$fn: bloco desconhecido '$s'")
        return id
    }

    private fun itemId(mod: ScriptMod, s: String, fn: String): Int {
        val id = resolveId(mod, s)
        if (id <= 0) throw ScriptError("$fn: item/bloco desconhecido '$s'")
        return id
    }

    private fun countOf(inv: Inventory, id: Int): Int {
        var n = 0
        for (i in 0 until 36) if (inv.id[i] == id && inv.count[i] > 0) n += inv.count[i]
        return n
    }

    fun call(mod: ScriptMod, name: String, a: List<Any?>): Any? {
        when (name) {
            // ------------------------------------------------------------ geral
            "log" -> {
                ScriptHost.log(mod, a.joinToString("\t") { text(it) })
                return null
            }
            "mod.info" -> return mapOf(
                "id" to mod.id, "name" to mod.name, "version" to mod.version,
                "author" to mod.author, "description" to mod.description
            )
            "mod.read" -> return mod.files[a.str(0, name).lowercase().removePrefix("/")]

            // ------------------------------------------------------------ registro (so no carregamento)
            "blocks.register" -> {
                ModManager.scriptBlock(mod, a.str(0, name), a.tbl(1, name))
                return null
            }
            "items.register" -> {
                ModManager.scriptItem(mod, a.str(0, name), a.tbl(1, name))
                return null
            }
            "recipes.add" -> {
                ModManager.scriptRecipe(mod, a.tbl(0, name))
                return null
            }
            "recipes.shaped" -> {
                val j = LinkedHashMap<String, Any?>()
                j["type"] = "crafting_shaped"
                j["pattern"] = a.getOrNull(0)
                j["key"] = a.getOrNull(1)
                j["result"] = mapOf("item" to a.str(2, name), "count" to a.numOr(3, 1.0))
                ModManager.scriptRecipe(mod, j)
                return null
            }
            "recipes.shapeless" -> {
                val j = LinkedHashMap<String, Any?>()
                j["type"] = "crafting_shapeless"
                j["ingredients"] = a.getOrNull(0)
                j["result"] = mapOf("item" to a.str(1, name), "count" to a.numOr(2, 1.0))
                ModManager.scriptRecipe(mod, j)
                return null
            }
            "blocks.exists" -> {
                val id = resolveId(mod, a.str(0, name))
                return id in 1..Blocks.MOD_LAST
            }
            "items.exists" -> return resolveId(mod, a.str(0, name)) >= Blocks.APPLE

            // ------------------------------------------------------------ eventos e tempo
            "events.on" -> {
                val cb = a.getOrNull(1) as? ScriptCallback
                    ?: throw ScriptError("events.on: o segundo argumento precisa ser uma função")
                ScriptHost.addHandler(mod, a.str(0, name), cb)
                return null
            }
            "timer.after", "timer.every" -> {
                val cb = a.getOrNull(1) as? ScriptCallback
                    ?: throw ScriptError("$name: o segundo argumento precisa ser uma função")
                return ScriptHost.addTimer(mod, a.num(0, name), name == "timer.every", cb).toDouble()
            }
            "timer.cancel" -> {
                ScriptHost.cancelTimer(a.int(0, name))
                return null
            }

            // ------------------------------------------------------------ dados salvos do mod
            "storage.get" -> {
                val o = ScriptHost.storage(mod)
                val k = a.str(0, name)
                return if (o.has(k) && !o.isNull(k)) ScriptJson.decode(o.opt(k)) else a.getOrNull(1)
            }
            "storage.set" -> {
                val o = ScriptHost.storage(mod)
                val k = a.str(0, name)
                if (k.length > 200) throw ScriptError("storage.set: nome da chave muito longo")
                val v = a.getOrNull(1)
                if (v == null) o.remove(k) else o.put(k, ScriptJson.encode(v))
                mod.dataDirty = true
                return null
            }
            "storage.delete" -> {
                ScriptHost.storage(mod).remove(a.str(0, name))
                mod.dataDirty = true
                return null
            }

            // ------------------------------------------------------------ mundo
            "world.get_block" -> {
                val g = game(name)
                val id = g.world.get(a.int(0, name), a.int(1, name), a.int(2, name))
                return if (id < 0) null else Blocks.keyOf(id)
            }
            "world.set_block" -> {
                val g = game(name)
                val x = a.int(0, name)
                val y = a.int(1, name)
                val z = a.int(2, name)
                val id = blockId(mod, a.str(3, name), name)
                if (y < 0 || y >= H || g.world.get(x, y, z) < 0) return false
                g.world.set(x, y, z, id)
                return true
            }
            "world.fill" -> {
                val g = game(name)
                val x1 = a.int(0, name); val y1 = a.int(1, name); val z1 = a.int(2, name)
                val x2 = a.int(3, name); val y2 = a.int(4, name); val z2 = a.int(5, name)
                val id = blockId(mod, a.str(6, name), name)
                val xa = min(x1, x2); val xb = max(x1, x2)
                val ya = max(0, min(y1, y2)); val yb = min(H - 1, max(y1, y2))
                val za = min(z1, z2); val zb = max(z1, z2)
                val vol = (xb - xa + 1).toLong() * (yb - ya + 1).toLong() * (zb - za + 1).toLong()
                if (vol > 32768L) throw ScriptError("world.fill: área grande demais (máximo 32768 blocos)")
                var n = 0
                for (x in xa..xb) for (y in ya..yb) for (z in za..zb) {
                    if (g.world.get(x, y, z) < 0) continue
                    g.world.set(x, y, z, id)
                    n++
                }
                return n.toDouble()
            }
            "world.explode" -> {
                val g = game(name)
                val cx = a.int(0, name)
                val cy = a.int(1, name)
                val cz = a.int(2, name)
                val r = min(a.numOr(3, 3.0), 12.0).coerceAtLeast(0.5)
                val drop = a.boolOr(4, false)
                val ri = ceil(r).toInt()
                var n = 0
                for (dx in -ri..ri) for (dy in -ri..ri) for (dz in -ri..ri) {
                    if ((dx * dx + dy * dy + dz * dz).toDouble() > r * r) continue
                    val x = cx + dx
                    val y = cy + dy
                    val z = cz + dz
                    if (y < 0 || y >= H) continue
                    val b = g.world.get(x, y, z)
                    if (b <= 0 || b == Blocks.WATER) continue
                    if (drop) {
                        val d = Blocks.drop(b, rnd.nextFloat())
                        if (d != 0) g.scriptDrop(d, Blocks.dropCount(b), x, y, z)
                    }
                    if (n % 5 == 0) g.particles.burst(x, y, z, Blocks.particleTile(b))
                    g.world.set(x, y, z, 0)
                    n++
                }
                return n.toDouble()
            }
            "world.top_y" -> {
                val g = game(name)
                val x = a.int(0, name)
                val z = a.int(1, name)
                if (g.world.get(x, 0, z) < 0) return null
                for (y in H - 1 downTo 0) {
                    val b = g.world.get(x, y, z)
                    if (b > 0 && b != Blocks.WATER && !Blocks.isPlant(b)) return y.toDouble()
                }
                return null
            }
            "world.is_loaded" -> return game(name).world.get(a.int(0, name), 0, a.int(1, name)) >= 0
            "world.get_time" -> return game(name).scriptHours()
            "world.set_time" -> {
                game(name).scriptSetTime(a.num(0, name))
                return null
            }
            "world.drop" -> {
                val g = game(name)
                val id = itemId(mod, a.str(0, name), name)
                g.scriptDrop(id, a.intOr(1, 1).coerceIn(1, 64), a.int(2, name), a.int(3, name), a.int(4, name))
                return null
            }

            // ------------------------------------------------------------ jogador: posicao e movimento
            "player.pos" -> {
                val g = game(name)
                return mapOf("x" to g.px, "y" to g.py, "z" to g.pz)
            }
            "player.set_pos" -> {
                game(name).scriptTeleport(a.num(0, name), a.num(1, name), a.num(2, name))
                return null
            }
            "player.velocity" -> {
                val v = game(name).scriptVelocity()
                return mapOf("x" to v[0], "y" to v[1], "z" to v[2])
            }
            "player.set_velocity" -> {
                game(name).scriptSetVelocity(a.num(0, name), a.num(1, name), a.num(2, name))
                return null
            }
            "player.look" -> {
                val g = game(name)
                val cp = cos(g.pitch)
                return mapOf(
                    "yaw" to g.yaw, "pitch" to g.pitch,
                    "dx" to -sin(g.yaw) * cp, "dy" to sin(g.pitch), "dz" to -cos(g.yaw) * cp
                )
            }
            "player.target" -> {
                val g = game(name)
                val t = g.scriptTarget() ?: return null
                return mapOf(
                    "x" to t[0].toDouble(), "y" to t[1].toDouble(), "z" to t[2].toDouble(),
                    "block" to Blocks.keyOf(g.world.get(t[0], t[1], t[2])),
                    "nx" to t[3].toDouble(), "ny" to t[4].toDouble(), "nz" to t[5].toDouble()
                )
            }

            // ------------------------------------------------------------ jogador: vida, fome, modo
            "player.health" -> return game(name).health.toDouble()
            "player.set_health" -> {
                game(name).scriptSetHealth(a.num(0, name).toFloat())
                return null
            }
            "player.damage" -> {
                game(name).scriptHurt(a.num(0, name).toFloat())
                return null
            }
            "player.heal" -> {
                game(name).scriptHeal(a.num(0, name).toFloat())
                return null
            }
            "player.food" -> return game(name).food.toDouble()
            "player.set_food" -> {
                game(name).scriptSetFood(a.int(0, name))
                return null
            }
            "player.gamemode" -> return if (game(name).creative) "creative" else "survival"
            "player.set_gamemode" -> {
                val s = a.str(0, name).lowercase()
                val g = game(name)
                if (s.startsWith("c")) g.scriptSetCreative(true)
                else if (s.startsWith("s")) g.scriptSetCreative(false)
                else throw ScriptError("player.set_gamemode: use \"creative\" ou \"survival\"")
                return null
            }
            "player.set_flying" -> {
                game(name).scriptSetFlying(a.boolOr(0, true))
                return null
            }

            // ------------------------------------------------------------ jogador: inventario (slots 0..35, 0..8 = hotbar)
            "player.give" -> {
                val g = game(name)
                val id = itemId(mod, a.str(0, name), name)
                val left = g.input.inv.add(id, a.intOr(1, 1).coerceIn(1, 2304))
                return left.toDouble()
            }
            "player.count" -> {
                val g = game(name)
                return countOf(g.input.inv, itemId(mod, a.str(0, name), name)).toDouble()
            }
            "player.take" -> {
                val g = game(name)
                val id = itemId(mod, a.str(0, name), name)
                val n = a.intOr(1, 1).coerceAtLeast(1)
                val inv = g.input.inv
                if (countOf(inv, id) < n) return false
                var left = n
                val sel = g.input.selected.coerceIn(0, 8)
                // primeiro o slot da mao, depois o resto
                for (k in -1 until 36) {
                    if (left <= 0) break
                    val i = if (k < 0) sel else k
                    if (k >= 0 && i == sel) continue
                    if (inv.id[i] == id && inv.count[i] > 0) {
                        val m = min(left, inv.count[i])
                        inv.take(i, m)
                        left -= m
                    }
                }
                return true
            }
            "player.held" -> {
                val g = game(name)
                val sel = g.input.selected.coerceIn(0, 8)
                val c = g.input.inv.count[sel]
                if (c <= 0) return null
                return mapOf(
                    "item" to Blocks.keyOf(g.input.inv.id[sel]),
                    "count" to c.toDouble(), "slot" to sel.toDouble()
                )
            }
            "player.slot" -> {
                val g = game(name)
                val i = a.int(0, name)
                if (i < 0 || i > 35) throw ScriptError("player.slot: o slot vai de 0 a 35")
                val c = g.input.inv.count[i]
                if (c <= 0) return null
                return mapOf("item" to Blocks.keyOf(g.input.inv.id[i]), "count" to c.toDouble())
            }
            "player.set_slot" -> {
                val g = game(name)
                val i = a.int(0, name)
                if (i < 0 || i > 35) throw ScriptError("player.set_slot: o slot vai de 0 a 35")
                val what = a.getOrNull(1)
                val n = a.intOr(2, 1)
                if (what == null || n <= 0) g.input.inv.set(i, 0, 0)
                else g.input.inv.set(i, itemId(mod, a.str(1, name), name), n.coerceIn(1, 64))
                return null
            }
            "player.selected" -> return game(name).input.selected.coerceIn(0, 8).toDouble()
            "player.message" -> {
                game(name).scriptMessage(a.str(0, name), a.numOr(1, 3.0))
                return null
            }
        }
        throw ScriptError("função desconhecida: $name")
    }
}
