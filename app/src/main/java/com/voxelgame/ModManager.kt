package com.voxelgame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Mods. Formatos aceitos (em mods/): .zip / .jar, ou um .lua / .js solto.
 *
 * Um mod pode combinar:
 *  1) Scripts: mod.json + main.lua (e/ou main.js). Veja ScriptApi / MODDING.md.
 *  2) Assets de mods Java (Forge/NeoForge/Fabric/Quilt): blockstates + modelos de cubo, itens
 *     "generated/handheld", lang e receitas vanilla.
 *  3) JSON de comportamento: data/<ns>/blocks/ e data/<ns>/items/ (arquivos .json).
 * O codigo Java (.class) NAO e executado.
 *
 * Mods podem ser ativados/desativados e excluidos; as mudancas valem apos reiniciar o jogo (recreate).
 */
object ModManager {
    class ModInfo(
        val file: String, val id: String, val name: String, val version: String,
        val author: String, val description: String, val icon: Bitmap?, val hasJavaCode: Boolean
    ) {
        /** Estado desejado (salvo em mods_state.json). */
        @Volatile var enabled = true
        /** Estado com que o jogo foi carregado. */
        @Volatile var loadedEnabled = false
        var blocks = 0
        var items = 0
        var recipes = 0
        var skipped = 0
        val langs = ArrayList<String>()
        var scriptMod: ScriptMod? = null
        var error: String? = null

        val pending: Boolean get() = enabled != loadedEnabled
        val currentError: String? get() = scriptMod?.lastError ?: error
    }

    @Volatile var mods: List<ModInfo> = emptyList()
        private set
    @Volatile private var changed = false

    fun dir(filesDir: File) = File(filesDir, "mods")

    /** Ha mudancas (ativar/desativar/excluir) esperando o jogo reiniciar? */
    fun needsReload(): Boolean = changed || mods.any { it.pending }

    // ---------------------------------------------------------------- estado em disco

    private fun readDisabled(filesDir: File): MutableSet<String> {
        val out = HashSet<String>()
        try {
            val f = File(filesDir, "mods_state.json")
            if (f.exists()) {
                val arr = JSONObject(f.readText()).optJSONArray("disabled")
                if (arr != null) for (i in 0 until arr.length()) out.add(arr.optString(i))
            }
        } catch (t: Throwable) { }
        return out
    }

    private fun writeDisabled(filesDir: File, set: Set<String>) {
        try {
            val arr = JSONArray()
            for (s in set.sorted()) arr.put(s)
            val j = JSONObject()
            j.put("disabled", arr)
            File(filesDir, "mods_state.json").writeText(j.toString())
        } catch (t: Throwable) { }
    }

    fun setEnabled(filesDir: File, m: ModInfo, on: Boolean) {
        m.enabled = on
        val set = readDisabled(filesDir)
        if (on) set.remove(m.file) else set.add(m.file)
        writeDisabled(filesDir, set)
    }

    /** Apaga o arquivo do mod (e os dados salvos dele). Os ids de blocos ficam reservados para nao quebrar o mundo. */
    fun delete(filesDir: File, m: ModInfo) {
        try { File(dir(filesDir), m.file).delete() } catch (t: Throwable) { }
        try { File(File(filesDir, "mods_data"), m.id + ".json").delete() } catch (t: Throwable) { }
        val set = readDisabled(filesDir)
        if (set.remove(m.file)) writeDisabled(filesDir, set)
        mods = mods.filter { it !== m }
        changed = true
    }

    /** null = arquivo valido; senao a mensagem de erro. */
    fun validate(f: File): String? {
        val n = f.name.lowercase()
        return try {
            if (n.endsWith(".lua") || n.endsWith(".js")) {
                if (f.length() > 1024 * 1024) "Script grande demais (máx. 1 MB)"
                else if (String(f.readBytes(), Charsets.UTF_8).isBlank()) "Script vazio"
                else null
            } else {
                ZipFile(f).use { }
                null
            }
        } catch (t: Throwable) {
            "Não é um .zip/.jar válido"
        }
    }

    // ---------------------------------------------------------------- fonte dos arquivos do mod

    private interface Source : Closeable {
        fun names(): List<String>
        fun read(name: String): ByteArray?
    }

    private class ZipSource(val zf: ZipFile) : Source {
        private val map = LinkedHashMap<String, ZipEntry>()

        init {
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                map[e.name.replace('\\', '/')] = e
            }
        }

        override fun names(): List<String> = map.keys.toList()

        override fun read(name: String): ByteArray? {
            val e = map[name] ?: return null
            if (e.size > 64L * 1024 * 1024) return null
            return zf.getInputStream(e).use { it.readBytes() }
        }

