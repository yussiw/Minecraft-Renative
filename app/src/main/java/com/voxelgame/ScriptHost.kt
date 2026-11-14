package com.voxelgame

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Erro "esperado" de um script (argumento invalido, funcao usada na hora errada...). A mensagem vai para o autor do mod. */
class ScriptError(msg: String) : RuntimeException(msg)

/** Funcao de script (Lua ou JS) guardada para ser chamada depois. */
interface ScriptCallback {
    val mod: ScriptMod
    fun call(args: List<Any?>): Any?
}

/** Motor de uma linguagem (um por mod e por linguagem, para que os mods nao compartilhem variaveis). */
interface ScriptEngine {
    fun run(file: String, src: String)
}

/** Estado de execucao de um mod com scripts. */
class ScriptMod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** Textos do pacote (lua, js, json, txt, csv), chaves em minusculas. */
    val files: Map<String, String>
) {
    var lua: ScriptEngine? = null
    var js: ScriptEngine? = null
    @Volatile var halted = false
    @Volatile var lastError: String? = null
    var errors = 0

    private val logs = ArrayList<String>()
    fun addLog(s: String) {
        synchronized(logs) {
            logs.add(s)
            if (logs.size > 40) logs.removeAt(0)
        }
    }
    fun logLines(): List<String> = synchronized(logs) { ArrayList(logs) }

    // dados persistentes (storage.*)
    var data: JSONObject? = null
    var dataDirty = false
}

/** Conversao JSON <-> valores simples (null, Boolean, Double, String, List, Map). */
object ScriptJson {
    fun encode(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, x) in v) {
                if (x is ScriptCallback) continue
                o.put(k.toString(), encode(x))
            }
            o
        }
        is List<*> -> {
            val a = JSONArray()
            for (x in v) {
                if (x is ScriptCallback) continue
                a.put(encode(x))
            }
            a
        }
        is Double -> if (v.isNaN() || v.isInfinite()) 0.0 else v
        is Float -> if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()
        is Number -> v.toDouble()
        is Boolean -> v
        else -> v.toString()
    }

    fun decode(v: Any?): Any? = when (v) {
        null -> null
        JSONObject.NULL -> null
        is JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            val it = v.keys()
            while (it.hasNext()) {
                val k = it.next()
                m[k] = decode(v.opt(k))
            }
            m
        }
        is JSONArray -> {
            val l = ArrayList<Any?>()
            for (i in 0 until v.length()) l.add(decode(v.opt(i)))
            l
        }
        is Number -> v.toDouble()
        is Boolean -> v
        else -> v.toString()
    }
}

/**
 * Executa os scripts dos mods. Tudo roda no thread do jogo (o mesmo do Game.update), entao os scripts
 * podem mexer no mundo e no inventario sem travas.
 *
 * Seguranca: cada mod tem o proprio interpretador, sem acesso a arquivos, rede ou classes Java.
 * Cada chamada tem um limite de tempo; mods que dao erro demais sao pausados.
 */
object ScriptHost {
    private const val MAX_ERRORS = 5
    private const val CALLBACK_BUDGET_MS = 250L
    private const val LOAD_BUDGET_MS = 3000L

    val EVENTS = arrayOf(
        "ready", "tick", "block_break", "block_place", "block_use",
        "item_use", "hurt", "death", "respawn"
    )

    @Volatile var game: Game? = null
    @Volatile var dataDir: File? = null
    @Volatile var active = false
        private set

    private val mods = ArrayList<ScriptMod>()
    private val handlers = HashMap<String, ArrayList<ScriptCallback>>()
    private val timers = ArrayList<Timer>()
    private var nextTimer = 1
    private var depth = 0
    @Volatile private var deadline = 0L
    private var started = false
    private var flushT = 0f

    private class Timer(val id: Int, val mod: ScriptMod, var left: Float, val every: Float, val cb: ScriptCallback) {
        var dead = false
    }

    class Fired(val cancelled: Boolean, val number: Double?)
    private val NOT_FIRED = Fired(false, null)

    @Synchronized
    fun reset() {
        flush()
        mods.clear()
        handlers.clear()
        timers.clear()
        game = null
        started = false
        deadline = 0L
        depth = 0
        nextTimer = 1
        active = false
    }

    fun expired(): Boolean {
        val d = deadline
        return d != 0L && System.nanoTime() > d
    }

    fun has(ev: String): Boolean = active && handlers.containsKey(ev)

