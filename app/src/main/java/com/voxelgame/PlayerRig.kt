package com.voxelgame

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Estados de animacao "padrao" do jogo. O JSON do modelo liga cada um a um clip do .glb. */
object MState {
    const val IDLE = 0
    const val WALK = 1
    const val RUN = 2
    const val SNEAK = 3
    const val SNEAK_WALK = 4
    const val JUMP = 5
    const val FALL = 6
    const val SWIM = 7
    const val SWIM_IDLE = 8
    const val FLY = 9
    const val FLY_MOVE = 10
    const val COUNT = 11

    val KEYS = arrayOf("idle", "walk", "run", "sneak", "sneak_walk", "jump", "fall", "swim", "swim_idle", "fly", "fly_move")
    val REF_SPEED = floatArrayOf(0f, 4.317f, 5.612f, 0f, 1.295f, 0f, 0f, 2.16f, 0f, 0f, 10.89f)
    val FALLBACK = arrayOf(
        intArrayOf(), intArrayOf(IDLE), intArrayOf(WALK, IDLE), intArrayOf(IDLE),
        intArrayOf(SNEAK, WALK, IDLE), intArrayOf(FALL, IDLE), intArrayOf(JUMP, IDLE),
        intArrayOf(WALK, IDLE), intArrayOf(SWIM, IDLE), intArrayOf(IDLE), intArrayOf(FLY, WALK, IDLE)
    )
    // palavras-chave para achar o clip pelo nome quando o JSON nao diz
    val AUTO = arrayOf(
        arrayOf("idle", "stand", "breath"), arrayOf("walk"), arrayOf("run", "sprint", "jog"),
        arrayOf("sneak", "crouch"), arrayOf("sneak", "crouch"), arrayOf("jump"), arrayOf("fall"),
        arrayOf("swim"), arrayOf("swimidle", "float", "tread"), arrayOf("fly", "hover"), arrayOf("flymove", "flyforward")
    )
    val ALIAS = mapOf(
        "stand" to IDLE, "sprint" to RUN, "crouch" to SNEAK, "crouch_walk" to SNEAK_WALK,
        "sneak_idle" to SNEAK, "float" to SWIM_IDLE, "hover" to FLY
    )
}

/** Um clip ligado a um estado. */
class StateClip(
    val clip: Int, val speed: Float, val loop: Boolean, val sync: Boolean, val refSpeed: Float, val mask: BooleanArray?
)

class AnimInput {
    var speed = 0f
    var onGround = true
    var velY = 0f
    var inWater = false
    var underwater = false
    var flying = false
    var sneaking = false
    var sprinting = false
    var attack = 0f
    var pitchDown = 0f // radianos, positivo = olhando para baixo
}

class Draw(val first: Int, val count: Int, val material: Int)

/** Modelo 3D do jogador pronto para animar e desenhar (parte da CPU). */
class ModelAsset(val glb: GlbData, cfg: JSONObject?, val warnings: ArrayList<String>) {
    val nodeCount = glb.nodes.size
    val parent = IntArray(nodeCount) { glb.nodes[it].parent }
    val order: IntArray
    val restT = FloatArray(nodeCount * 3)
    val restR = FloatArray(nodeCount * 4)
    val restS = FloatArray(nodeCount * 3)

    // paleta: cada entrada = (no, inversa do bind). Pele e partes rigidas compartilham a mesma tabela.
    val palNode: IntArray
    val palIbm: FloatArray
    val palSize: Int

    // geometria unificada: 14 floats + 4 shorts por vertice (stride 56)
    val vertData: ByteBuffer
    val idxData: ByteBuffer
    val vertCount: Int
    val draws: List<Draw>
    val materials: List<GlbMaterial> = glb.materials

    // configuracao
    var worldScale = 1f
    var groundY = 0f
    var rotY = 0f
    val offset = floatArrayOf(0f, 0f, 0f)
    var hideFpArm = true
    var headNode = -1
    var headPitch = 1f
    var handNode = -1
    val itemOffset = floatArrayOf(0f, 0f, 0f)
    val itemRot = floatArrayOf(0f, 0f, 0f)
    var itemScale = 1f
    var fade = 0.15f
    val stateClips = arrayOfNulls<StateClip>(MState.COUNT)
    var attackClip: StateClip? = null
    var mappedCount = 0

