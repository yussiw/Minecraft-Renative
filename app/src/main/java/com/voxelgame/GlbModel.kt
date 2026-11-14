package com.voxelgame

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.max
import kotlin.math.sqrt

/** Dados crus de um .glb (glTF 2.0 binario): hierarquia, malhas, materiais, esqueletos e animacoes. */
class GlbNode(val name: String) {
    var parent = -1
    val children = ArrayList<Int>()
    val t = floatArrayOf(0f, 0f, 0f)
    val r = floatArrayOf(0f, 0f, 0f, 1f)
    val s = floatArrayOf(1f, 1f, 1f)
    var mesh = -1
    var skin = -1
}

class GlbPrim(
    val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray,
    val joints: IntArray?, val weights: FloatArray?, val idx: IntArray, val material: Int
)

/** mode: 0 opaco, 1 mask (recorte), 2 blend (translucido). */
class GlbMaterial(
    val color: FloatArray, val image: Int, val wrapS: Int, val wrapT: Int,
    val mode: Int, val cutoff: Float, val doubleSided: Boolean
)

class GlbSkin(val joints: IntArray, val ibm: FloatArray?)

/** path: 0 translacao, 1 rotacao, 2 escala. interp: 0 linear, 1 degrau, 2 cubica. */
class GlbChannel(val node: Int, val path: Int, val times: FloatArray, val values: FloatArray, val interp: Int)

class GlbClip(val name: String, val channels: List<GlbChannel>, val duration: Float)

class GlbData(
    val nodes: List<GlbNode>, val roots: IntArray, val meshes: List<List<GlbPrim>>,
    val materials: List<GlbMaterial>, val images: List<ByteArray?>,
    val skins: List<GlbSkin>, val clips: List<GlbClip>
)

object GlbParser {
    private const val MAGIC = 0x46546C67
    private const val CH_JSON = 0x4E4F534A
    private const val CH_BIN = 0x004E4942

    fun parse(data: ByteArray): GlbData = Reader(data).read()

    private class Reader(val data: ByteArray) {
        lateinit var root: JSONObject
        val buffers = ArrayList<ByteBuffer>()
        var views: JSONArray = JSONArray()
        var accessors: JSONArray = JSONArray()

        fun read(): GlbData {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (data.size < 20 || bb.getInt(0) != MAGIC) throw IllegalArgumentException("nao e um arquivo .glb")
            var off = 12
            var json: String? = null
            var binOff = -1
            var binLen = 0
            while (off + 8 <= data.size) {
                val len = bb.getInt(off)
                val type = bb.getInt(off + 4)
                val start = off + 8
                if (len < 0 || start + len > data.size) break
                if (type == CH_JSON && json == null) json = String(data, start, len, Charsets.UTF_8)
                else if (type == CH_BIN && binOff < 0) { binOff = start; binLen = len }
                off = start + ((len + 3) and 3.inv())
            }
            if (json == null) throw IllegalArgumentException("glb sem bloco JSON")
            root = JSONObject(json)

            val req = root.optJSONArray("extensionsRequired")
            if (req != null) for (i in 0 until req.length()) {
                val e = req.optString(i)
                if (e == "KHR_draco_mesh_compression" || e == "EXT_meshopt_compression")
                    throw IllegalArgumentException("modelo comprimido ($e): exporte sem compressao")
            }

            val bufs = root.optJSONArray("buffers")
            if (bufs != null) for (i in 0 until bufs.length()) {
                val uri = bufs.getJSONObject(i).optString("uri", "")
                val bytes: ByteBuffer = when {
                    uri.isEmpty() -> {
                        if (binOff < 0) throw IllegalArgumentException("glb sem bloco BIN")
                        ByteBuffer.wrap(data, binOff, binLen).slice()
                    }
                    uri.startsWith("data:") -> ByteBuffer.wrap(Base64.getDecoder().decode(uri.substringAfter(',')))
                    else -> throw IllegalArgumentException("buffer externo nao suportado (use .glb com tudo embutido)")
                }
                buffers.add(bytes.order(ByteOrder.LITTLE_ENDIAN))
            }
            views = root.optJSONArray("bufferViews") ?: JSONArray()
            accessors = root.optJSONArray("accessors") ?: JSONArray()

            // nos
            val nodes = ArrayList<GlbNode>()
            val nj = root.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nj.length()) {
                val o = nj.getJSONObject(i)
                val n = GlbNode(o.optString("name", "").ifEmpty { "node_$i" })
                n.mesh = if (o.has("mesh")) o.getInt("mesh") else -1
                n.skin = if (o.has("skin")) o.getInt("skin") else -1
                val m = o.optJSONArray("matrix")
                if (m != null && m.length() == 16) decompose(FloatArray(16) { m.getDouble(it).toFloat() }, n)
                else {
                    o.optJSONArray("translation")?.let { for (k in 0 until 3) n.t[k] = it.getDouble(k).toFloat() }
                    o.optJSONArray("rotation")?.let { for (k in 0 until 4) n.r[k] = it.getDouble(k).toFloat() }
                    o.optJSONArray("scale")?.let { for (k in 0 until 3) n.s[k] = it.getDouble(k).toFloat() }
                }
                nodes.add(n)
            }
            for (i in 0 until nj.length()) {
                val ch = nj.getJSONObject(i).optJSONArray("children") ?: continue
                for (k in 0 until ch.length()) {
                    val c = ch.getInt(k)
                    if (c in nodes.indices && nodes[c].parent < 0) { nodes[c].parent = i; nodes[i].children.add(c) }
                }
            }
            val roots = ArrayList<Int>()
            val scenes = root.optJSONArray("scenes")
            val sc = scenes?.optJSONObject(root.optInt("scene", 0))?.optJSONArray("nodes")
            if (sc != null) for (i in 0 until sc.length()) roots.add(sc.getInt(i))
            else for (i in nodes.indices) if (nodes[i].parent < 0) roots.add(i)