    // ------------------------------------------------------------ carregamento

    /** Roda os scripts de um mod (fase de carregamento: registrar blocos, itens, receitas e eventos). */
    fun loadMod(mod: ScriptMod, scripts: List<String>) {
        mods.add(mod)
        active = true
        for (name in scripts) {
            val src = mod.files[name] ?: continue
            val prev = deadline
            deadline = System.nanoTime() + LOAD_BUDGET_MS * 1_000_000L
            depth++
            try {
                val isLua = name.endsWith(".lua")
                if (isLua) {
                    var e = mod.lua
                    if (e == null) { e = LuaEngine(mod); mod.lua = e }
                    e.run(name, src)
                } else {
                    var e = mod.js
                    if (e == null) { e = JsEngine(mod); mod.js = e }
                    e.run(name, src)
                }
            } catch (t: Throwable) {
                fail(mod, t)
            } finally {
                depth--
                deadline = prev
            }
        }
    }

    /** Chamado na primeira atualizacao do jogo: o mundo ja existe, scripts podem usar world.* e player.*. */
    fun start(g: Game) {
        if (started) return
        started = true
        game = g
        if (active) fire("ready", emptyMap())
    }

    // ------------------------------------------------------------ eventos e timers

    fun addHandler(mod: ScriptMod, ev: String, cb: ScriptCallback) {
        if (ev !in EVENTS) throw ScriptError("evento desconhecido '" + ev + "'. Eventos: " + EVENTS.joinToString(", "))
        handlers.getOrPut(ev) { ArrayList() }.add(cb)
    }

    fun addTimer(mod: ScriptMod, seconds: Double, repeat: Boolean, cb: ScriptCallback): Int {
        val s = seconds.toFloat().coerceAtLeast(0.05f)
        val t = Timer(nextTimer++, mod, s, if (repeat) s else 0f, cb)
        timers.add(t)
        return t.id
    }

    fun cancelTimer(id: Int) {
        for (t in timers) if (t.id == id) t.dead = true
    }

    /** Chama uma funcao de script com limite de tempo; erros ficam registrados no mod. */
    fun invoke(cb: ScriptCallback, args: List<Any?>): Any? {
        val mod = cb.mod
        if (mod.halted) return null
        val prev = deadline
        depth++
        deadline = System.nanoTime() + CALLBACK_BUDGET_MS * 1_000_000L
        try {
            return cb.call(args)
        } catch (t: Throwable) {
            fail(mod, t)
            return null
        } finally {
            depth--
            deadline = prev
        }
    }

    /** Dispara um evento. `cancelled` = algum script retornou false; `number` = ultimo numero retornado. */
    fun fire(ev: String, payload: Map<String, Any?>): Fired {
        if (depth > 0) return NOT_FIRED
        val hs = handlers[ev] ?: return NOT_FIRED
        var cancelled = false
        var num: Double? = null
        val n = hs.size
        for (i in 0 until n) {
            val r = invoke(hs[i], listOf(payload))
            if (r == false) cancelled = true
            else if (r is Double) num = r
        }
        return Fired(cancelled, num)
    }

    /** A cada quadro (jogo nao pausado). */
    fun tick(dt: Float) {
        if (!active || depth > 0) return
        val n = timers.size
        for (i in 0 until n) {
            val t = timers[i]
            if (t.dead || t.mod.halted) continue
            t.left -= dt
            if (t.left <= 0f) {
                if (t.every > 0f) t.left += t.every else t.dead = true
                invoke(t.cb, emptyList())
            }
        }
        if (n > 0) timers.removeAll { it.dead }
        val hs = handlers["tick"]
        if (hs != null) {
            val arg = listOf<Any?>(dt.toDouble())
            for (i in 0 until hs.size) invoke(hs[i], arg)
        }
        flushT += dt
        if (flushT >= 5f) { flushT = 0f; flush() }
    }

    // ------------------------------------------------------------ ganchos usados pelo Game

    private fun blockEvent(id: Int, x: Int, y: Int, z: Int): Map<String, Any?> = mapOf(
        "x" to x.toDouble(), "y" to y.toDouble(), "z" to z.toDouble(), "block" to Blocks.keyOf(id)
    )