    val animator: RigAnimator

    private fun findNode(name: String?): Int {
        if (name == null || name.isEmpty()) return -1
        val n = norm(name)
        for (i in glb.nodes.indices) if (glb.nodes[i].name == name) return i
        for (i in glb.nodes.indices) if (norm(glb.nodes[i].name) == n) return i
        return -1
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun findClip(ref: Any?): Int {
        if (ref is Number) return if (ref.toInt() in glb.clips.indices) ref.toInt() else -1
        val s = ref?.toString() ?: return -1
        for (i in glb.clips.indices) if (glb.clips[i].name == s) return i
        val n = norm(s)
        for (i in glb.clips.indices) if (norm(glb.clips[i].name) == n) return i
        return -1
    }

    init {
        // ordem topologica (pais antes dos filhos)
        val ord = ArrayList<Int>()
        val seen = BooleanArray(nodeCount)
        fun visit(i: Int) {
            if (i !in 0 until nodeCount || seen[i]) return
            seen[i] = true
            ord.add(i)
            for (c in glb.nodes[i].children) visit(c)
        }
        for (r in glb.roots) visit(r)
        order = ord.toIntArray()
        for (i in 0 until nodeCount) {
            val n = glb.nodes[i]
            for (k in 0 until 3) { restT[i * 3 + k] = n.t[k]; restS[i * 3 + k] = n.s[k] }
            for (k in 0 until 4) restR[i * 4 + k] = n.r[k]
        }

        // ---- geometria
        val pn = ArrayList<Int>()
        val pi = ArrayList<FloatArray?>()
        val skinBase = HashMap<Int, Int>()
        val rigidBase = HashMap<Int, Int>()
        fun addPal(node: Int, ibm: FloatArray?, off: Int): Int {
            pn.add(node)
            pi.add(if (ibm != null) ibm.copyOfRange(off, off + 16) else null)
            return pn.size - 1
        }

        class Item(val prim: GlbPrim, val node: Int)
        val items = ArrayList<Item>()
        for (ni in order) {
            val n = glb.nodes[ni]
            if (n.mesh < 0 || n.mesh >= glb.meshes.size) continue
            for (p in glb.meshes[n.mesh]) items.add(Item(p, ni))
        }
        var total = 0
        for (it in items) total += it.prim.pos.size / 3
        vertCount = total
        vertData = ByteBuffer.allocateDirect(max(1, total) * 56).order(ByteOrder.nativeOrder())
        val allIdx = ArrayList<IntArray>()
        val drawInfo = ArrayList<Triple<Int, Int, Int>>() // (primIndexInAllIdx, count, material)
        var vbase = 0
        val firsts = ArrayList<Int>()
        var idxTotal = 0
        for (it in items) {
            val p = it.prim
            val n = glb.nodes[it.node]
            val vc = p.pos.size / 3
            val skinned = n.skin >= 0 && n.skin < glb.skins.size && p.joints != null && p.weights != null
            var base = 0
            var jmax = 1
            if (skinned) {
                val sk = glb.skins[n.skin]
                base = skinBase.getOrPut(n.skin) {
                    val b = pn.size
                    for (j in sk.joints.indices) addPal(sk.joints[j], sk.ibm, j * 16)
                    b
                }
                jmax = sk.joints.size
            } else {
                base = rigidBase.getOrPut(it.node) { addPal(it.node, null, 0) }
            }
            for (v in 0 until vc) {
                vertData.putFloat(p.pos[v * 3]).putFloat(p.pos[v * 3 + 1]).putFloat(p.pos[v * 3 + 2])
                vertData.putFloat(p.nrm[v * 3]).putFloat(p.nrm[v * 3 + 1]).putFloat(p.nrm[v * 3 + 2])
                vertData.putFloat(p.uv[v * 2]).putFloat(p.uv[v * 2 + 1])
                for (k in 0 until 4) {
                    val j = if (skinned) base + min(max(p.joints!![v * 4 + k], 0), jmax - 1) else base
                    vertData.putShort(j.toShort())
                }
                for (k in 0 until 4) vertData.putFloat(if (skinned) p.weights!![v * 4 + k] else if (k == 0) 1f else 0f)
            }
            val ix = IntArray(p.idx.size) { q -> p.idx[q] + vbase }
            allIdx.add(ix)
            firsts.add(idxTotal)
            drawInfo.add(Triple(allIdx.size - 1, ix.size, p.material))
            idxTotal += ix.size
            vbase += vc
        }
        vertData.position(0)
        palNode = pn.toIntArray()
        palSize = max(1, palNode.size)
        palIbm = FloatArray(palSize * 16)
        for (k in 0 until palSize) {
            val m = if (k < pi.size) pi[k] else null
            if (m != null) System.arraycopy(m, 0, palIbm, k * 16, 16)
            else { palIbm[k * 16] = 1f; palIbm[k * 16 + 5] = 1f; palIbm[k * 16 + 10] = 1f; palIbm[k * 16 + 15] = 1f }
        }
        // opacos/recorte primeiro, translucidos por ultimo
        val ds = ArrayList<Draw>()
        val buf = ByteBuffer.allocateDirect(max(1, idxTotal) * 4).order(ByteOrder.nativeOrder())
        for (pass in 0..1) {
            for (q in drawInfo.indices) {
                val mat = drawInfo[q].third
                val mode = if (mat in materials.indices) materials[mat].mode else 0
                if ((mode == 2) != (pass == 1)) continue
                val first = buf.position() / 4
                for (x in allIdx[drawInfo[q].first]) buf.putInt(x)
                ds.add(Draw(first, drawInfo[q].second, mat))
            }
        }
        buf.position(0)
        idxData = buf
        draws = ds

        animator = RigAnimator(this)
        val rest = animator
        rest.computeRest()
        // ---- altura/escala automaticas pela pose de repouso
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val pal = rest.palette
        val tmp = FloatArray(3)
        for (v in 0 until vertCount) {
            val o = v * 56
            val px = vertData.getFloat(o); val py = vertData.getFloat(o + 4); val pz = vertData.getFloat(o + 8)
            var ox = 0f; var oy = 0f; var oz = 0f
            for (k in 0 until 4) {
                val w = vertData.getFloat(o + 40 + k * 4)
                if (w == 0f) continue
                val j = vertData.getShort(o + 32 + k * 2).toInt() * 16
                ox += w * (pal[j] * px + pal[j + 4] * py + pal[j + 8] * pz + pal[j + 12])
                oy += w * (pal[j + 1] * px + pal[j + 5] * py + pal[j + 9] * pz + pal[j + 13])
                oz += w * (pal[j + 2] * px + pal[j + 6] * py + pal[j + 10] * pz + pal[j + 14])
            }
            tmp[0] = ox; tmp[1] = oy; tmp[2] = oz
            if (oy < minY) minY = oy
            if (oy > maxY) maxY = oy
        }
        if (vertCount == 0 || maxY <= minY) { minY = 0f; maxY = 1f; warnings.add("modelo sem malha visivel") }

        // ---- configuracao (JSON)
        val c = cfg ?: JSONObject()
        val height = c.optDouble("height", 1.8).toFloat()
        worldScale = if (c.has("scale")) c.optDouble("scale", 1.0).toFloat() else height / (maxY - minY)
        groundY = if (c.optBoolean("ground", true)) -minY else 0f
        rotY = c.optDouble("rotation_y", 0.0).toFloat()
        c.optJSONArray("offset")?.let { for (k in 0 until min(3, it.length())) offset[k] = it.optDouble(k, 0.0).toFloat() }
        hideFpArm = c.optString("first_person", "hide") != "default"
        fade = c.optDouble("fade", 0.15).toFloat().coerceIn(0f, 2f)
        headNode = findNode(c.optString("head_bone", "")).let { if (it < 0) autoBone(arrayOf("head")) else it }
        headPitch = c.optDouble("head_pitch", 1.0).toFloat()
        if (c.optString("head_bone", "").isNotEmpty() && findNode(c.optString("head_bone")) < 0) warnings.add("head_bone nao encontrado")
        handNode = findNode(c.optString("hand_bone", "")).let { if (it < 0) autoBone(arrayOf("righthand", "handr", "handright", "rhand")) else it }
        c.optJSONObject("item")?.let { io ->
            io.optJSONArray("offset")?.let { for (k in 0 until min(3, it.length())) itemOffset[k] = it.optDouble(k, 0.0).toFloat() }
            io.optJSONArray("rotation")?.let { for (k in 0 until min(3, it.length())) itemRot[k] = it.optDouble(k, 0.0).toFloat() }
            itemScale = io.optDouble("scale", 1.0).toFloat()
        }

        // ---- animacoes: JSON primeiro, depois deteccao por nome
        val an = c.optJSONObject("animations")
        val auto = c.optBoolean("auto_detect", true)
        val used = HashSet<Int>()
        if (an != null) {
            val keys = an.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val lk = key.lowercase()
                if (lk == "attack" || lk == "swing" || lk == "punch") {
                    attackClip = entry(an.opt(key), key, false, false, 0f)
                    continue
                }
                var st = MState.KEYS.indexOf(lk)
                if (st < 0) st = MState.ALIAS[lk] ?: -1
                if (st < 0) { warnings.add("estado desconhecido: $key"); continue }
                val e = entry(an.opt(key), key, st != MState.JUMP, true, MState.REF_SPEED[st])
                if (e != null) { stateClips[st] = e; used.add(e.clip) }
            }
        }
        if (auto) {
            val order2 = intArrayOf(
                MState.SNEAK_WALK, MState.SNEAK, MState.SWIM_IDLE, MState.SWIM, MState.FLY_MOVE, MState.FLY,
                MState.JUMP, MState.FALL, MState.RUN, MState.WALK, MState.IDLE
            )
            for (st in order2) {
                if (stateClips[st] != null) continue
                if (an != null && hasKey(an, st)) continue
                val ci = autoClip(st)
                if (ci >= 0) stateClips[st] = StateClip(ci, 1f, st != MState.JUMP, true, MState.REF_SPEED[st], null)
            }
            if (attackClip == null && !(an != null && (an.has("attack") || an.has("swing") || an.has("punch")))) {
                for (i in glb.clips.indices) {
                    val nm = norm(glb.clips[i].name)
                    if (nm.contains("attack") || nm.contains("punch") || nm.contains("swing") || nm.contains("slash")) {
                        attackClip = StateClip(i, 1f, false, false, 0f, null); break
                    }
                }
            }
        }
        // fallback: estado sem clip usa o proximo da cadeia
        val resolved = arrayOfNulls<StateClip>(MState.COUNT)
        for (st in 0 until MState.COUNT) {
            var e = stateClips[st]
            if (e == null) for (f in MState.FALLBACK[st]) { if (stateClips[f] != null) { e = stateClips[f]; break } }
            resolved[st] = e
        }
        mappedCount = stateClips.count { it != null } + (if (attackClip != null) 1 else 0)
        for (st in 0 until MState.COUNT) stateClips[st] = resolved[st]
        if (glb.clips.isEmpty()) warnings.add("o .glb nao tem animacoes")
    }