            // malhas
            val meshes = ArrayList<List<GlbPrim>>()
            val mj = root.optJSONArray("meshes") ?: JSONArray()
            for (i in 0 until mj.length()) {
                val ps = mj.getJSONObject(i).optJSONArray("primitives") ?: JSONArray()
                val list = ArrayList<GlbPrim>()
                for (k in 0 until ps.length()) prim(ps.getJSONObject(k))?.let { list.add(it) }
                meshes.add(list)
            }

            // materiais e imagens
            val samplers = root.optJSONArray("samplers")
            val textures = root.optJSONArray("textures")
            val mats = ArrayList<GlbMaterial>()
            val mtj = root.optJSONArray("materials")
            if (mtj != null) for (i in 0 until mtj.length()) {
                val o = mtj.getJSONObject(i)
                val pbr = o.optJSONObject("pbrMetallicRoughness")
                val col = floatArrayOf(1f, 1f, 1f, 1f)
                pbr?.optJSONArray("baseColorFactor")?.let { for (k in 0 until 4) col[k] = it.getDouble(k).toFloat() }
                var img = -1
                var ws = 10497
                var wt = 10497
                val ti = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                if (ti >= 0 && textures != null && ti < textures.length()) {
                    val t = textures.getJSONObject(ti)
                    img = t.optInt("source", -1)
                    val si = t.optInt("sampler", -1)
                    if (si >= 0 && samplers != null && si < samplers.length()) {
                        ws = samplers.getJSONObject(si).optInt("wrapS", 10497)
                        wt = samplers.getJSONObject(si).optInt("wrapT", 10497)
                    }
                }
                val mode = when (o.optString("alphaMode", "OPAQUE")) { "MASK" -> 1; "BLEND" -> 2; else -> 0 }
                mats.add(GlbMaterial(col, img, ws, wt, mode, o.optDouble("alphaCutoff", 0.5).toFloat(), o.optBoolean("doubleSided", false)))
            }
            val images = ArrayList<ByteArray?>()
            val ij = root.optJSONArray("images")
            if (ij != null) for (i in 0 until ij.length()) {
                val o = ij.getJSONObject(i)
                images.add(try {
                    if (o.has("bufferView")) {
                        val bv = views.getJSONObject(o.getInt("bufferView"))
                        val buf = buffers[bv.optInt("buffer", 0)]
                        val off0 = bv.optInt("byteOffset", 0)
                        ByteArray(bv.getInt("byteLength")).also { b -> for (k in b.indices) b[k] = buf.get(off0 + k) }
                    } else if (o.optString("uri").startsWith("data:")) Base64.getDecoder().decode(o.getString("uri").substringAfter(','))
                    else null
                } catch (t: Throwable) { null })
            }

            // peles
            val skins = ArrayList<GlbSkin>()
            val sj = root.optJSONArray("skins")
            if (sj != null) for (i in 0 until sj.length()) {
                val o = sj.getJSONObject(i)
                val ja = o.getJSONArray("joints")
                val joints = IntArray(ja.length()) { ja.getInt(it) }
                skins.add(GlbSkin(joints, if (o.has("inverseBindMatrices")) floats(o.getInt("inverseBindMatrices")) else null))
            }

