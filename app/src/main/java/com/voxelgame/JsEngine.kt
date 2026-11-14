package com.voxelgame

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Function as JsFunction

/** Funcao JS guardada (evento, on_use, timer...). */
class JsCallback(override val mod: ScriptMod, private val eng: JsEngine, private val fn: JsFunction) : ScriptCallback {
    override fun call(args: List<Any?>): Any? = eng.invoke(fn, args)
}

/**
 * JavaScript (Rhino, modo interpretado = unico que roda no Android). Mesma API do Lua.
 * Usa initSafeStandardObjects: sem Packages/java (nao ha acesso a classes Java).
 * Alem de snake_case (world.get_block) aceita camelCase (world.getBlock).
 */
class JsEngine(private val mod: ScriptMod) : ScriptEngine {
    class Timeout : Error("tempo limite do script excedido (loop infinito?)")

    private class Factory : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 10000
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (ScriptHost.expired()) throw Timeout()
        }
    }

    companion object {
        val factory: ContextFactory by lazy { Factory() }
    }

    private val modules = HashMap<String, Any?>()
    private val scope: ScriptableObject = build()

    private fun camel(s: String): String {
        val p = s.split('_')
        return p[0] + p.drop(1).joinToString("") { w -> w.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun build(): ScriptableObject {
        val cx = factory.enterContext()
        try {
            val sc = cx.initSafeStandardObjects()
            for (full in ScriptApi.NAMES) {
                val fn = JsFn(full)
                ScriptRuntime.setFunctionProtoAndParent(fn, sc)
                val dot = full.indexOf('.')
                if (dot < 0) {
                    ScriptableObject.putProperty(sc, full, fn)
                    continue
                }
                val tn = full.substring(0, dot)
                val fname = full.substring(dot + 1)
                val existing = ScriptableObject.getProperty(sc, tn)
                val obj: Scriptable = if (existing is Scriptable) existing else {
                    val o = cx.newObject(sc)
                    ScriptableObject.putProperty(sc, tn, o)
                    o
                }
                ScriptableObject.putProperty(obj, fname, fn)
                val alias = camel(fname)
                if (alias != fname) ScriptableObject.putProperty(obj, alias, fn)
            }
            val console = cx.newObject(sc)
            val logFn = JsFn("log")
            ScriptRuntime.setFunctionProtoAndParent(logFn, sc)
            for (n in arrayOf("log", "info", "warn", "error")) ScriptableObject.putProperty(console, n, logFn)
            ScriptableObject.putProperty(sc, "console", console)
            val req = JsRequire()
            ScriptRuntime.setFunctionProtoAndParent(req, sc)
            ScriptableObject.putProperty(sc, "require", req)
            return sc
        } finally {
            Context.exit()
        }
    }

    override fun run(file: String, src: String) {
        val cx = factory.enterContext()
        try {
            cx.evaluateString(scope, src, file, 1, null)
        } finally {
            Context.exit()
        }
    }

    fun invoke(fn: JsFunction, args: List<Any?>): Any? {
        val cx = factory.enterContext()
        try {
            val js = arrayOfNulls<Any>(args.size)
            for (i in args.indices) js[i] = toJs(cx, scope, args[i], 0)
            return fromJs(fn.call(cx, scope, scope, js), 0)
        } finally {
            Context.exit()
        }
    }

    // ------------------------------------------------------------ funcoes expostas

    private inner class JsFn(private val apiName: String) : BaseFunction() {
        override fun call(cx: Context, sc: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            val list = ArrayList<Any?>(args.size)
            for (a in args) list.add(fromJs(a, 0))
            val r = try {
                ScriptApi.call(mod, apiName, list)
            } catch (e: ScriptError) {
                throw Context.reportRuntimeError(e.message ?: "erro")
            }
            return toJs(cx, sc, r, 0)
        }
    }

    /** require("util") -> util.js (ou util/index.js) dentro do proprio mod; usa module.exports. */
    private inner class JsRequire : BaseFunction() {
        override fun call(cx: Context, sc: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
            val name = Context.toString(if (args.isNotEmpty()) args[0] else Undefined.instance)
            if (modules.containsKey(name)) return modules[name]
            val base = name.replace('\\', '/').removePrefix("./").lowercase()
            val cands = if (base.endsWith(".js")) listOf(base) else listOf("$base.js", "$base/index.js")
            for (p in cands) {
                val src = mod.files[p] ?: continue
                val module = cx.newObject(sc)
                val exports = cx.newObject(sc)
                ScriptableObject.putProperty(module, "exports", exports)
                val wrapped = "(function(module, exports, require){" + src + "\n})"
                val fn = cx.evaluateString(sc, wrapped, p, 1, null) as JsFunction
                fn.call(cx, sc, sc, arrayOf<Any?>(module, exports, this))
                val result = ScriptableObject.getProperty(module, "exports")
                modules[name] = result
                return result
            }
            throw Context.reportRuntimeError("modulo '" + name + "' nao encontrado no mod")
        }
    }

    // ------------------------------------------------------------ conversao JS <-> Kotlin

    fun fromJs(a: Any?, depth: Int): Any? {
        if (depth > 8) return null
        if (a == null || a === Scriptable.NOT_FOUND || Undefined.isUndefined(a)) return null
        return when (a) {
            is Boolean -> a
            is Number -> a.toDouble()
            is CharSequence -> a.toString()
            is JsFunction -> JsCallback(mod, this, a)
            is NativeArray -> {
                val n = a.length.toInt()
                val out = ArrayList<Any?>(n)
                for (i in 0 until n) out.add(fromJs(a.get(i, a), depth + 1))
                out
            }
            is Scriptable -> {
                val m = LinkedHashMap<String, Any?>()
                for (id in a.ids) {
                    if (id is Int) m[id.toString()] = fromJs(a.get(id, a), depth + 1)
                    else {
                        val k = id.toString()
                        m[k] = fromJs(a.get(k, a), depth + 1)
                    }
                }
                m
            }
            else -> a.toString()
        }
    }

    private fun toJs(cx: Context, sc: Scriptable, x: Any?, depth: Int): Any? {
        if (depth > 8) return null
        return when (x) {
            null -> null
            is Boolean -> x
            is Double -> x
            is Float -> x.toDouble()
            is Int -> x.toDouble()
            is Long -> x.toDouble()
            is String -> x
            is Map<*, *> -> {
                val o = cx.newObject(sc)
                for ((k, v) in x) ScriptableObject.putProperty(o, k.toString(), toJs(cx, sc, v, depth + 1))
                o
            }
            is List<*> -> {
                val arr = arrayOfNulls<Any>(x.size)
                for (i in x.indices) arr[i] = toJs(cx, sc, x[i], depth + 1)
                cx.newArray(sc, arr)
            }
            else -> x.toString()
        }
    }
}