    private fun hasKey(an: JSONObject, st: Int): Boolean {
        if (an.has(MState.KEYS[st])) return true
        for ((k, v) in MState.ALIAS) if (v == st && an.has(k)) return true
        return false
    }

    private fun autoBone(names: Array<String>): Int {
        for (nm in names) for (i in glb.nodes.indices) {
            val n = norm(glb.nodes[i].name)
            if (n == nm || n.endsWith(nm)) return i
        }
        return -1
    }

    private fun autoClip(st: Int): Int {
        val kw = MState.AUTO[st]
        for (i in glb.clips.indices) {
            val n = norm(glb.clips[i].name)
            val sneaky = n.contains("sneak") || n.contains("crouch")
            val ok = kw.any { n.contains(it) }
            if (!ok) continue
            when (st) {
                MState.SNEAK_WALK -> if (!(sneaky && n.contains("walk"))) continue
                MState.SNEAK -> if (!sneaky || n.contains("walk")) continue
                MState.WALK, MState.RUN -> if (sneaky) continue
                MState.SWIM -> if (n.contains("idle")) continue
                MState.FLY -> if (n.contains("move")) continue
            }
            return i
        }
        return -1
    }

    /** "Skin_run", {"clip":"Skin_run","speed":1,"loop":true,"sync":true,"ref_speed":5.6,"mask":["Spine"]} ou indice. */
    private fun entry(v: Any?, key: String, loopDef: Boolean, syncDef: Boolean, refDef: Float): StateClip? {
        var clipRef: Any? = v
        var speed = 1f
        var loop = loopDef
        var sync = syncDef
        var ref = refDef
        var mask: BooleanArray? = null
        if (v is JSONObject) {
            clipRef = v.opt("clip")
            speed = v.optDouble("speed", 1.0).toFloat()
            loop = v.optBoolean("loop", loopDef)
            sync = v.optBoolean("sync", syncDef)
            ref = v.optDouble("ref_speed", refDef.toDouble()).toFloat()
            val ma = v.optJSONArray("mask")
            if (ma != null) {
                mask = BooleanArray(nodeCount)
                for (i in 0 until ma.length()) {
                    val ni = findNode(ma.optString(i))
                    if (ni < 0) { warnings.add("mask: no '${ma.optString(i)}' nao encontrado"); continue }
                    markTree(ni, mask)
                }
            }
        }
        val ci = findClip(clipRef)
        if (ci < 0) { warnings.add("clip '${clipRef}' (estado $key) nao existe no .glb"); return null }
        return StateClip(ci, speed, loop, sync && ref > 0f, ref, mask)
    }