            // animacoes
            val clips = ArrayList<GlbClip>()
            val aj = root.optJSONArray("animations")
            if (aj != null) for (i in 0 until aj.length()) {
                val o = aj.getJSONObject(i)
                val sa = o.optJSONArray("samplers") ?: continue
                val ca = o.optJSONArray("channels") ?: continue
                val chans = ArrayList<GlbChannel>()
                var dur = 0f
                for (k in 0 until ca.length()) {
                    val c = ca.getJSONObject(k)
                    val tg = c.optJSONObject("target") ?: continue
                    if (!tg.has("node")) continue
                    val path = when (tg.optString("path")) { "translation" -> 0; "rotation" -> 1; "scale" -> 2; else -> continue }
                    val s = sa.getJSONObject(c.getInt("sampler"))
                    val times = floats(s.getInt("input"))
                    val vals = floats(s.getInt("output"))
                    val interp = when (s.optString("interpolation", "LINEAR")) { "STEP" -> 1; "CUBICSPLINE" -> 2; else -> 0 }
                    if (times.isEmpty()) continue
                    dur = max(dur, times[times.size - 1])
                    chans.add(GlbChannel(tg.getInt("node"), path, times, vals, interp))
                }
                clips.add(GlbClip(o.optString("name", "").ifEmpty { "anim_$i" }, chans, dur))
            }
            return GlbData(nodes, roots.toIntArray(), meshes, mats, images, skins, clips)
        }

        private fun prim(p: JSONObject): GlbPrim? {
            val mode = p.optInt("mode", 4)
            if (mode != 4 && mode != 5 && mode != 6) return null
            val at = p.optJSONObject("attributes") ?: return null
            if (!at.has("POSITION")) return null
            val pos = floats(at.getInt("POSITION"))
            val vc = pos.size / 3
            if (vc == 0) return null
            var raw = if (p.has("indices")) ints(p.getInt("indices")) else IntArray(vc) { it }
            raw = when (mode) {
                5 -> {
                    val o = ArrayList<Int>()
                    for (i in 0 until raw.size - 2) {
                        if (i % 2 == 0) { o.add(raw[i]); o.add(raw[i + 1]); o.add(raw[i + 2]) }
                        else { o.add(raw[i + 1]); o.add(raw[i]); o.add(raw[i + 2]) }
                    }
                    o.toIntArray()
                }
                6 -> {
                    val o = ArrayList<Int>()
                    for (i in 1 until raw.size - 1) { o.add(raw[0]); o.add(raw[i]); o.add(raw[i + 1]) }
                    o.toIntArray()
                }
                else -> raw
            }
            val good = ArrayList<Int>(raw.size)
            var i = 0
            while (i + 2 < raw.size) {
                val a = raw[i]; val b = raw[i + 1]; val c = raw[i + 2]
                if (a in 0 until vc && b in 0 until vc && c in 0 until vc) { good.add(a); good.add(b); good.add(c) }
                i += 3
            }
            val idx = good.toIntArray()
            val nrm = if (at.has("NORMAL")) floats(at.getInt("NORMAL")) else smoothNormals(pos, idx)
            val uv = if (at.has("TEXCOORD_0")) floats(at.getInt("TEXCOORD_0")) else FloatArray(vc * 2)
            var joints: IntArray? = null
            var weights: FloatArray? = null
            if (at.has("JOINTS_0") && at.has("WEIGHTS_0")) {
                joints = ints(at.getInt("JOINTS_0"))
                weights = floats(at.getInt("WEIGHTS_0"))
                for (v in 0 until vc) {
                    val s = weights[v * 4] + weights[v * 4 + 1] + weights[v * 4 + 2] + weights[v * 4 + 3]
                    if (s <= 1e-6f) { weights[v * 4] = 1f; weights[v * 4 + 1] = 0f; weights[v * 4 + 2] = 0f; weights[v * 4 + 3] = 0f }
                    else for (k in 0 until 4) weights[v * 4 + k] /= s
                }
            }
            if (nrm.size < vc * 3 || uv.size < vc * 2) return null
            return GlbPrim(pos, nrm, uv, joints, weights, idx, p.optInt("material", -1))
        }

