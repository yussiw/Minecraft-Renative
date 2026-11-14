package com.voxelgame

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

/** Funcao Lua guardada (evento, on_use, timer...). */
class LuaCallback(override val mod: ScriptMod, private val eng: LuaEngine, private val fn: LuaValue) : ScriptCallback {
    override fun call(args: List<Any?>): Any? {
        val lua = Array<LuaValue>(args.size) { eng.toLua(args[it], 0) }
        val r = fn.invoke(LuaValue.varargsOf(lua))
        return eng.fromLua(r.arg1(), 0)
    }
}

/**
 * Lua 5.2 (LuaJ). Cada mod ganha um interpretador proprio e isolado:
 * sem io, os.execute, luajava, package, dofile/loadfile e coroutines; os so tem time/clock/date/difftime.
 */
class LuaEngine(private val mod: ScriptMod) : ScriptEngine {
    private val g: Globals = JsePlatform.debugGlobals()
    private val loaded = HashMap<String, LuaValue>()

    init {
        val os = g.get("os")
        val safeOs = LuaTable()
        for (k in arrayOf("time", "clock", "date", "difftime")) safeOs.set(k, os.get(k))
        g.set("os", safeOs)
        for (k in arrayOf("io", "luajava", "package", "dofile", "loadfile", "module", "coroutine")) {
            g.set(k, LuaValue.NIL)
        }

        // limite de tempo: o hook de contagem do debug.sethook roda a cada N instrucoes
        try {
            val sethook = g.get("debug").get("sethook")
            val hook = object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    if (ScriptHost.expired()) throw LuaError("tempo limite do script excedido (loop infinito?)")
                    return LuaValue.NONE
                }
            }
            sethook.invoke(LuaValue.varargsOf(arrayOf<LuaValue>(hook, LuaValue.valueOf(""), LuaValue.valueOf(10000))))
        } catch (t: Throwable) {
            // sem hook o mod ainda funciona, so nao tem protecao contra loop infinito
        }
        g.set("debug", LuaValue.NIL)

        // API do jogo
        for (full in ScriptApi.NAMES) {
            val dot = full.indexOf('.')
            if (dot < 0) {
                g.set(full, ApiFn(full))
                continue
            }
            val tn = full.substring(0, dot)
            var t = g.get(tn)
            if (t.isnil()) {
                t = LuaTable()
                g.set(tn, t)
            }
            t.set(full.substring(dot + 1), ApiFn(full))
        }
        g.set("print", PrintFn())
        g.set("require", RequireFn())
    }

    override fun run(file: String, src: String) {
        val chunk = g.load(src, "@" + file)
        chunk.call()
    }

    // ------------------------------------------------------------ funcoes expostas

    private inner class ApiFn(private val apiName: String) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val n = args.narg()
            val list = ArrayList<Any?>(n)
            for (i in 1..n) list.add(fromLua(args.arg(i), 0))
            val r = try {
                ScriptApi.call(mod, apiName, list)
            } catch (e: ScriptError) {
                throw LuaError(e.message ?: "erro")
            }
            return toLua(r, 0)
        }
    }

    private inner class PrintFn : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val sb = StringBuilder()
            for (i in 1..args.narg()) {
                if (i > 1) sb.append('\t')
                sb.append(args.arg(i).tojstring())
            }
            ScriptHost.log(mod, sb.toString())
            return LuaValue.NONE
        }
    }

    /** require("util") -> util.lua ou util/init.lua dentro do proprio mod. */
    private inner class RequireFn : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val name = arg.checkjstring()
            val cached = loaded[name]
            if (cached != null) return cached
            val base = name.replace('\\', '/').replace('.', '/').lowercase()
            for (p in arrayOf("$base.lua", "$base/init.lua")) {
                val src = mod.files[p] ?: continue
                val f = g.load(src, "@" + p)
                val r = f.call(LuaValue.valueOf(name))
                val v = if (r.isnil()) LuaValue.TRUE else r
                loaded[name] = v
                return v
            }
            throw LuaError("modulo '" + name + "' nao encontrado no mod")
        }
    }

    // ------------------------------------------------------------ conversao Lua <-> Kotlin

    fun fromLua(v: LuaValue, depth: Int): Any? {
        if (depth > 8) return null
        return when (v.type()) {
            LuaValue.TBOOLEAN -> v.toboolean()
            LuaValue.TNUMBER -> v.todouble()
            LuaValue.TSTRING -> v.tojstring()
            LuaValue.TFUNCTION -> LuaCallback(mod, this, v)
            LuaValue.TTABLE -> tableToKotlin(v, depth)
            else -> null
        }
    }

    private fun tableToKotlin(t: LuaValue, depth: Int): Any? {
        val keys = ArrayList<LuaValue>()
        val vals = ArrayList<LuaValue>()
        var k: LuaValue = LuaValue.NIL
        while (true) {
            val n = t.next(k)
            k = n.arg1()
            if (k.isnil()) break
            keys.add(k)
            vals.add(n.arg(2))
            if (keys.size > 20000) break
        }
        var isList = keys.isNotEmpty()
        for (key in keys) {
            if (key.type() != LuaValue.TNUMBER) { isList = false; break }
        }
        if (isList) {
            val n = keys.size
            val arr = arrayOfNulls<Any>(n)
            var ok = true
            for (i in 0 until n) {
                val d = keys[i].todouble()
                val idx = d.toInt()
                if (idx < 1 || idx > n || d != idx.toDouble()) { ok = false; break }
                arr[idx - 1] = fromLua(vals[i], depth + 1)
            }
            if (ok) return arr.toList()
        }
        val m = LinkedHashMap<String, Any?>()
        for (i in keys.indices) {
            val key = keys[i]
            val name = if (key.type() == LuaValue.TNUMBER) {
                val d = key.todouble()
                if (d == Math.rint(d)) d.toLong().toString() else d.toString()
            } else key.tojstring()
            m[name] = fromLua(vals[i], depth + 1)
        }
        return m
    }

    private fun number(d: Double): LuaValue =
        if (d == Math.rint(d) && Math.abs(d) < 2.0e9) LuaValue.valueOf(d.toInt()) else LuaValue.valueOf(d)

    fun toLua(x: Any?, depth: Int): LuaValue {
        if (depth > 8) return LuaValue.NIL
        return when (x) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(x)
            is Double -> number(x)
            is Float -> number(x.toDouble())
            is Int -> LuaValue.valueOf(x)
            is Long -> number(x.toDouble())
            is String -> LuaValue.valueOf(x)
            is Map<*, *> -> {
                val t = LuaTable()
                for ((k, v) in x) t.set(k.toString(), toLua(v, depth + 1))
                t
            }
            is List<*> -> {
                val t = LuaTable()
                for (i in x.indices) t.set(i + 1, toLua(x[i], depth + 1))
                t
            }
            else -> LuaValue.valueOf(x.toString())
        }
    }
}