    private fun markTree(i: Int, m: BooleanArray) {
        m[i] = true
        for (c in glb.nodes[i].children) markTree(c, m)
    }

    fun clipNames(): List<String> = glb.clips.map { it.name }
}

class Pose(n: Int) {
    val t = FloatArray(n * 3)
    val r = FloatArray(n * 4)
    val s = FloatArray(n * 3)
}

/** Toca os clips conforme o estado do jogador e produz a paleta de matrizes de juntas. */
class RigAnimator(private val a: ModelAsset) {
    private val n = a.nodeCount
    private val cur = Pose(n)
    private val old = Pose(n)
    private val atk = Pose(n)
    val globals = FloatArray(n * 16)
    val palette = FloatArray(a.palSize * 16)
    private var started = false
    private var eCur: StateClip? = null
    private var tCur = 0f
    private var eOld: StateClip? = null
    private var tOld = 0f
    private var fadeT = 1f
    private var air = 0f
    private val local = FloatArray(16)

    private fun dur(e: StateClip?): Float = if (e == null) 0f else a.glb.clips[e.clip].duration

    fun computeRest() {
        copyRest(cur)
        build(cur, 0f)
    }

    fun update(dt: Float, inp: AnimInput) {
        if (inp.onGround) air = 0f else air += dt
        val moving = inp.speed > 0.25f
        val st = when {
            inp.flying -> if (moving) MState.FLY_MOVE else MState.FLY
            inp.inWater && (inp.underwater || !inp.onGround) -> if (moving) MState.SWIM else MState.SWIM_IDLE
            !inp.onGround && (air > 0.12f || inp.velY > 1.5f) -> if (inp.velY > 0f) MState.JUMP else MState.FALL
            inp.sneaking -> if (moving) MState.SNEAK_WALK else MState.SNEAK
            inp.sprinting && moving -> MState.RUN
            moving -> MState.WALK
            else -> MState.IDLE
        }
        val ne = a.stateClips[st]
        if (!started || ne !== eCur) {
            if (started) {
                val keep = eCur != null && ne != null && eCur!!.loop && ne.loop && dur(eCur) > 0f && dur(ne) > 0f
                eOld = eCur; tOld = tCur; fadeT = 0f
                tCur = if (keep) tCur / dur(eCur) * dur(ne) else 0f
            }
            eCur = ne
            started = true
        }
        tCur = advance(eCur, tCur, dt, inp.speed)
        if (fadeT < 1f) {
            tOld = advance(eOld, tOld, dt, inp.speed)
            fadeT = if (a.fade <= 0f) 1f else min(1f, fadeT + dt / a.fade)
        }

        sample(cur, eCur, tCur)
        if (fadeT < 1f) {
            sample(old, eOld, tOld)
            val w = fadeT * fadeT * (3f - 2f * fadeT)
            blend(cur, old, cur, w, null)
        }
        val ac = a.attackClip
        if (ac != null && inp.attack > 0f) {
            val d = a.glb.clips[ac.clip].duration
            sample(atk, ac, min(inp.attack, 1f) * d)
            val w = min(1f, min(inp.attack, 1f - inp.attack) * 10f).coerceIn(0f, 1f)
            blend(cur, cur, atk, w, ac.mask)
        }
        build(cur, inp.pitchDown)
    }