        private fun smoothNormals(pos: FloatArray, idx: IntArray): FloatArray {
            val n = FloatArray(pos.size)
            var i = 0
            while (i + 2 < idx.size) {
                val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
                val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
                val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (q in intArrayOf(a, b, c)) { n[q] += nx; n[q + 1] += ny; n[q + 2] += nz }
                i += 3
            }
            var v = 0
            while (v + 2 < n.size) {
                val l = sqrt(n[v] * n[v] + n[v + 1] * n[v + 1] + n[v + 2] * n[v + 2])
                if (l > 1e-12f) { n[v] /= l; n[v + 1] /= l; n[v + 2] /= l } else n[v + 1] = 1f
                v += 3
            }
            return n
        }

        private fun compCount(t: String) = when (t) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16; else -> 1
        }

        private fun compSize(ct: Int) = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }

        private fun floats(i: Int): FloatArray {
            val a = accessors.getJSONObject(i)
            val out = FloatArray(a.getInt("count") * compCount(a.getString("type")))
            read(a, out, null)
            return out
        }

        private fun ints(i: Int): IntArray {
            val a = accessors.getJSONObject(i)
            val out = IntArray(a.getInt("count") * compCount(a.getString("type")))
            read(a, null, out)
            return out
        }

        private fun read(a: JSONObject, f: FloatArray?, n: IntArray?) {
            if (!a.has("bufferView")) return
            val count = a.getInt("count")
            val comps = compCount(a.getString("type"))
            val bv = views.getJSONObject(a.getInt("bufferView"))
            val buf = buffers[bv.optInt("buffer", 0)]
            val ct = a.getInt("componentType")
            val cs = compSize(ct)
            var stride = bv.optInt("byteStride", 0)
            if (stride <= 0) stride = cs * comps
            val base = bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            val norm = a.optBoolean("normalized", false)
            for (e in 0 until count) {
                for (c in 0 until comps) {
                    val q = base + e * stride + c * cs
                    val k = e * comps + c
                    if (f != null) f[k] = rawFloat(buf, q, ct, norm)
                    if (n != null) n[k] = rawInt(buf, q, ct)
                }
            }
        }

        private fun rawInt(b: ByteBuffer, q: Int, ct: Int): Int = when (ct) {
            5120 -> b.get(q).toInt()
            5121 -> b.get(q).toInt() and 0xFF
            5122 -> b.getShort(q).toInt()
            5123 -> b.getShort(q).toInt() and 0xFFFF
            5125 -> b.getInt(q)
            else -> b.getFloat(q).toInt()
        }

        private fun rawFloat(b: ByteBuffer, q: Int, ct: Int, norm: Boolean): Float = when (ct) {
            5120 -> if (norm) max(b.get(q) / 127f, -1f) else b.get(q).toFloat()
            5121 -> (b.get(q).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
            5122 -> if (norm) max(b.getShort(q) / 32767f, -1f) else b.getShort(q).toFloat()
            5123 -> (b.getShort(q).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
            5125 -> (b.getInt(q).toLong() and 0xFFFFFFFFL).toFloat()
            else -> b.getFloat(q)
        }

        private fun decompose(m: FloatArray, n: GlbNode) {
            n.t[0] = m[12]; n.t[1] = m[13]; n.t[2] = m[14]
            var sx = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
            val sy = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
            val sz = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
            val det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[4] * (m[1] * m[10] - m[2] * m[9]) + m[8] * (m[1] * m[6] - m[2] * m[5])
            if (det < 0f) sx = -sx
            n.s[0] = sx; n.s[1] = sy; n.s[2] = sz
            if (sx == 0f || sy == 0f || sz == 0f) return
            val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx
            val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy
            val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
            val tr = r00 + r11 + r22
            val x: Float; val y: Float; val z: Float; val w: Float
            if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                w = s / 4f; x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s
            } else if (r00 > r11 && r00 > r22) {
                val s = sqrt(1f + r00 - r11 - r22) * 2f
                w = (r21 - r12) / s; x = s / 4f; y = (r01 + r10) / s; z = (r02 + r20) / s
            } else if (r11 > r22) {
                val s = sqrt(1f + r11 - r00 - r22) * 2f
                w = (r02 - r20) / s; x = (r01 + r10) / s; y = s / 4f; z = (r12 + r21) / s
            } else {
                val s = sqrt(1f + r22 - r00 - r11) * 2f
                w = (r10 - r01) / s; x = (r02 + r20) / s; y = (r12 + r21) / s; z = s / 4f
            }
            n.r[0] = x; n.r[1] = y; n.r[2] = z; n.r[3] = w
        }
    }
}