    /** false = algum script cancelou a quebra. */
    fun blockBreak(id: Int, x: Int, y: Int, z: Int): Boolean {
        if (!active || depth > 0) return true
        val cb = Blocks.defs[id]?.onBreak
        if (cb == null && !handlers.containsKey("block_break")) return true
        val ev = blockEvent(id, x, y, z)
        if (cb != null && invoke(cb, listOf(ev)) == false) return false
        if (handlers.containsKey("block_break") && fire("block_break", ev).cancelled) return false
        return true
    }

    /** false = algum script cancelou a colocacao. */
    fun blockPlace(id: Int, x: Int, y: Int, z: Int): Boolean {
        if (!active || depth > 0) return true
        val cb = Blocks.defs[id]?.onPlace
        if (cb == null && !handlers.containsKey("block_place")) return true
        val ev = blockEvent(id, x, y, z)
        if (cb != null && invoke(cb, listOf(ev)) == false) return false
        if (handlers.containsKey("block_place") && fire("block_place", ev).cancelled) return false
        return true
    }

    /** true = o clique foi tratado por script (o jogo nao deve colocar bloco nem usar o bloco). */
    fun blockUse(id: Int, x: Int, y: Int, z: Int): Boolean {
        if (!active || depth > 0) return false
        val cb = Blocks.defs[id]?.onUse
        if (cb == null && !handlers.containsKey("block_use")) return false
        val ev = blockEvent(id, x, y, z)
        if (handlers.containsKey("block_use") && fire("block_use", ev).cancelled) return true
        if (cb != null) { invoke(cb, listOf(ev)); return true }
        return false
    }

    /** false = algum script cancelou o uso do item. */
    fun itemUse(item: Int, slot: Int, count: Int): Boolean {
        if (!active || depth > 0) return true
        val cb = Blocks.items[item]?.onUse
        if (cb == null && !handlers.containsKey("item_use")) return true
        val ev = mapOf<String, Any?>("item" to Blocks.keyOf(item), "slot" to slot.toDouble(), "count" to count.toDouble())
        if (cb != null && invoke(cb, listOf(ev)) == false) return false
        if (handlers.containsKey("item_use") && fire("item_use", ev).cancelled) return false
        return true
    }

    /** Devolve o dano final (<= 0 = cancelado). */
    fun hurt(amount: Float, health: Float): Float {
        if (!active || depth > 0 || !handlers.containsKey("hurt")) return amount
        val f = fire("hurt", mapOf("amount" to amount.toDouble(), "health" to health.toDouble()))
        if (f.cancelled) return 0f
        return f.number?.toFloat() ?: amount
    }

    fun simple(ev: String) {
        if (active && handlers.containsKey(ev)) fire(ev, emptyMap())
    }

    // ------------------------------------------------------------ erros e log

    fun log(mod: ScriptMod, msg: String) {
        Log.i("Blocos", "[" + mod.id + "] " + msg)
        mod.addLog(msg)
    }

    fun fail(mod: ScriptMod, t: Throwable) {
        var msg = t.message ?: t.javaClass.simpleName
        msg = msg.replace('\n', ' ').trim()
        if (msg.length > 220) msg = msg.substring(0, 220) + "..."
        mod.lastError = msg
        mod.errors++
        mod.addLog("ERRO: " + msg)
        Log.w("Blocos", "[" + mod.id + "] erro: " + msg)
        if (mod.errors >= MAX_ERRORS && !mod.halted) {
            mod.halted = true
            mod.addLog("Mod pausado por excesso de erros.")
            Stats.message = "Mod '" + mod.name + "' pausado por erros (veja em Mods)"
            Stats.messageUntil = System.currentTimeMillis() + 4000L
        }
    }

    // ------------------------------------------------------------ dados persistentes

    private fun dataFile(mod: ScriptMod): File? {
        val d = dataDir ?: return null
        return File(d, mod.id + ".json")
    }

    fun storage(mod: ScriptMod): JSONObject {
        var o = mod.data
        if (o == null) {
            o = JSONObject()
            try {
                val f = dataFile(mod)
                if (f != null && f.exists()) o = JSONObject(f.readText())
            } catch (t: Throwable) { o = JSONObject() }
            mod.data = o
        }
        return o!!
    }

    fun flush() {
        for (m in mods) {
            if (!m.dataDirty) continue
            m.dataDirty = false
            try {
                val f = dataFile(m) ?: continue
                f.parentFile?.mkdirs()
                f.writeText((m.data ?: JSONObject()).toString())
            } catch (t: Throwable) { }
        }
    }
}