    private fun advance(e: StateClip?, t: Float, dt: Float, speed: Float): Float {
        if (e == null) return 0f
        val d = dur(e)
        if (d <= 0f) return 0f
        var rate = e.speed
        if (e.sync && e.refSpeed > 0f) rate *= (speed / e.refSpeed).coerceIn(0.4f, 2.2f)
        var nt = t + dt * rate
        if (e.loop) { nt %= d; if (nt < 0f) nt += d } else if (nt > d) nt = d
        return nt
    }

    private fun copyRest(p: Pose) {
        System.arraycopy(a.restT, 0, p.t, 0, p.t.size)
        System.arraycopy(a.restR, 0, p.r, 0, p.r.size)
        System.arraycopy(a.restS, 0, p.s, 0, p.s.size)
    }

    private fun sample(p: Pose, e: StateClip?, time: Float) {
        copyRest(p)
        if (e == null) return
        for (ch in a.glb.clips[e.clip].channels) {
            if (ch.node !in 0 until n) continue
            when (ch.path) {
                0 -> eval(ch, time, p.t, ch.node * 3, 3)
                1 -> eval(ch, time, p.r, ch.node * 4, 4)
                else -> eval(ch, time, p.s, ch.node * 3, 3)
            }
        }
    }

    private fun eval(ch: GlbChannel, time: Float, out: FloatArray, o: Int, nc: Int) {
        val ts = ch.times
        val k = ts.size
        val cubic = ch.interp == 2
        val stride = if (cubic) nc * 3 else nc
        val vo = if (cubic) nc else 0 // onde esta o valor dentro da chave
        val v = ch.values
        if (v.size < k * stride) return
        if (k == 1 || time <= ts[0]) { for (c in 0 until nc) out[o + c] = v[vo + c]; return }
        if (time >= ts[k - 1]) { for (c in 0 until nc) out[o + c] = v[(k - 1) * stride + vo + c]; return }
        var lo = 0
        var hi = k - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (ts[mid] <= time) lo = mid else hi = mid }
        val t0 = ts[lo]
        val t1 = ts[lo + 1]
        val f = if (t1 > t0) (time - t0) / (t1 - t0) else 0f
        val b0 = lo * stride
        val b1 = (lo + 1) * stride
        when (ch.interp) {
            1 -> for (c in 0 until nc) out[o + c] = v[b0 + c]
            2 -> {
                val dt = t1 - t0
                val f2 = f * f
                val f3 = f2 * f
                for (c in 0 until nc) {
                    val p0 = v[b0 + nc + c]
                    val m0 = v[b0 + 2 * nc + c] * dt
                    val p1 = v[b1 + nc + c]
                    val m1 = v[b1 + c] * dt
                    out[o + c] = (2 * f3 - 3 * f2 + 1) * p0 + (f3 - 2 * f2 + f) * m0 + (-2 * f3 + 3 * f2) * p1 + (f3 - f2) * m1
                }
                if (nc == 4) normQ(out, o)
            }
            else -> if (nc == 4) slerp(v, b0, v, b1, f, out, o)
            else for (c in 0 until nc) out[o + c] = v[b0 + c] + (v[b1 + c] - v[b0 + c]) * f
        }
    }

    private fun normQ(q: FloatArray, o: Int) {
        val l = sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3])
        if (l > 1e-9f) for (i in 0 until 4) q[o + i] /= l else { q[o] = 0f; q[o + 1] = 0f; q[o + 2] = 0f; q[o + 3] = 1f }
    }

    private fun slerp(x: FloatArray, xo: Int, y: FloatArray, yo: Int, f: Float, out: FloatArray, o: Int) {
        var d = x[xo] * y[yo] + x[xo + 1] * y[yo + 1] + x[xo + 2] * y[yo + 2] + x[xo + 3] * y[yo + 3]
        var sg = 1f
        if (d < 0f) { d = -d; sg = -1f }
        val w0: Float
        val w1: Float
        if (d > 0.9995f) { w0 = 1f - f; w1 = f * sg }
        else {
            val th = acos(d.coerceAtMost(1f))
            val s = sin(th)
            w0 = sin((1f - f) * th) / s
            w1 = sin(f * th) / s * sg
        }
        for (i in 0 until 4) out[o + i] = x[xo + i] * w0 + y[yo + i] * w1
        normQ(out, o)
    }

    private fun blend(dst: Pose, pa: Pose, pb: Pose, w: Float, mask: BooleanArray?) {
        for (i in 0 until n) {
            if (mask != null && !mask[i]) { if (dst !== pa) copyNode(dst, pa, i); continue }
            for (k in 0 until 3) {
                dst.t[i * 3 + k] = pa.t[i * 3 + k] + (pb.t[i * 3 + k] - pa.t[i * 3 + k]) * w
                dst.s[i * 3 + k] = pa.s[i * 3 + k] + (pb.s[i * 3 + k] - pa.s[i * 3 + k]) * w
            }
            slerp(pa.r, i * 4, pb.r, i * 4, w, dst.r, i * 4)
        }
    }

    private fun copyNode(d: Pose, s: Pose, i: Int) {
        for (k in 0 until 3) { d.t[i * 3 + k] = s.t[i * 3 + k]; d.s[i * 3 + k] = s.s[i * 3 + k] }
        for (k in 0 until 4) d.r[i * 4 + k] = s.r[i * 4 + k]
    }

    private fun build(p: Pose, pitch: Float) {
        for (i in a.order) {
            val m = local
            val x = p.r[i * 4]; val y = p.r[i * 4 + 1]; val z = p.r[i * 4 + 2]; val w = p.r[i * 4 + 3]
            val sx = p.s[i * 3]; val sy = p.s[i * 3 + 1]; val sz = p.s[i * 3 + 2]
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            m[0] = (1 - 2 * (yy + zz)) * sx; m[1] = 2 * (xy + wz) * sx; m[2] = 2 * (xz - wy) * sx; m[3] = 0f
            m[4] = 2 * (xy - wz) * sy; m[5] = (1 - 2 * (xx + zz)) * sy; m[6] = 2 * (yz + wx) * sy; m[7] = 0f
            m[8] = 2 * (xz + wy) * sz; m[9] = 2 * (yz - wx) * sz; m[10] = (1 - 2 * (xx + yy)) * sz; m[11] = 0f
            m[12] = p.t[i * 3]; m[13] = p.t[i * 3 + 1]; m[14] = p.t[i * 3 + 2]; m[15] = 1f
            val par = a.parent[i]
            if (par >= 0) mul(globals, i * 16, globals, par * 16, m, 0)
            else System.arraycopy(m, 0, globals, i * 16, 16)
            if (i == a.headNode && pitch != 0f && a.headPitch != 0f) {
                // gira a cabeca em torno do proprio eixo X (olhar para cima/baixo); a posicao da junta nao muda
                val th = (pitch * a.headPitch).coerceIn(-1.2f, 1.2f)
                val c = cos(th); val s = sin(th)
                val g = i * 16
                for (col in 0 until 3) {
                    val gy = globals[g + col * 4 + 1]
                    val gz = globals[g + col * 4 + 2]
                    globals[g + col * 4 + 1] = c * gy - s * gz
                    globals[g + col * 4 + 2] = s * gy + c * gz
                }
            }
        }
        for (k in 0 until a.palSize) {
            if (k < a.palNode.size) mul(palette, k * 16, globals, a.palNode[k] * 16, a.palIbm, k * 16)
        }
    }

    /** out = A * B (column-major). out nao pode sobrepor A nem B. */
    private fun mul(out: FloatArray, oo: Int, A: FloatArray, ao: Int, B: FloatArray, bo: Int) {
        for (c in 0 until 4) {
            val b0 = B[bo + c * 4]; val b1 = B[bo + c * 4 + 1]; val b2 = B[bo + c * 4 + 2]; val b3 = B[bo + c * 4 + 3]
            for (r in 0 until 4) {
                out[oo + c * 4 + r] = A[ao + r] * b0 + A[ao + 4 + r] * b1 + A[ao + 8 + r] * b2 + A[ao + 12 + r] * b3
            }
        }
    }
}