        override fun close() { zf.close() }
    }

    /** Um .lua ou .js solto vira um mod de um arquivo so (main.lua / main.js). */
    private class LooseSource(val f: File) : Source {
        private val ext = f.extension.lowercase()
        override fun names(): List<String> = listOf("main.$ext")
        override fun read(name: String): ByteArray? = if (name == "main.$ext") f.readBytes() else null
        override fun close() { }
    }

    private fun open(f: File): Source {
        val n = f.name.lowercase()
        return if (n.endsWith(".lua") || n.endsWith(".js")) LooseSource(f) else ZipSource(ZipFile(f))
    }

    // ---------------------------------------------------------------- utilitarios

    private fun sanitizeId(s: String): String {
        val r = s.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        return r.ifEmpty { "mod" }
    }

    private fun pretty(name: String): String =
        name.substringAfterLast(':').substringAfterLast('/').split('_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }

    private fun str(o: JSONObject?, k: String): String? {
        val v = o?.optString(k, "")?.trim() ?: return null
        return if (v.isEmpty() || v == "null") null else v
    }

    private fun authorOf(o: JSONObject): String? {
        str(o, "author")?.let { return it }
        val arr = o.optJSONArray("authors") ?: return null
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) names.add(v)
            else if (v is JSONObject) str(v, "name")?.let { names.add(it) }
        }
        return names.joinToString(", ").ifEmpty { null }
    }

    private fun iconField(v: Any?): String? {
        if (v is String) return v
        if (v is JSONObject) {
            val keys = ArrayList<String>()
            val it = v.keys()
            while (it.hasNext()) keys.add(it.next())
            val best = keys.maxByOrNull { k -> k.toIntOrNull() ?: 0 } ?: return null
            return v.optString(best).ifEmpty { null }
        }
        return null
    }

    private fun decodeIcon(b: ByteArray): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= 128) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeByteArray(b, 0, b.size, o2)
        } catch (t: Throwable) { null }
    }

    /** Converte "mod:nome" ou "nome" na chave "namespace:nome". */
    private fun qualify(ns: String, raw: String): String {
        val k = raw.trim().lowercase().replace(' ', '_')
        if (k.isEmpty() || !Regex("^[a-z0-9_./:-]+$").matches(k)) throw ScriptError("id invalido: '$raw' (use letras minusculas, numeros e _)")
        return if (k.contains(':')) k else "$ns:$k"
    }

    // ---------------------------------------------------------------- estado do carregamento atual

    private class Cand(
        val key: String, val name: String, val top: String?, val side: String?, val bottom: String?,
        val def: BlockDef?, val wg: JSONObject?
    )

    private class ItemCand(val key: String, val name: String, val tex: String?, val j: JSONObject?)

    private class QRecipe(val info: ModInfo, val ns: String, val j: JSONObject)

    private class LoadState(val reg: HashMap<String, Int>) {
        lateinit var info: ModInfo
        lateinit var r: Reader
        var ns = ""
        var sm: ScriptMod? = null
        val blockIds = ArrayList<Int>()
        val itemIds = ArrayList<Int>()
        val ores = ArrayList<Ore>()
        val recipes = ArrayList<QRecipe>()

        fun alloc(key: String, lo: Int, hi: Int): Int? {
            val old = reg[key]
            if (old != null && old in lo..hi) return old
            val free = (lo..hi).firstOrNull { x -> !reg.containsValue(x) }
            if (free != null) reg[key] = free
            return free
        }
    }

    @Volatile private var cur: LoadState? = null

    // ---------------------------------------------------------------- carregar tudo

    @Synchronized
    fun loadAll(filesDir: File) {
        ScriptHost.reset()
        ScriptHost.dataDir = File(filesDir, "mods_data")
        Atlas.clearMods()
        Blocks.resetMods()
        Recipes.reset()
        changed = false

        val regFile = File(filesDir, "mods_ids.json")
        val reg = HashMap<String, Int>()
        try {
            if (regFile.exists()) {
                val j = JSONObject(regFile.readText())
                val it = j.keys()
                while (it.hasNext()) { val k = it.next(); reg[k] = j.getInt(k) }
            }
        } catch (t: Throwable) { reg.clear() }

        val disabled = readDisabled(filesDir)
        val files = dir(filesDir).listFiles { f ->
            val n = f.name.lowercase()
            f.isFile && (n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".lua") || n.endsWith(".js"))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

        val infos = ArrayList<ModInfo>()
        val st = LoadState(reg)
        cur = st
        try {
            // passo 1: metadados de todos; conteudo so dos ativados
            for (f in files) {
                var info: ModInfo? = null
                try {
                    open(f).use { src ->
                        val r = Reader(src)
                        val m = r.meta(f.name)
                        val inf = ModInfo(f.name, m.id, m.name, m.version, m.author, m.description, r.icon(m.icon), r.hasCode)
                        inf.enabled = f.name !in disabled
                        inf.loadedEnabled = inf.enabled
                        info = inf
                        infos.add(inf)
                        if (!inf.enabled) return@use

                        st.info = inf
                        st.r = r
                        st.ns = m.id
                        var skipped = r.skipped
                        for (c in r.blocks()) if (addBlock(st, c) < 0) skipped++
                        for (ic in r.items()) if (addItem(st, ic) < 0) skipped++
                        inf.skipped += skipped

                        val scripts = r.scriptList()
                        if (scripts.isNotEmpty()) {
                            val sm = ScriptMod(m.id, m.name, m.version, m.author, m.description, r.textFiles())
                            inf.scriptMod = sm
                            for (s in scripts) {
                                val l = if (s.endsWith(".lua")) "Lua" else "JS"
                                if (l !in inf.langs) inf.langs.add(l)
                            }
                            st.sm = sm
                            ScriptHost.loadMod(sm, scripts)
                            st.sm = null
                        }
                    }
                } catch (t: Throwable) {
                    val inf = info
                    if (inf != null) {
                        inf.error = "Falha ao carregar: " + (t.message ?: t.javaClass.simpleName)
                    } else {
                        val bad = ModInfo(f.name, sanitizeId(f.nameWithoutExtension), f.name, "", "", "Arquivo inválido ou corrompido.", null, false)
                        bad.enabled = f.name !in disabled
                        bad.loadedEnabled = bad.enabled
                        bad.error = "Não foi possível abrir este arquivo"
                        infos.add(bad)
                    }
                }
            }

            Blocks.ores = st.ores
            Blocks.finishMods(st.blockIds, st.itemIds)

            // passo 2: receitas (precisam de todos os ids ja registrados)
            val byFile = infos.associateBy { it.file }
            for (f in files) {
                val inf = byFile[f.name] ?: continue
                if (!inf.loadedEnabled) continue
                try {
                    open(f).use { src ->
                        val res = Reader(src).addRecipes()
                        inf.recipes += res.first
                        inf.skipped += res.second
                    }
                } catch (t: Throwable) { }
            }
            for (q in st.recipes) {
                val ok = try { addRecipeJson(q.j, q.ns) } catch (t: Throwable) { false }
                if (ok == true) q.info.recipes++ else if (ok == false) q.info.skipped++
            }
        } finally {
            cur = null
        }

        try {
            val j = JSONObject()
            for ((k, v) in reg) j.put(k, v)
            regFile.writeText(j.toString())
        } catch (t: Throwable) { }
        mods = infos
    }

    // ---------------------------------------------------------------- registrar blocos e itens

    /** Devolve o id, -1 (sem id livre) ou -2 (atlas de texturas cheio). */
    private fun addBlock(st: LoadState, c: Cand): Int {
        val id = st.alloc(c.key, Blocks.MOD_FIRST, Blocks.MOD_LAST) ?: return -1
        val tTop = tile(st.r, c.top ?: c.side, false, st.ns)
        val tSide = tile(st.r, c.side ?: c.top, false, st.ns)
        val tBot = tile(st.r, c.bottom ?: c.top ?: c.side, false, st.ns)
        if (tTop < 0 || tSide < 0 || tBot < 0) return -2
        Blocks.registerMod(id, c.name, tTop, tSide, tBot)
        Blocks.bind(c.key, id)
        val d = c.def
        if (d != null) Blocks.registerDef(id, c.key, d)
        val wg = c.wg
        if (wg != null) {
            st.ores.add(Ore(
                id, wg.optInt("min_y", 4).coerceIn(1, H - 2), wg.optInt("max_y", 48).coerceIn(2, H - 1),
                wg.optInt("tries", 4).coerceIn(1, 40), wg.optInt("vein", 4).coerceIn(1, 16)
            ))
        }
        st.blockIds.add(id)
        st.info.blocks++
        return id
    }

    private fun addItem(st: LoadState, ic: ItemCand): Int {
        val id = st.alloc("item:" + ic.key, Blocks.ITEM_FIRST, Blocks.ITEM_LAST) ?: return -1
        val tl = tile(st.r, ic.tex, true, st.ns)
        if (tl < 0) return -2
        val j = ic.j
        val food = j?.optJSONObject("food")
        val tool = j?.optJSONObject("tool")
        Blocks.registerItem(ItemDef(
            id, ic.key, ic.name, tl,
            food?.optInt("hunger", 0) ?: 0,
            food?.optDouble("saturation", 0.0)?.toFloat() ?: 0f,
            j?.optDouble("heal", 0.0)?.toFloat() ?: 0f,
            tool?.optString("type")?.ifEmpty { null },
            tool?.optDouble("speed", 1.0)?.toFloat() ?: 1f,
            tool?.optDouble("attack", 1.0)?.toFloat() ?: 1f
        ))
        st.itemIds.add(id)
        st.info.items++
        return id
    }

    private fun failText(code: Int, what: String): String =
        if (code == -1) "limite de $what de mods atingido"
        else "o atlas de texturas está cheio (máx. 44 texturas de mods no total)"

    /** blocks.register(id, {...}) chamado por um script durante o carregamento. */
    fun scriptBlock(sm: ScriptMod, rawId: String, def: Map<String, Any?>) {
        val st = cur
        if (st == null || st.sm !== sm) throw ScriptError("blocks.register só funciona enquanto o mod carrega (no nível principal do script, fora de funções de evento)")
        val key = qualify(st.ns, rawId)
        if (Blocks.keyToId.containsKey(key)) throw ScriptError("já existe algo registrado com o id '$key'")
        val j = ScriptJson.encode(def) as JSONObject
        val tj = j.optJSONObject("textures")
        val all = j.optString("texture").ifEmpty { null }
        val top = tj?.optString("top")?.ifEmpty { null } ?: all
        val side = tj?.optString("side")?.ifEmpty { null } ?: all ?: top
        val bottom = tj?.optString("bottom")?.ifEmpty { null } ?: all ?: top
        val short = rawId.substringAfter(':')
        val name = j.optString("name").ifEmpty { pretty(rawId) }
        val c = Cand(
            key, name, top ?: side ?: short, side ?: top ?: short, bottom ?: top ?: side ?: short,
            parseDef(st.ns, j), j.optJSONObject("worldgen")
        )
        val id = addBlock(st, c)
        if (id < 0) throw ScriptError("não foi possível registrar '$key': " + failText(id, "blocos"))
        val d = Blocks.defs[id]
        if (d != null) {
            d.onUse = def["on_use"] as? ScriptCallback
            d.onBreak = def["on_break"] as? ScriptCallback
            d.onPlace = def["on_place"] as? ScriptCallback
        }
    }

    /** items.register(id, {...}) */
    fun scriptItem(sm: ScriptMod, rawId: String, def: Map<String, Any?>) {
        val st = cur
        if (st == null || st.sm !== sm) throw ScriptError("items.register só funciona enquanto o mod carrega (no nível principal do script, fora de funções de evento)")
        val key = qualify(st.ns, rawId)
        if (Blocks.keyToId.containsKey(key)) throw ScriptError("já existe algo registrado com o id '$key'")
        val j = ScriptJson.encode(def) as JSONObject
        val name = j.optString("name").ifEmpty { pretty(rawId) }
        val tex = j.optString("texture").ifEmpty { rawId.substringAfter(':') }
        val id = addItem(st, ItemCand(key, name, tex, j))
        if (id < 0) throw ScriptError("não foi possível registrar '$key': " + failText(id, "itens"))
        Blocks.items[id]?.onUse = def["on_use"] as? ScriptCallback
    }

    /** recipes.add({...}) no formato de receita do Minecraft. */
    fun scriptRecipe(sm: ScriptMod, def: Map<String, Any?>) {
        val st = cur
        if (st == null || st.sm !== sm) throw ScriptError("recipes.* só funciona enquanto o mod carrega (no nível principal do script)")
        st.recipes.add(QRecipe(st.info, st.ns, ScriptJson.encode(def) as JSONObject))
    }

    /** "ns:caminho" -> tile do atlas (le o PNG do proprio mod; o pack pode trocar depois). */
    private fun tile(r: Reader, ref: String?, keepAlpha: Boolean, defNs: String): Int {
        if (ref == null) return -1
        val ns = if (ref.contains(':')) ref.substringBefore(':') else defNs
        val path = ref.substringAfter(':')
        val bytes = r.texBytes(ns, path)
        val tex = if (bytes != null) try { Atlas.decodeTex(ByteArrayInputStream(bytes)) } catch (t: Throwable) { null } else null
        return Atlas.allocModTile("$ns:$path".lowercase(), tex, keepAlpha)
    }

    // ---------------------------------------------------------------- JSON de comportamento e receitas

    private fun ref(ns: String, v: String): String = if (v.contains(':')) v.lowercase() else "$ns:$v".lowercase()

    private fun key(ns: String, v: String): String =
        if (v.isEmpty() || v == "none" || v.contains(':')) v.lowercase() else "$ns:$v".lowercase()

    private fun parseUse(ns: String, o: JSONObject): UseAction = UseAction(
        give = o.optString("give").ifEmpty { null }?.let { key(ns, it) },
        giveCount = o.optInt("count", 1).coerceIn(1, 64),
        heal = o.optDouble("heal", 0.0).toFloat(),
        feed = o.optInt("feed", 0),
        message = o.optString("message").ifEmpty { null },
        consume = o.optBoolean("consume", false),
        replace = o.optString("replace").ifEmpty { null }?.let { key(ns, it) },
        cooldown = o.optDouble("cooldown", 0.5).toFloat()
    )

    private fun parseDef(ns: String, j: JSONObject): BlockDef = BlockDef(
        hardness = j.optDouble("hardness", 1.0).toFloat(),
        light = j.optInt("light", 0).coerceIn(0, 15),
        damage = j.optDouble("damage", 0.0).toFloat(),
        tool = j.optString("tool").ifEmpty { null },
        dropKey = if (j.has("drop")) key(ns, j.optString("drop")) else null,
        dropCount = j.optInt("drop_count", 1).coerceIn(0, 64),
        use = j.optJSONObject("on_use")?.let { parseUse(ns, it) }
    )

    private fun recipeId(s: String, defNs: String?): Int {
        if (defNs != null && !s.contains(':')) {
            val own = Blocks.keyToId[defNs + ":" + s.lowercase()]
            if (own != null) return own
        }
        return Blocks.idOf(s)
    }

    private fun one(s: String, defNs: String?): IntArray? {
        if (s.startsWith("#")) return Recipes.tag(s)
        val id = recipeId(s, defNs)
        return if (id > 0) intArrayOf(id) else null
    }

    private fun ingredient(o: Any?, defNs: String?): IntArray? = when (o) {
        is String -> one(o, defNs)
        is JSONObject -> o.optString("item").ifEmpty { null }?.let { one(it, defNs) }
            ?: o.optString("tag").ifEmpty { null }?.let { Recipes.tag(it) }
        is JSONArray -> {
            val ids = ArrayList<Int>()
            for (i in 0 until o.length()) ingredient(o.opt(i), defNs)?.forEach { ids.add(it) }
            if (ids.isEmpty()) null else ids.toIntArray()
        }
        else -> null
    }

    private fun recipeResult(o: Any?, defNs: String?): Pair<Int, Int>? = when (o) {
        is String -> recipeId(o, defNs).takeIf { it > 0 }?.let { Pair(it, 1) }
        is JSONObject -> {
            val s = o.optString("item").ifEmpty { o.optString("id") }
            recipeId(s, defNs).takeIf { it > 0 }?.let { Pair(it, o.optInt("count", 1).coerceIn(1, 64)) }
        }
        else -> null
    }

    /** true = adicionada, false = invalida, null = tipo de receita ignorado. */
    private fun addRecipeJson(j: JSONObject, defNs: String?): Boolean? {
        val type = j.optString("type").removePrefix("minecraft:")
        if (type != "crafting_shaped" && type != "crafting_shapeless") return null
        val res = recipeResult(j.opt("result"), defNs) ?: return false
        if (type == "crafting_shaped") {
            val pat = j.optJSONArray("pattern")
            val keyObj = j.optJSONObject("key")
            if (pat == null || keyObj == null || pat.length() == 0 || pat.length() > 3) return false
            val rows = (0 until pat.length()).map { pat.optString(it) }
            val w = rows.maxOf { it.length }
            val h = rows.size
            if (w == 0 || w > 3) return false
            val grid = arrayOfNulls<IntArray>(w * h)
            var good = true
            for (y in 0 until h) for (x in 0 until w) {
                val ch = if (x < rows[y].length) rows[y][x] else ' '
                if (ch == ' ') continue
                val ing = ingredient(keyObj.opt(ch.toString()), defNs)
                if (ing == null) good = false else grid[y * w + x] = ing
            }
            if (!good) return false
            Recipes.add(Recipe(true, w, h, grid, emptyList(), res.first, res.second))
            return true
        }
        val arr = j.optJSONArray("ingredients")
        if (arr == null || arr.length() == 0 || arr.length() > 9) return false
        val list = ArrayList<IntArray>()
        var good = true
        for (i in 0 until arr.length()) {
            val ing = ingredient(arr.opt(i), defNs)
            if (ing == null) good = false else list.add(ing)
        }
        if (!good) return false
        Recipes.add(Recipe(false, 0, 0, emptyArray(), list, res.first, res.second))
        return true
    }

    // ---------------------------------------------------------------- leitura do pacote

    private class Meta(
        val id: String, val name: String, val version: String,
        val author: String, val description: String, val icon: String?
    )

    private class Reader(val src: Source) {
        /** caminho minusculo relativo ao mod -> nome real na fonte */
        private val entries = HashMap<String, String>()
        var hasCode = false
        var skipped = 0

        init {
            val names = src.names()
            var root = ""
            if (names.none { it.equals("mod.json", true) }) {
                val m = names.firstOrNull { Regex("^[^/]+/mod\\.json$", RegexOption.IGNORE_CASE).matches(it) }
                if (m != null) root = m.substring(0, m.indexOf('/') + 1)
            }
            for (n in names) {
                if (n.endsWith(".class")) hasCode = true
                val rel = if (root.isNotEmpty() && n.startsWith(root, true)) n.substring(root.length) else n
                entries[rel.lowercase()] = n
            }
        }

        fun has(path: String): Boolean = entries.containsKey(path.lowercase())

        fun bytes(path: String): ByteArray? {
            val n = entries[path.lowercase()] ?: return null
            return try { src.read(n) } catch (t: Throwable) { null }
        }

        fun text(path: String): String? = bytes(path)?.let { String(it, Charsets.UTF_8).removePrefix("\uFEFF") }

        fun json(path: String): JSONObject? {
            val t = text(path) ?: return null
            return try { JSONObject(t) } catch (t2: Throwable) { null }
        }

        /** Procura o PNG em varios lugares: assets/<ns>/textures/..., textures/..., e pastas block(s)/item(s). */
        fun texBytes(ns: String, path: String): ByteArray? {
            val p = path.removeSuffix(".png").lowercase()
            val cands = ArrayList<String>()
            cands.add("assets/$ns/textures/$p.png")
            cands.add("textures/$p.png")
            cands.add("$p.png")
            if (!p.contains('/')) {
                for (d in arrayOf("block", "blocks", "item", "items")) {
                    cands.add("textures/$d/$p.png")
                    cands.add("assets/$ns/textures/$d/$p.png")
                }
            }
            for (c in cands) bytes(c)?.let { return it }
            return null
        }

        fun icon(hint: String?): Bitmap? {
            val cands = ArrayList<String>()
            if (hint != null) cands.add(hint)
            cands.add("icon.png"); cands.add("logo.png"); cands.add("pack.png")
            for (c in cands) {
                val b = bytes(c) ?: continue
                val bmp = decodeIcon(b)
                if (bmp != null) return bmp
            }
            return null
        }

        fun meta(fileName: String): Meta {
            val base = fileName.substringBeforeLast('.')
            var id = sanitizeId(base)
            var name = pretty(base).ifEmpty { base }
            var version = ""
            var author = ""
            var desc = ""
            var icon: String? = null
            var named = false

            // script solto: metadados em comentarios (--@name Meu Mod  /  //@name Meu Mod)
            if (src is LooseSource) {
                val head = text("main.lua") ?: text("main.js") ?: ""
                val re = Regex("^\\s*(?:--|//)\\s*@(\\w+)\\s*(.*)$")
                var n = 0
                for (line in head.lineSequence()) {
                    if (++n > 40) break
                    val m = re.find(line) ?: continue
                    val v = m.groupValues[2].trim()
                    if (v.isEmpty()) continue
                    when (m.groupValues[1].lowercase()) {
                        "id" -> id = sanitizeId(v)
                        "name" -> { name = v; named = true }
                        "version" -> version = v
                        "author" -> author = v
                        "description", "desc" -> desc = if (desc.isEmpty()) v else "$desc $v"
                    }
                }
            }

            val mj = json("mod.json")
            if (mj != null) {
                str(mj, "id")?.let { id = sanitizeId(it) }
                str(mj, "name")?.let { name = it; named = true }
                str(mj, "version")?.let { version = it }
                authorOf(mj)?.let { author = it }
                str(mj, "description")?.let { desc = it }
                str(mj, "icon")?.let { icon = it }
            }

            if (!named) {
                val fj = json("fabric.mod.json")
                if (fj != null) {
                    str(fj, "name")?.let { name = it; named = true }
                    if (version.isEmpty()) str(fj, "version")?.let { version = it }
                    if (author.isEmpty()) authorOf(fj)?.let { author = it }
                    if (desc.isEmpty()) str(fj, "description")?.let { desc = it }
                    if (icon == null) icon = iconField(fj.opt("icon"))
                }
                val qm = json("quilt.mod.json")?.optJSONObject("quilt_loader")?.optJSONObject("metadata")
                if (qm != null) {
                    str(qm, "name")?.let { name = it; named = true }
                    if (author.isEmpty()) {
                        val contrib = qm.optJSONObject("contributors")
                        if (contrib != null) {
                            val names = ArrayList<String>()
                            val ks = contrib.keys()
                            while (ks.hasNext()) names.add(ks.next())
                            author = names.joinToString(", ")
                        }
                    }
                    if (desc.isEmpty()) str(qm, "description")?.let { desc = it }
                    if (icon == null) icon = iconField(qm.opt("icon"))
                }
                for (tp in arrayOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
                    val txt = text(tp) ?: continue
                    Regex("displayName\\s*=\\s*\"([^\"]+)\"").find(txt)?.let { name = it.groupValues[1]; named = true }
                    if (version.isEmpty()) Regex("(?m)^\\s*version\\s*=\\s*\"([^\"$]+)\"").find(txt)?.let { version = it.groupValues[1] }
                    if (author.isEmpty()) Regex("authors\\s*=\\s*\"([^\"]+)\"").find(txt)?.let { author = it.groupValues[1] }
                    if (desc.isEmpty()) {
                        val m3 = Regex("description\\s*=\\s*'''(.*?)'''", RegexOption.DOT_MATCHES_ALL).find(txt)
                            ?: Regex("description\\s*=\\s*\"([^\"]*)\"").find(txt)
                        if (m3 != null) desc = m3.groupValues[1].trim()
                    }
                    if (icon == null) Regex("logoFile\\s*=\\s*\"([^\"]+)\"").find(txt)?.let { icon = it.groupValues[1] }
                    break
                }
            }
            return Meta(id, name, version, author, desc.trim(), icon)
        }

        /** Scripts a executar, em ordem (minusculos). */
        fun scriptList(): List<String> {
            val out = ArrayList<String>()
            val j = json("mod.json")
            val arr = j?.optJSONArray("scripts")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim().lowercase()
                    if ((p.endsWith(".lua") || p.endsWith(".js")) && has(p)) out.add(p)
                }
                return out
            }
            val main = str(j, "main")?.lowercase()
            if (main != null && (main.endsWith(".lua") || main.endsWith(".js")) && has(main)) return listOf(main)
            if (has("main.lua")) out.add("main.lua")
            if (has("main.js")) out.add("main.js")
            return out
        }

        /** Textos do pacote que os scripts podem ler (require, mod.read). */
        fun textFiles(): Map<String, String> {
            val out = HashMap<String, String>()
            var total = 0
            for (low in entries.keys) {
                val ext = low.substringAfterLast('.', "")
                if (ext != "lua" && ext != "js" && ext != "json" && ext != "txt" && ext != "csv") continue
                if (low.startsWith("assets/") && ext == "json") continue
                val b = bytes(low) ?: continue
                if (b.size > 512 * 1024) continue
                total += b.size
                if (total > 4 * 1024 * 1024) break
                out[low] = String(b, Charsets.UTF_8).removePrefix("\uFEFF")
            }
            return out
        }

        private fun lang(ns: String): JSONObject? =
            json("assets/$ns/lang/pt_br.json") ?: json("assets/$ns/lang/en_us.json")

        private fun shown(kind: String, ns: String, name: String): String =
            lang(ns)?.optString("$kind.$ns.${name.replace('/', '.')}")?.takeIf { it.isNotBlank() } ?: pretty(name)

        // ------------------------------------------------------------ blocos

        fun blocks(): List<Cand> {
            val out = ArrayList<Cand>()
            val data = HashMap<String, JSONObject>()
            val dre = Regex("^data/([^/]+)/blocks/(.+)\\.json$")
            for (m in entries.keys.mapNotNull { dre.find(it) }) {
                val j = json(m.value) ?: continue
                data[m.groupValues[1] + ":" + m.groupValues[2]] = j
            }

            val re = Regex("^assets/([^/]+)/blockstates/(.+)\\.json$")
            val states = entries.keys.mapNotNull { re.find(it) }.sortedBy { it.value }
            val done = HashSet<String>()
            for (m in states) {
                val ns = m.groupValues[1]
                val name = m.groupValues[2]
                if (ns == "minecraft") continue
                val k = "$ns:$name"
                val bs = json(m.value) ?: continue
                val model = firstModel(bs)
                val faces = if (model != null) resolve(ns, model) else null
                if (faces == null) { skipped++; continue }
                val dj = data[k]
                val nm = dj?.optString("name")?.takeIf { it.isNotBlank() } ?: shown("block", ns, name)
                out.add(Cand(k, nm, faces[0], faces[1], faces[2], dj?.let { parseDef(ns, it) }, dj?.optJSONObject("worldgen")))
                done.add(k)
            }

            // blocos definidos so por JSON (sem blockstate): textura vem do proprio JSON
            for (k in data.keys.sorted()) {
                if (k in done) continue
                val j = data[k]!!
                val ns = k.substringBefore(':')
                val name = k.substringAfter(':')
                val tj = j.optJSONObject("textures")
                val all = j.optString("texture").ifEmpty { null }?.let { ref(ns, it) }
                val top = tj?.optString("top")?.ifEmpty { null }?.let { ref(ns, it) } ?: all
                val side = tj?.optString("side")?.ifEmpty { null }?.let { ref(ns, it) } ?: all ?: top
                val bottom = tj?.optString("bottom")?.ifEmpty { null }?.let { ref(ns, it) } ?: all ?: top
                if (top == null && side == null) { skipped++; continue }
                val nm = j.optString("name").ifEmpty { shown("block", ns, name) }
                out.add(Cand(k, nm, top ?: side, side ?: top, bottom ?: top ?: side, parseDef(ns, j), j.optJSONObject("worldgen")))
            }
            return out.sortedBy { it.key }
        }

        // ------------------------------------------------------------ itens

        fun items(): List<ItemCand> {
            val out = ArrayList<ItemCand>()
            val seen = HashSet<String>()
            val dre = Regex("^data/([^/]+)/items/(.+)\\.json$")
            for (m in entries.keys.mapNotNull { dre.find(it) }.sortedBy { it.value }) {
                val ns = m.groupValues[1]
                val name = m.groupValues[2]
                val j = json(m.value) ?: continue
                val nm = j.optString("name").ifEmpty { shown("item", ns, name) }
                val tx = j.optString("texture").ifEmpty { "item/$name" }
                out.add(ItemCand("$ns:$name".lowercase(), nm, ref(ns, tx), j))
                seen.add("$ns:$name".lowercase())
            }
            // itens "generated/handheld" dos mods Java (materiais, sem comportamento)
            val mre = Regex("^assets/([^/]+)/models/item/(.+)\\.json$")
            for (m in entries.keys.mapNotNull { mre.find(it) }.sortedBy { it.value }) {
                val ns = m.groupValues[1]
                val name = m.groupValues[2]
                val k = "$ns:$name".lowercase()
                if (ns == "minecraft" || k in seen) continue
                if (entries.containsKey("assets/$ns/blockstates/$name.json")) continue // item de bloco
                val mj = json(m.value) ?: continue
                val parent = mj.optString("parent")
                if (!parent.contains("generated") && !parent.contains("handheld")) continue
                val l0 = mj.optJSONObject("textures")?.optString("layer0")?.ifEmpty { null } ?: continue
                val full = if (l0.contains(':')) l0.lowercase() else "minecraft:$l0"
                out.add(ItemCand(k, shown("item", ns, name), full, null))
            }
            return out
        }

        // ------------------------------------------------------------ receitas

        /** Devolve (receitas adicionadas, ignoradas). */
        fun addRecipes(): Pair<Int, Int> {
            var ok = 0
            var bad = 0
            val re = Regex("^data/([^/]+)/recipes?/(.+)\\.json$")
            for (m in entries.keys.mapNotNull { re.find(it) }.sortedBy { it.value }) {
                val j = json(m.value) ?: continue
                val r = addRecipeJson(j, null)
                if (r == true) ok++ else if (r == false) bad++
            }
            return Pair(ok, bad)
        }

        // ------------------------------------------------------------ modelos de bloco

        private fun firstModel(bs: JSONObject): String? {
            val v = bs.optJSONObject("variants")
            if (v != null) {
                val keys = ArrayList<String>()
                val it = v.keys()
                while (it.hasNext()) keys.add(it.next())
                if (keys.isEmpty()) return null
                val k = keys.firstOrNull { x -> x == "" || x == "normal" } ?: keys.sorted()[0]
                return modelOf(v.opt(k))
            }
            val mp = bs.optJSONArray("multipart")
            if (mp != null && mp.length() > 0) return modelOf(mp.optJSONObject(0)?.opt("apply"))
            return null
        }

        private fun modelOf(o: Any?): String? = when (o) {
            is JSONObject -> o.optString("model").ifEmpty { null }
            is JSONArray -> if (o.length() > 0) (o.optJSONObject(0)?.optString("model")?.ifEmpty { null }) else null
            else -> null
        }

        private fun split(ref: String, defNs: String): Pair<String, String> =
            if (ref.contains(':')) Pair(ref.substringBefore(':'), ref.substringAfter(':')) else Pair(defNs, ref)

        /** Devolve [topo, lado, base] como "ns:caminho" ou null se o modelo nao for um cubo inteiro. */
        private fun resolve(defNs: String, start: String): Array<String?>? {
            val tex = HashMap<String, String>()
            var kind: String? = null
            var own: HashMap<String, String>? = null
            var ref = start
            var depth = 0
            while (depth++ < 12) {
                val (ns, path) = split(ref, "minecraft")
                if (ns == "minecraft") { kind = path.removePrefix("block/"); break }
                val j = json("assets/$ns/models/$path.json") ?: return null
                val tj = j.optJSONObject("textures")
                if (tj != null) {
                    val it = tj.keys()
                    while (it.hasNext()) { val k = it.next(); if (!tex.containsKey(k)) tex[k] = tj.optString(k) }
                }
                if (own == null) {
                    val els = j.optJSONArray("elements")
                    if (els != null) {
                        if (els.length() != 1) return null
                        val el = els.getJSONObject(0)
                        if (!full(el.optJSONArray("from"), 0.0) || !full(el.optJSONArray("to"), 16.0)) return null
                        val fs = el.optJSONObject("faces") ?: return null
                        val m = HashMap<String, String>()
                        val it = fs.keys()
                        while (it.hasNext()) { val k = it.next(); m[k] = fs.getJSONObject(k).optString("texture") }
                        own = m
                    }
                }
                val parent = j.optString("parent", "")
                if (parent.isEmpty()) break
                ref = parent
            }

            fun t(name: String?): String? {
                if (name == null) return null
                var v = if (name.startsWith("#")) (tex[name.substring(1)] ?: return null)
                else (tex[name] ?: if (name.contains(':') || name.contains('/')) name else return null)
                var n = 0
                while (v.startsWith("#") && n++ < 10) v = tex[v.substring(1)] ?: return null
                val (ns, p) = split(v, "minecraft")
                return "$ns:$p"
            }

            val up: String?
            val down: String?
            val sd: String?
            val o = own
            if (o != null) {
                fun f(k: String) = t(o[k])
                up = f("up"); down = f("down"); sd = f("north") ?: f("east") ?: f("south") ?: f("west")
            } else when (kind) {
                "cube_all", "cube_mirrored_all", "leaves" -> { up = t("all"); down = up; sd = up }
                "cube_column", "cube_column_horizontal" -> { up = t("end"); down = up; sd = t("side") }
                "cube_bottom_top" -> { up = t("top"); down = t("bottom"); sd = t("side") }
                "cube_top" -> { up = t("top"); down = t("side"); sd = down }
                "orientable", "orientable_vertical" -> { up = t("top"); down = up; sd = t("side") ?: t("front") }
                "orientable_with_bottom" -> { up = t("top"); down = t("bottom"); sd = t("side") ?: t("front") }
                "cube", "cube_directional" -> { up = t("up"); down = t("down"); sd = t("north") ?: t("east") ?: t("south") ?: t("west") }
                else -> return null
            }
            val s = sd ?: up ?: down ?: return null
            return arrayOf(up ?: s, s, down ?: up ?: s)
        }

        private fun full(a: JSONArray?, v: Double): Boolean =
            a != null && a.length() == 3 && (0 until 3).all { Math.abs(a.optDouble(it, -1.0) - v) < 0.001 }
    }
}