/** Modelo 3D atual do jogador (null = skin quadrada padrao). Salvo em filesDir/player_model. */
object CustomModel {
    val version = AtomicInteger(0)
    @Volatile var asset: ModelAsset? = null
        private set
    @Volatile var lastInfo = ""
        private set

    fun dir(filesDir: File) = File(filesDir, "player_model")

    private fun set(a: ModelAsset?) {
        asset = a
        version.incrementAndGet()
    }

    fun clear(filesDir: File) {
        dir(filesDir).deleteRecursively()
        set(null)
    }

    fun loadSaved(filesDir: File) {
        val d = dir(filesDir)
        val g = File(d, "model.glb")
        if (!g.exists()) return
        try {
            val j = File(d, "model.json")
            build(g.readBytes(), if (j.exists()) j.readText() else null)
        } catch (t: Throwable) { }
    }

    /** Le o .glb (+ JSON opcional), valida e so entao grava e ativa. Devolve a mensagem para o usuario. */
    fun importGlb(filesDir: File, glb: ByteArray, json: String?): String {
        return try {
            val a = parseAsset(glb, json)
            val d = dir(filesDir)
            d.deleteRecursively()
            d.mkdirs()
            File(d, "model.glb").writeBytes(glb)
            if (json != null) File(d, "model.json").writeText(json)
            set(a)
            describe(a)
        } catch (t: Throwable) {
            "Nao consegui carregar o modelo: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun importZip(filesDir: File, zip: File): String {
        return try {
            ZipFile(zip).use { zf ->
                val es = zf.entries().toList().filter { !it.isDirectory }
                val ge = es.firstOrNull { it.name.lowercase().endsWith(".glb") }
                    ?: return "O .zip nao tem nenhum .glb"
                val base = ge.name.substringBeforeLast('.').lowercase()
                val dirName = ge.name.lowercase().substringBeforeLast('/', "")
                val je = es.firstOrNull { it.name.lowercase() == "$base.json" }
                    ?: es.firstOrNull { it.name.lowercase().substringAfterLast('/') == "model.json" }
                    ?: es.firstOrNull { it.name.lowercase().endsWith(".json") && it.name.lowercase().substringBeforeLast('/', "") == dirName }
                val g = zf.getInputStream(ge).use { it.readBytes() }
                val j = je?.let { e -> zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
                importGlb(filesDir, g, j)
            }
        } catch (t: Throwable) {
            "Nao consegui ler o .zip: " + (t.message ?: "")
        }
    }

    private fun build(glb: ByteArray, json: String?) = set(parseAsset(glb, json))

    private fun parseAsset(glb: ByteArray, json: String?): ModelAsset {
        val cfg = if (json != null && json.isNotBlank()) JSONObject(json) else null
        val warn = ArrayList<String>()
        return ModelAsset(GlbParser.parse(glb), cfg, warn)
    }

    private fun describe(a: ModelAsset): String {
        val s = StringBuilder("Modelo carregado: ${a.glb.clips.size} animacao(oes), ${a.mappedCount} ligada(s)")
        if (a.warnings.isNotEmpty()) s.append(". Aviso: ").append(a.warnings.take(3).joinToString("; "))
        lastInfo = s.toString()
        return lastInfo
    }
}
