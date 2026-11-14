package com.voxelgame

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class GameRenderer(
    private val game: Game,
    private val refreshRate: Float,
    private val onShot: (Bitmap) -> Unit
) : GLSurfaceView.Renderer {

    private class GLMesh(
        val cx: Int, val cz: Int, val vao: Int, val vbo: Int, val quads: Int, val maxY: Int, val ver: Int,
        val vao2: Int, val vbo2: Int, val quads2: Int
    )
    private class Job(val prio: Int, val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    private val meshes = HashMap<Long, GLMesh>()
    private val inFlight = HashSet<Long>()
    private val results = ConcurrentLinkedQueue<MeshData>()
    private val verCounter = AtomicInteger(0)

    private val workers = max(2, min(4, Runtime.getRuntime().availableProcessors() - 1))
    private val pool = ThreadPoolExecutor(
        workers, workers, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(128, Comparator<Runnable> { a, b -> (a as Job).prio.compareTo((b as Job).prio) }),
        ThreadFactory { r -> Thread(r, "mesher").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    )

    @Volatile private var wantCx = 0
    @Volatile private var wantCz = 0
    @Volatile private var wantR = 6

    // distancia de render adaptativa
    private val minR = 3
    private val maxR = 14
    private var radius = 6
    private var cap = maxR
    private var justGrew = false
    private var goodWindows = 0
    private var warmup = 3

    private var lastCx = Int.MIN_VALUE
    private var lastCz = Int.MIN_VALUE
    private var lastRadius = -1
    private var frameCount = 0
    private var lastNs = 0L
    private var accNs = 0L
    private var frames = 0
    private var width = 1
    private var height = 1

    private var prog = 0
    private var uMvp = 0
    private var uOrigin = 0
    private var uFog = 0
    private var uSky = 0
    private var uTex = 0
    private var uLight = 0
    private var lineProg = 0
    private var lMvp = 0
    private var lOff = 0
    private var lineVao = 0
    private var ibo = 0
    private var tex = 0

    // programa "flat": nuvens, sol/lua e rachadura do bloco
    private var flatProg = 0
    private var fMvp = 0
    private var fOff = 0
    private var fFog = 0
    private var fTint = 0
    private var fAlpha = 0
    private var fSky = 0
    private var cubeVao = 0
    private var sunVao = 0
    private var sunVbo = 0
    private var cloudVao = 0
    private var cloudVbo = 0
    private var cloudCount = 0
    private var cloudCi = Int.MIN_VALUE
    private var cloudCj = 0
    private var cloudDrift = 0f
    private val CR = 14
    private val sunArr = FloatArray(2 * 42)
    private val cloudArr = FloatArray(29 * 29 * 252)
    private val sunBB = ByteBuffer.allocateDirect(2 * 42 * 4).order(ByteOrder.nativeOrder())
    private val cloudBB = ByteBuffer.allocateDirect(29 * 29 * 252 * 4).order(ByteOrder.nativeOrder())

    // programa "hand": mao, itens soltos, modelo do jogador, particulas e rachadura (pos3, uv2, brilho1)
    private class HMesh(val vao: Int, val vbo: Int, val count: Int)
    private var handProg = 0
    private var hMvp = 0
    private var hModel = 0
    private var hTex = 0
    private var hLight = 0
    private var hFog = 0
    private var hSky = 0
    private var hTint = 0
    private var handVao = 0
    private var handVbo = 0
    private val handArr = FloatArray(36 * 6)
    private val handBB = ByteBuffer.allocateDirect(36 * 6 * 4).order(ByteOrder.nativeOrder())
    private val model = FloatArray(16)
    private val model2 = FloatArray(16)
    private val tmpA = FloatArray(16)
    private val handMvp = FloatArray(16)
    private var fovCur = 70f

    private var crackTex = 0
    private var skinTex = 0
    private var skinVer = -1
    private var pmVao = 0
    private var pmVbo = 0
    private var partVao = 0
    private var partVbo = 0
    private val partArr = FloatArray(Particles.MAX * 36)
    private val partBB = ByteBuffer.allocateDirect(Particles.MAX * 36 * 4).order(ByteOrder.nativeOrder())
    private val itemMeshes = HashMap<Int, HMesh>()   // sprite de item extrudado, por tile
    private val blockMeshes = HashMap<Int, HMesh>()  // cubo, por id de bloco
    private val poses = Array(6) { PartPose() }

    // modelo 3D customizado (.glb) do jogador
    private var skinGpu: SkinnedModelGpu? = null
    private var skinGpuVer = -1
    private var frameDt = 0.016f
    private val animIn = AnimInput()
    private val rotTmp = FloatArray(16)

    // camera (1a ou 3a pessoa); tudo e desenhado relativo a ela
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var vdx = 0f
    private var vdy = 0f
    private var vdz = -1f

    // animacao de troca de item na mao
    private var handShown = 0
    private var handEquip = 1f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val planes = FloatArray(24)

    private val skyR = 0.53f
    private val skyG = 0.76f
    private val skyB = 1.0f

    init {
        Stats.radius = radius
        wantR = radius
    }

    // ---------------------------------------------------------------- GL setup

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(VS, FS)
        uMvp = GLES30.glGetUniformLocation(prog, "u_mvp")
        uOrigin = GLES30.glGetUniformLocation(prog, "u_origin")
        uFog = GLES30.glGetUniformLocation(prog, "u_fog")
        uSky = GLES30.glGetUniformLocation(prog, "u_sky")
        uTex = GLES30.glGetUniformLocation(prog, "u_tex")
        uLight = GLES30.glGetUniformLocation(prog, "u_light")
        lineProg = link(LVS, LFS)
        lMvp = GLES30.glGetUniformLocation(lineProg, "u_mvp")
        lOff = GLES30.glGetUniformLocation(lineProg, "u_off")

        // textura (atlas gerado por codigo)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST_MIPMAP_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        uploadAtlas()

        // indice compartilhado por todos os chunks (quads -> 2 triangulos)
        val idx = ShortArray(Mesher.MAXQ * 6)
        for (q in 0 until Mesher.MAXQ) {
            val b = q * 4
            idx[q * 6] = b.toShort()
            idx[q * 6 + 1] = (b + 1).toShort()
            idx[q * 6 + 2] = (b + 2).toShort()
            idx[q * 6 + 3] = b.toShort()
            idx[q * 6 + 4] = (b + 2).toShort()
            idx[q * 6 + 5] = (b + 3).toShort()
        }
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
        ib.asShortBuffer().put(idx)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        ibo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        // contorno do bloco mirado
        val e = 0.003f
        val lo = -e
        val hi = 1f + e
        val c = floatArrayOf(
            lo, lo, lo, hi, lo, lo, hi, lo, lo, hi, lo, hi, hi, lo, hi, lo, lo, hi, lo, lo, hi, lo, lo, lo,
            lo, hi, lo, hi, hi, lo, hi, hi, lo, hi, hi, hi, hi, hi, hi, lo, hi, hi, lo, hi, hi, lo, hi, lo,
            lo, lo, lo, lo, hi, lo, hi, lo, lo, hi, hi, lo, hi, lo, hi, hi, hi, hi, lo, lo, hi, lo, hi, hi
        )
        val lb = ByteBuffer.allocateDirect(c.size * 4).order(ByteOrder.nativeOrder())
        lb.asFloatBuffer().put(c)
        val lv = IntArray(2)
        GLES30.glGenVertexArrays(1, lv, 0)
        GLES30.glGenBuffers(1, lv, 1)
        lineVao = lv[0]
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lv[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, c.size * 4, lb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)

        skinGpu = null // o contexto GL antigo (e seus handles) nao existe mais
        skinGpuVer = -1
        initFlat()
        initHand()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(skyR, skyG, skyB, 1f)
        cloudCi = Int.MIN_VALUE

        // contexto novo: os handles antigos nao valem mais
        meshes.clear()
        lastCx = Int.MIN_VALUE
        lastNs = 0L
    }

    private var texVersion = -1

    /** Sobe o atlas para a GPU (com mipmaps: tiles alinhados em potencia de 2, sem sangrar entre vizinhos). */
    private fun uploadAtlas() {
        val v = Atlas.version.get()
        val bmp = Atlas.bitmap
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        texVersion = v
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
    }

    // ---------------------------------------------------------------- chunks

    private fun wanted(cx: Int, cz: Int): Boolean {
        val dx = cx - wantCx
        val dz = cz - wantCz
        val r = wantR + 1
        return dx * dx + dz * dz <= r * r
    }

    private fun submit(cx: Int, cz: Int, prio: Int, force: Boolean) {
        val ver = verCounter.incrementAndGet()
        pool.execute(Job(prio) {
            try {
                if (!force && !wanted(cx, cz)) results.add(MeshData(cx, cz, ver, null, 0, 0))
                else results.add(Mesher.build(game.world, cx, cz, ver))
            } catch (t: Throwable) {
                Log.e("Blocos", "mesh falhou", t)
                results.add(MeshData(cx, cz, ver, null, 0, 0))
            }
        })
    }

    private fun scan(pcx: Int, pcz: Int) {
        val r = radius
        for (dz in -r..r) {
            for (dx in -r..r) {
                val d2 = dx * dx + dz * dz
                if (d2 > r * r) continue
                val cx = pcx + dx
                val cz = pcz + dz
                val k = chunkKey(cx, cz)
                if (meshes.containsKey(k) || inFlight.contains(k)) continue
                inFlight.add(k)
                submit(cx, cz, d2, false)
            }
        }
    }

    private fun makeVao(buf: ByteBuffer): IntArray {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, buf.remaining(), buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, 4, GLES30.GL_UNSIGNED_BYTE, 8, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribIPointer(1, 2, GLES30.GL_UNSIGNED_BYTE, 8, 4)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBindVertexArray(0)
        return ids
    }

    private fun upload(r: MeshData) {
        val buf = r.buf ?: return
        val key = chunkKey(r.cx, r.cz)
        val old = meshes[key]
        if (old != null && old.ver > r.ver) return
        var vao = 0
        var vbo = 0
        var vao2 = 0
        var vbo2 = 0
        if (r.quads > 0) {
            val ids = makeVao(buf)
            vao = ids[0]; vbo = ids[1]
        }
        val buf2 = r.buf2
        if (r.quads2 > 0 && buf2 != null) {
            val ids = makeVao(buf2)
            vao2 = ids[0]; vbo2 = ids[1]
        }
        if (old != null) free(old)
        meshes[key] = GLMesh(r.cx, r.cz, vao, vbo, r.quads, r.maxY, r.ver, vao2, vbo2, r.quads2)
    }

    private fun free(m: GLMesh) {
        if (m.vao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(m.vbo), 0)
        }
        if (m.vao2 != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao2), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(m.vbo2), 0)
        }
    }

    private fun housekeep(pcx: Int, pcz: Int) {
        val r2 = (radius + 2) * (radius + 2)
        val it = meshes.entries.iterator()
        while (it.hasNext()) {
            val m = it.next().value
            val dx = m.cx - pcx
            val dz = m.cz - pcz
            if (dx * dx + dz * dz > r2) {
                free(m)
                it.remove()
            }
        }
        val w2 = (radius + 4) * (radius + 4)
        for (c in game.world.chunks.values) {
            if (c.edited) continue
            val dx = c.cx - pcx
            val dz = c.cz - pcz
            if (dx * dx + dz * dz > w2) game.world.chunks.remove(chunkKey(c.cx, c.cz))
        }
    }

    // ---------------------------------------------------------------- adaptacao

    private fun adapt(fps: Float) {
        if (warmup > 0) { warmup--; return }
        if (fps < 58f && radius > minR) {
            if (justGrew) cap = radius - 1
            radius--
            justGrew = false
            goodWindows = 0
        } else if (fps >= refreshRate * 0.96f) {
            justGrew = false
            goodWindows++
            if (goodWindows >= 4 && radius < min(cap, maxR)) {
                radius++
                goodWindows = 0
                justGrew = true
            }
        } else {
            goodWindows = 0
        }
        Stats.radius = radius
    }

    // ---------------------------------------------------------------- flat / mao

    private fun genBuf(): Int {
        val a = IntArray(1)
        GLES30.glGenBuffers(1, a, 0)
        return a[0]
    }

    private fun flatVao(vbo: Int): Int {
        val v = IntArray(1)
        GLES30.glGenVertexArrays(1, v, 0)
        GLES30.glBindVertexArray(v[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glBindVertexArray(0)
        return v[0]
    }

    private fun fillBuf(vbo: Int, bb: ByteBuffer, arr: FloatArray, count: Int) {
        bb.clear()
        bb.asFloatBuffer().put(arr, 0, count)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 4, bb, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun vert(a: FloatArray, o: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, al: Float): Int {
        a[o] = x; a[o + 1] = y; a[o + 2] = z
        a[o + 3] = r; a[o + 4] = g; a[o + 5] = b; a[o + 6] = al
        return o + 7
    }

    /** Quad centrado em (cx,cy,cz) com meios-eixos u e v (6 vertices). */
    private fun putQuad(
        a: FloatArray, o0: Int, cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        r: Float, g: Float, b: Float, al: Float
    ): Int {
        var o = o0
        val p0x = cx - ux - vx; val p0y = cy - uy - vy; val p0z = cz - uz - vz
        val p1x = cx + ux - vx; val p1y = cy + uy - vy; val p1z = cz + uz - vz
        val p2x = cx + ux + vx; val p2y = cy + uy + vy; val p2z = cz + uz + vz
        val p3x = cx - ux + vx; val p3y = cy - uy + vy; val p3z = cz - uz + vz
        o = vert(a, o, p0x, p0y, p0z, r, g, b, al)
        o = vert(a, o, p1x, p1y, p1z, r, g, b, al)
        o = vert(a, o, p2x, p2y, p2z, r, g, b, al)
        o = vert(a, o, p0x, p0y, p0z, r, g, b, al)
        o = vert(a, o, p2x, p2y, p2z, r, g, b, al)
        o = vert(a, o, p3x, p3y, p3z, r, g, b, al)
        return o
    }

    private fun initFlat() {
        flatProg = link(FVS, FFS)
        fMvp = GLES30.glGetUniformLocation(flatProg, "u_mvp")
        fOff = GLES30.glGetUniformLocation(flatProg, "u_off")
        fFog = GLES30.glGetUniformLocation(flatProg, "u_fog")
        fTint = GLES30.glGetUniformLocation(flatProg, "u_tint")
        fAlpha = GLES30.glGetUniformLocation(flatProg, "u_alpha")
        fSky = GLES30.glGetUniformLocation(flatProg, "u_sky")

        // cubo unitario (rachadura do bloco), preto
        val arr = FloatArray(36 * 7)
        val hs = 0.502f
        var o = 0
        for (sg in intArrayOf(1, -1)) {
            val f = sg.toFloat()
            o = putQuad(arr, o, 0.5f + f * hs, 0.5f, 0.5f, 0f, 0f, hs, 0f, hs, 0f, 0f, 0f, 0f, 1f)
            o = putQuad(arr, o, 0.5f, 0.5f + f * hs, 0.5f, hs, 0f, 0f, 0f, 0f, hs, 0f, 0f, 0f, 1f)
            o = putQuad(arr, o, 0.5f, 0.5f, 0.5f + f * hs, hs, 0f, 0f, 0f, hs, 0f, 0f, 0f, 0f, 1f)
        }
        val cubeVbo = genBuf()
        val bb = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder())
        fillBuf(cubeVbo, bb, arr, o)
        cubeVao = flatVao(cubeVbo)

        sunVbo = genBuf()
        sunVao = flatVao(sunVbo)
        cloudVbo = genBuf()
        cloudVao = flatVao(cloudVbo)
    }

    private fun makeHandVao(vbo: Int): Int {
        val v = IntArray(1)
        GLES30.glGenVertexArrays(1, v, 0)
        GLES30.glBindVertexArray(v[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 24, 20)
        GLES30.glBindVertexArray(0)
        return v[0]
    }

    private fun makeTex(bmp: Bitmap): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        return t[0]
    }

    /** 10 estagios de rachadura (tira 160x16), que crescem: cada estagio contem o anterior. */
    private fun makeCrackBitmap(): Bitmap {
        val order = ArrayList<Int>()
        val seen = BooleanArray(256)
        val rnd = java.util.Random(4242)
        val wx = intArrayOf(8, 3, 12, 6, 10, 13)
        val wy = intArrayOf(8, 5, 10, 13, 2, 6)
        val hx = intArrayOf(1, 1, -1, 0, -1, 1)
        val hy = intArrayOf(1, -1, 1, -1, 1, 0)
        val total = 100
        var guard = 0
        while (order.size < total && guard++ < 6000) {
            val i = rnd.nextInt(wx.size)
            if (rnd.nextInt(10) < 3) {
                hx[i] = (hx[i] + rnd.nextInt(3) - 1).coerceIn(-1, 1)
                hy[i] = (hy[i] + rnd.nextInt(3) - 1).coerceIn(-1, 1)
                if (hx[i] == 0 && hy[i] == 0) hx[i] = 1
            }
            val nx = wx[i] + hx[i]
            val ny = wy[i] + hy[i]
            if (nx !in 0..15 || ny !in 0..15) {
                hx[i] = -hx[i]
                hy[i] = -hy[i]
                if (hx[i] == 0 && hy[i] == 0) hy[i] = 1
                continue
            }
            wx[i] = nx
            wy[i] = ny
            val k = ny * 16 + nx
            if (!seen[k]) { seen[k] = true; order.add(k) }
            if (rnd.nextInt(4) == 0 && nx + 1 < 16) {
                val k2 = ny * 16 + nx + 1
                if (!seen[k2]) { seen[k2] = true; order.add(k2) }
            }
        }
        val pix = IntArray(160 * 16)
        for (st in 0 until 10) {
            val n = order.size * (st + 1) / 10
            for (j in 0 until n) {
                val k = order[j]
                val g = 28 + (Noise.hash(k, 3, 91) and 0x1F)
                pix[(k / 16) * 160 + st * 16 + (k % 16)] = Color.argb(255, g, g, g)
            }
        }
        val bmp = Bitmap.createBitmap(160, 16, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pix, 0, 160, 0, 0, 160, 16)
        return bmp
    }

    private fun uploadSkin() {
        val v = PlayerSkin.version.get()
        if (skinTex == 0) {
            skinTex = makeTex(PlayerSkin.bitmap)
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skinTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, PlayerSkin.bitmap, 0)
        }
        skinVer = v
    }

    private fun initHand() {
        handProg = link(HVS, HFS)
        hMvp = GLES30.glGetUniformLocation(handProg, "u_mvp")
        hModel = GLES30.glGetUniformLocation(handProg, "u_model")
        hTex = GLES30.glGetUniformLocation(handProg, "u_tex")
        hLight = GLES30.glGetUniformLocation(handProg, "u_light")
        hFog = GLES30.glGetUniformLocation(handProg, "u_fog")
        hSky = GLES30.glGetUniformLocation(handProg, "u_sky")
        hTint = GLES30.glGetUniformLocation(handProg, "u_tint")
        handVbo = genBuf()
        handVao = makeHandVao(handVbo)

        // particulas (buffer dinamico)
        partVbo = genBuf()
        partVao = makeHandVao(partVbo)

        // modelo do jogador (estatico: 12 partes x 36 vertices)
        val pv = PlayerModel.verts
        val bb = ByteBuffer.allocateDirect(pv.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(pv)
        pmVbo = genBuf()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pmVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, pv.size * 4, bb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        pmVao = makeHandVao(pmVbo)

        crackTex = makeTex(makeCrackBitmap())
        skinTex = 0
        uploadSkin()
        itemMeshes.clear()
        blockMeshes.clear()
        handShown = 0
        handEquip = 1f
    }

    /** Descarta as malhas de item (a textura do atlas mudou: a silhueta do sprite pode ter mudado). */
    private fun dropItemMeshes() {
        for (m in itemMeshes.values) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(m.vbo), 0)
        }
        itemMeshes.clear()
    }

    private fun hv(a: FloatArray, o: Int, x: Float, y: Float, z: Float, u: Float, v: Float, sh: Float): Int {
        a[o] = x; a[o + 1] = y; a[o + 2] = z; a[o + 3] = u; a[o + 4] = v; a[o + 5] = sh
        return o + 6
    }

    /** Cantos na ordem: baixo-esq, baixo-dir, cima-dir, cima-esq (uv direto, 0..1 na textura). */
    private fun handQuadUV(
        a: FloatArray, o0: Int, u0: Float, v0: Float, u1: Float, v1: Float, sh: Float,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float
    ): Int {
        var o = o0
        o = hv(a, o, x0, y0, z0, u0, v1, sh)
        o = hv(a, o, x1, y1, z1, u1, v1, sh)
        o = hv(a, o, x2, y2, z2, u1, v0, sh)
        o = hv(a, o, x0, y0, z0, u0, v1, sh)
        o = hv(a, o, x2, y2, z2, u1, v0, sh)
        o = hv(a, o, x3, y3, z3, u0, v0, sh)
        return o
    }

    /** Cantos na ordem: baixo-esq, baixo-dir, cima-dir, cima-esq (tile inteiro do atlas). */
    private fun handQuad(
        a: FloatArray, o0: Int, tile: Int, sh: Float,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float
    ): Int {
        val col = tile % 8
        val row = tile / 8
        return handQuadUV(
            a, o0, (col + 0.004f) / 8f, (row + 0.004f) / 8f, (col + 0.996f) / 8f, (row + 0.996f) / 8f, sh,
            x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3
        )
    }

    private fun drawSky() {
        val ang = game.time * 6.2831855f
        val sx = cos(ang)
        val sy = sin(ang)
        val dist = 80f
        val sz = 9f
        var o = 0
        var n = 0
        if (sy > -0.25f) {
            o = putQuad(sunArr, o, sx * dist, sy * dist, 0f, 0f, 0f, sz, -sy * sz, sx * sz, 0f, 1f, 0.95f, 0.65f, 1f)
            n += 6
        }
        if (-sy > -0.25f) {
            val m = sz * 0.8f
            o = putQuad(sunArr, o, -sx * dist, -sy * dist, 0f, 0f, 0f, m, sy * m, -sx * m, 0f, 0.88f, 0.92f, 1f, 1f)
            n += 6
        }
        if (n == 0) return
        GLES30.glUseProgram(flatProg)
        GLES30.glUniformMatrix4fv(fMvp, 1, false, mvp, 0)
        GLES30.glUniform3f(fOff, 0f, 0f, 0f)
        GLES30.glUniform2f(fFog, 1e9f, 2e9f)
        GLES30.glUniform3f(fTint, 1f, 1f, 1f)
        GLES30.glUniform1f(fAlpha, 1f)
        GLES30.glUniform3f(fSky, sky[0], sky[1], sky[2])
        fillBuf(sunVbo, sunBB, sunArr, o)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glBindVertexArray(sunVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun cloudAt(i: Int, j: Int): Boolean = Noise.fbm(i * 0.17f, j * 0.17f, 987654, 3) > 0.56f

    private fun rebuildClouds(ci: Int, cj: Int) {
        val n = 2 * CR + 3
        val grid = BooleanArray(n * n)
        for (i in -CR - 1..CR + 1) for (j in -CR - 1..CR + 1) grid[(i + CR + 1) * n + (j + CR + 1)] = cloudAt(ci + i, cj + j)
        fun g(i: Int, j: Int) = grid[(i + CR + 1) * n + (j + CR + 1)]
        val c = 12f
        val h = c / 2f
        var o = 0
        for (i in -CR..CR) {
            for (j in -CR..CR) {
                if (!g(i, j)) continue
                val x0 = i * c
                val z0 = j * c
                o = putQuad(cloudArr, o, x0 + h, 4f, z0 + h, h, 0f, 0f, 0f, 0f, h, 1f, 1f, 1f, 0.8f)
                o = putQuad(cloudArr, o, x0 + h, 0f, z0 + h, h, 0f, 0f, 0f, 0f, h, 0.7f, 0.7f, 0.7f, 0.8f)
                if (!g(i - 1, j)) o = putQuad(cloudArr, o, x0, 2f, z0 + h, 0f, 0f, h, 0f, 2f, 0f, 0.85f, 0.85f, 0.85f, 0.8f)
                if (!g(i + 1, j)) o = putQuad(cloudArr, o, x0 + c, 2f, z0 + h, 0f, 0f, h, 0f, 2f, 0f, 0.85f, 0.85f, 0.85f, 0.8f)
                if (!g(i, j - 1)) o = putQuad(cloudArr, o, x0 + h, 2f, z0, h, 0f, 0f, 0f, 2f, 0f, 0.8f, 0.8f, 0.8f, 0.8f)
                if (!g(i, j + 1)) o = putQuad(cloudArr, o, x0 + h, 2f, z0 + c, h, 0f, 0f, 0f, 2f, 0f, 0.8f, 0.8f, 0.8f, 0.8f)
            }
        }
        cloudCount = o / 7
        fillBuf(cloudVbo, cloudBB, cloudArr, o)
        cloudCi = ci
        cloudCj = cj
    }

    private fun drawClouds(ex: Double, ey: Double, ez: Double, dt: Float) {
        cloudDrift = (cloudDrift + dt * 1.2f) % 1_000_000f
        if (game.underwater) return
        val ci = floor((ex - cloudDrift) / 12.0).toInt()
        val cj = floor(ez / 12.0).toInt()
        if (ci != cloudCi || cj != cloudCj) rebuildClouds(ci, cj)
        if (cloudCount == 0) return
        val l = sky[3]
        val dusk = sky[4]
        GLES30.glUseProgram(flatProg)
        GLES30.glUniformMatrix4fv(fMvp, 1, false, mvp, 0)
        GLES30.glUniform3f(fOff, (ci * 12.0 + cloudDrift - ex).toFloat(), (108.0 - ey).toFloat(), (cj * 12.0 - ez).toFloat())
        GLES30.glUniform2f(fFog, 120f, 215f)
        GLES30.glUniform3f(fTint, l, l * (1f - dusk * 0.2f), l * (1f - dusk * 0.45f))
        GLES30.glUniform1f(fAlpha, 1f)
        GLES30.glUniform3f(fSky, sky[0], sky[1], sky[2])
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(cloudVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, cloudCount)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    // ---------------------------------------------------------------- malhas de item / bloco

    private fun uploadMesh(arr: FloatArray, floats: Int): HMesh {
        val bb = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(arr, 0, floats)
        val vbo = genBuf()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floats * 4, bb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return HMesh(makeHandVao(vbo), vbo, floats / 6)
    }

    /** Cubo unitario centrado na origem, com as texturas do bloco. */
    private fun cubeMesh(id: Int): HMesh {
        val c = blockMeshes[id]
        if (c != null) return c
        val a = FloatArray(36 * 6)
        var o = 0
        val h = 0.5f
        val tt = Blocks.top[id]
        val ts = Blocks.side[id]
        val tb = Blocks.bottom[id]
        o = handQuad(a, o, ts, 0.8f, -h, -h, h, h, -h, h, h, h, h, -h, h, h)
        o = handQuad(a, o, ts, 0.8f, h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h)
        o = handQuad(a, o, ts, 0.6f, h, -h, h, h, -h, -h, h, h, -h, h, h, h)
        o = handQuad(a, o, ts, 0.6f, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h)
        o = handQuad(a, o, tt, 1.0f, -h, h, h, h, h, h, h, h, -h, -h, h, -h)
        o = handQuad(a, o, tb, 0.5f, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h)
        val m = uploadMesh(a, o)
        blockMeshes[id] = m
        return m
    }

    /**
     * Sprite de item com espessura (1/16 de bloco), como os itens do Minecraft Java:
     * duas faces + uma borda para cada pixel opaco que encosta num pixel transparente.
     * Centrado na origem, 1 x 1 bloco; o fundo transparente nao vira quadrado.
     */
    private fun itemMesh(tile: Int): HMesh {
        val cached = itemMeshes[tile]
        if (cached != null) return cached
        val bmp = Atlas.bitmap
        val ts = bmp.width / Atlas.TILES
        val col = tile % Atlas.TILES
        val row = tile / Atlas.TILES
        val px = IntArray(ts * ts)
        try {
            bmp.getPixels(px, 0, ts, col * ts, row * ts, ts, ts)
        } catch (t: Throwable) {
            java.util.Arrays.fill(px, -1)
        }
        fun solid(x: Int, y: Int): Boolean = x in 0 until ts && y in 0 until ts && (px[y * ts + x] ushr 24) >= 26
        var a = FloatArray(6 * 6 * 64)
        var o = 0
        fun room(n: Int) {
            if (o + n > a.size) a = a.copyOf(max(a.size * 2, o + n))
        }
        val h = 0.5f
        val t = 1f / 32f
        room(72)
        o = handQuad(a, o, tile, 1f, -h, -h, t, h, -h, t, h, h, t, -h, h, t)
        o = handQuad(a, o, tile, 1f, h, -h, -t, -h, -h, -t, -h, h, -t, h, h, -t)
        val inv = 1f / ts
        for (y in 0 until ts) {
            for (x in 0 until ts) {
                if (!solid(x, y)) continue
                val u = (col + (x + 0.5f) * inv) / 8f
                val v = (row + (y + 0.5f) * inv) / 8f
                val xl = x * inv - h
                val xr = (x + 1) * inv - h
                val yt = h - y * inv
                val yb = h - (y + 1) * inv
                if (!solid(x - 1, y)) {
                    room(36)
                    o = handQuadUV(a, o, u, v, u, v, 0.85f, xl, yb, t, xl, yb, -t, xl, yt, -t, xl, yt, t)
                }
                if (!solid(x + 1, y)) {
                    room(36)
                    o = handQuadUV(a, o, u, v, u, v, 0.85f, xr, yb, -t, xr, yb, t, xr, yt, t, xr, yt, -t)
                }
                if (!solid(x, y - 1)) {
                    room(36)
                    o = handQuadUV(a, o, u, v, u, v, 0.85f, xl, yt, t, xr, yt, t, xr, yt, -t, xl, yt, -t)
                }
                if (!solid(x, y + 1)) {
                    room(36)
                    o = handQuadUV(a, o, u, v, u, v, 0.85f, xl, yb, -t, xr, yb, -t, xr, yb, t, xl, yb, t)
                }
            }
        }
        val m = uploadMesh(a, o)
        itemMeshes[tile] = m
        return m
    }

    private fun meshFor(id: Int): HMesh =
        if (Blocks.isCube(id)) cubeMesh(id) else itemMesh(Blocks.iconTile(id))

    // ---------------------------------------------------------------- desenho (programa "hand")

    private fun beginHand(light: Float, fogS: Float, fogE: Float, tr: Float, tg: Float, tb: Float, texId: Int) {
        GLES30.glUseProgram(handProg)
        GLES30.glUniform1i(hTex, 0)
        GLES30.glUniform1f(hLight, light)
        GLES30.glUniform2f(hFog, fogS, fogE)
        GLES30.glUniform3f(hSky, sky[0], sky[1], sky[2])
        GLES30.glUniform3f(hTint, tr, tg, tb)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    private fun endHand() {
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /** base = projecao (mao) ou projecao*view (mundo); m = modelo (relativo a camera). */
    private fun drawMesh(base: FloatArray, m: FloatArray, vao: Int, first: Int, count: Int) {
        Matrix.multiplyMM(tmpA, 0, base, 0, m, 0)
        GLES30.glUniformMatrix4fv(hMvp, 1, false, tmpA, 0)
        GLES30.glUniformMatrix4fv(hModel, 1, false, m, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, first, count)
    }

    // ---------------------------------------------------------------- rachadura ao quebrar

    /** Textura de rachadura (10 estagios) sobre o bloco, com a mistura do Java: cor = 2 * src * dst. */
    private fun drawBreakOverlay(ex: Double, ey: Double, ez: Double) {
        if (game.breakProg <= 0f || !game.hitValid) return
        val stage = min(9, (game.breakProg * 10f).toInt())
        val u0 = stage / 10f
        val u1 = (stage + 1) / 10f
        val h = 0.502f
        var o = 0
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, -h, -h, h, h, -h, h, h, h, h, -h, h, h)
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h)
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, h, -h, h, h, -h, -h, h, h, -h, h, h, h)
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h)
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, -h, h, h, h, h, h, h, h, -h, -h, h, -h)
        o = handQuadUV(handArr, o, u0, 0f, u1, 1f, 1f, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            (game.hx + 0.5 - ex).toFloat(), (game.hy + 0.5 - ey).toFloat(), (game.hz + 0.5 - ez).toFloat()
        )
        beginHand(1f, 1e9f, 2e9f, 1f, 1f, 1f, crackTex)
        fillBuf(handVbo, handBB, handArr, o)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_SRC_COLOR)
        GLES30.glDepthMask(false)
        drawMesh(mvp, model, handVao, 0, o / 6)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        endHand()
    }

    // ---------------------------------------------------------------- mao (1a pessoa)

    /**
     * Poses do ItemInHandRenderer do Java: troca de item (sobe/desce), golpe e os "display transforms"
     * (bloco: giro 45, escala 0,4; sprite: [0,-90,25], [1,13; 3,2; 1,13]/16, escala 0,68).
     * Sem item, desenha o braco do jogador com a skin.
     */
    private fun drawHand(dt: Float) {
        if (game.input.hideHud || Stats.camMode != 0) return
        val inv = game.input.inv
        val sel = game.input.selected.coerceIn(0, 8)
        val want = if (inv.count[sel] > 0) inv.id[sel] else 0
        if (want != handShown) {
            handEquip -= dt * 8f
            if (handEquip <= 0f) { handEquip = 0f; handShown = want }
        } else if (handEquip < 1f) {
            handEquip = min(1f, handEquip + dt * 8f)
        }
        val id = handShown
        val pi = 3.1415927f
        val p = 1f - game.swing
        val sq = sqrt(p)
        val eq = 1f - handEquip
        val light = max(sky[3], 0.4f)
        Matrix.setIdentityM(model, 0)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

        if (id != 0) {
            val cube = Blocks.isCube(id)
            Matrix.translateM(model, 0, -0.4f * sin(sq * pi), 0.2f * sin(sq * 2f * pi), -0.2f * sin(p * pi))
            Matrix.translateM(model, 0, 0.56f, -0.52f - eq * 0.6f, -0.72f)
            Matrix.rotateM(model, 0, 45f - 20f * sin(p * p * pi), 0f, 1f, 0f)
            val f1 = sin(sq * pi)
            Matrix.rotateM(model, 0, f1 * -20f, 0f, 0f, 1f)
            Matrix.rotateM(model, 0, f1 * -80f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, -45f, 0f, 1f, 0f)
            if (cube) {
                Matrix.rotateM(model, 0, 45f, 0f, 1f, 0f)
                Matrix.scaleM(model, 0, 0.4f, 0.4f, 0.4f)
            } else {
                Matrix.translateM(model, 0, 1.13f / 16f, 3.2f / 16f, 1.13f / 16f)
                Matrix.rotateM(model, 0, -90f, 0f, 1f, 0f)
                Matrix.rotateM(model, 0, 25f, 0f, 0f, 1f)
                Matrix.scaleM(model, 0, 0.68f, 0.68f, 0.68f)
            }
            val mesh = meshFor(id)
            beginHand(light, 1e9f, 2e9f, 1f, 1f, 1f, tex)
            drawMesh(proj, model, mesh.vao, 0, mesh.count)
            endHand()
        } else {
            if (CustomModel.asset?.hideFpArm == true && skinGpu != null) return
            // braco direito (renderPlayerArm)
            Matrix.translateM(
                model, 0,
                -0.3f * sin(sq * pi) + 0.64000005f,
                0.4f * sin(sq * 2f * pi) - 0.6f - eq * 0.6f,
                -0.4f * sin(p * pi) - 0.71999997f
            )
            Matrix.rotateM(model, 0, 45f, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, sin(sq * pi) * 70f, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, sin(p * p * pi) * -20f, 0f, 0f, 1f)
            Matrix.translateM(model, 0, -1f, 3.6f, 3.5f)
            Matrix.rotateM(model, 0, 120f, 0f, 0f, 1f)
            Matrix.rotateM(model, 0, 200f, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, -135f, 0f, 1f, 0f)
            Matrix.translateM(model, 0, 5.6f, 0f, 0f)
            Matrix.translateM(model, 0, -5f / 16f, 2f / 16f, 0f)
            Matrix.rotateM(model, 0, 0.1f * 57.29578f, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, 1f / 16f, 1f / 16f, 1f / 16f)
            beginHand(light, 1e9f, 2e9f, 1f, 1f, 1f, skinTex)
            drawMesh(proj, model, pmVao, PlayerModel.R_ARM * PlayerModel.VERTS_PER_PART, PlayerModel.VERTS_PER_PART)
            drawMesh(proj, model, pmVao, PlayerModel.R_SLEEVE * PlayerModel.VERTS_PER_PART, PlayerModel.VERTS_PER_PART)
            endHand()
        }
    }

    // ---------------------------------------------------------------- entidades (itens, particulas, jogador)

    private fun drawEntities(ex: Double, ey: Double, ez: Double) {
        val light = max(sky[3], 0.35f)
        val fogS: Float
        val fogE: Float
        if (game.underwater) {
            fogS = 1f; fogE = 26f
        } else {
            val fe = radius * 16f - 6f
            fogS = fe * 0.55f; fogE = fe
        }
        val deg = 57.29578f

        // ---- itens soltos: giram e flutuam; cubo = 0,25 de bloco, sprite = 0,5
        if (game.drops.isNotEmpty()) {
            beginHand(light, fogS, fogE, 1f, 1f, 1f, tex)
            for (e in game.drops) {
                if (e.count <= 0) continue
                var wx = e.x
                var wy = e.y
                var wz = e.z
                if (e.collectT >= 0f) {
                    val tt = (e.collectT / 0.15f).coerceIn(0f, 1f)
                    val k = (tt * tt).toDouble()
                    wx = e.sx + (game.px - e.sx) * k
                    wy = e.sy + (game.py + game.eye * 0.5 - e.sy) * k
                    wz = e.sz + (game.pz - e.sz) * k
                }
                if (abs(wx - ex) > 72.0 || abs(wz - ez) > 72.0) continue
                val cube = Blocks.isCube(e.id)
                val mesh = meshFor(e.id)
                val bob = sin(e.age * 2f + e.bob) * 0.1f + 0.1f
                val sc = if (cube) 0.25f else 0.5f
                val n = if (e.count > 48) 5 else if (e.count > 32) 4 else if (e.count > 16) 3 else if (e.count > 1) 2 else 1
                for (k in 0 until n) {
                    val hh = Noise.hash(e.seed, k, 11)
                    val ox = if (k == 0) 0f else ((hh and 0xFF) / 255f - 0.5f) * 0.12f
                    val oz = if (k == 0) 0f else (((hh shr 8) and 0xFF) / 255f - 0.5f) * 0.12f
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(
                        model, 0,
                        (wx - ex).toFloat() + ox,
                        (wy - ey).toFloat() + bob + sc * 0.5f + k * 0.012f,
                        (wz - ez).toFloat() + oz
                    )
                    Matrix.rotateM(model, 0, (e.age + e.bob) * deg, 0f, 1f, 0f)
                    Matrix.scaleM(model, 0, sc, sc, sc)
                    drawMesh(mvp, model, mesh.vao, 0, mesh.count)
                }
            }
            endHand()
        }

        // ---- particulas (billboards com 1/4 do tile)
        val pc = game.particles
        if (pc.count > 0) {
            val vlen = sqrt(vdx * vdx + vdz * vdz)
            val rx = -vdz / vlen
            val rz = vdx / vlen
            val ux = -rz * vdy
            val uy = rz * vdx - rx * vdz
            val uz = rx * vdy
            var o = 0
            for (i in 0 until pc.count) {
                val cx = (pc.x[i] - ex).toFloat()
                val cy = (pc.y[i] - ey).toFloat()
                val cz = (pc.z[i] - ez).toFloat()
                val sz = pc.size[i]
                val t = pc.tile[i]
                val u0 = ((t % 8) + pc.uo[i]) / 8f
                val v0 = ((t / 8) + pc.vo[i]) / 8f
                val du = 0.25f / 8f
                o = handQuadUV(
                    partArr, o, u0, v0, u0 + du, v0 + du, 0.6f,
                    cx - rx * sz - ux * sz, cy - uy * sz, cz - rz * sz - uz * sz,
                    cx + rx * sz - ux * sz, cy - uy * sz, cz + rz * sz - uz * sz,
                    cx + rx * sz + ux * sz, cy + uy * sz, cz + rz * sz + uz * sz,
                    cx - rx * sz + ux * sz, cy + uy * sz, cz - rz * sz + uz * sz
                )
            }
            Matrix.setIdentityM(model, 0)
            beginHand(light, fogS, fogE, 1f, 1f, 1f, tex)
            fillBuf(partVbo, partBB, partArr, o)
            drawMesh(mvp, model, partVao, 0, o / 6)
            endHand()
        }

        // ---- jogador (3a pessoa)
        if (Stats.camMode != 0) drawPlayer(light, fogS, fogE, ex, ey, ez)

        // ---- outros jogadores (multijogador)
        if (Net.mode != Net.Mode.OFF) drawRemotePlayers(light, fogS, fogE, ex, ey, ez)
    }

    private fun partMatrix(out: FloatArray, base: FloatArray, pp: PartPose) {
        val deg = 57.29578f
        System.arraycopy(base, 0, out, 0, 16)
        Matrix.translateM(out, 0, pp.x / 16f, pp.y / 16f, pp.z / 16f)
        if (pp.rz != 0f) Matrix.rotateM(out, 0, pp.rz * deg, 0f, 0f, 1f)
        if (pp.ry != 0f) Matrix.rotateM(out, 0, pp.ry * deg, 0f, 1f, 0f)
        if (pp.rx != 0f) Matrix.rotateM(out, 0, pp.rx * deg, 1f, 0f, 0f)
    }

    /** Mesma cadeia do LivingEntityRenderer: pos, giro, scale(-1,-1,1), translate(0,-1,501,0) e cada parte. */
    private fun syncCustomModel() {
        val v = CustomModel.version.get()
        if (v == skinGpuVer) return
        skinGpuVer = v
        try { skinGpu?.release() } catch (t: Throwable) { }
        skinGpu = null
        val a = CustomModel.asset ?: return
        try {
            val g = SkinnedModelGpu(a)
            g.create()
            skinGpu = g
        } catch (t: Throwable) {
            Log.e("Blocos", "modelo 3d falhou na GPU", t)
            skinGpu = null
        }
    }

    /** Jogador com modelo .glb: animacao por esqueleto escolhida pelo estado (andar, correr, pular...). */
    private fun drawCustomPlayer(
        gpu: SkinnedModelGpu, a: ModelAsset, light: Float, fogS: Float, fogE: Float,
        ex: Double, ey: Double, ez: Double
    ) {
        val inv = game.input.inv
        val sel = game.input.selected.coerceIn(0, 8)
        val held = if (inv.count[sel] > 0) inv.id[sel] else 0
        val ai = animIn
        ai.speed = game.hSpeed
        ai.onGround = game.onGround
        ai.velY = game.velY.toFloat()
        ai.inWater = game.inWater
        ai.underwater = game.underwater
        ai.flying = game.flying
        ai.sneaking = game.sneaking
        ai.sprinting = game.sprinting
        ai.attack = game.attackProgress
        ai.pitchDown = (-game.pitch).toFloat()
        a.animator.update(frameDt, ai)

        val yawDeg = (game.yaw * 180.0 / Math.PI).toFloat() + 180f + a.rotY
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, (game.px - ex).toFloat(), (game.py - ey).toFloat(), (game.pz - ez).toFloat())
        Matrix.rotateM(model, 0, yawDeg, 0f, 1f, 0f)
        Matrix.translateM(model, 0, a.offset[0], a.offset[1], a.offset[2])
        // 'model' agora e a base (sem escala) usada tambem para o item na mao
        System.arraycopy(model, 0, model2, 0, 16)
        Matrix.scaleM(model2, 0, a.worldScale, a.worldScale, a.worldScale)
        Matrix.translateM(model2, 0, 0f, a.groundY, 0f)

        val gb = if (Stats.flash > 0f) 0.45f else 1f
        gpu.draw(mvp, model2, a.animator.palette, light, fogS, fogE, 1f, gb, gb, sky)

        beginHand(light, fogS, fogE, 1f, gb, gb, tex)
        if (held != 0 && a.handNode >= 0) {
            val g = a.animator.globals
            val ho = a.handNode * 16
            val s = a.worldScale
            Matrix.translateM(model, 0, s * g[ho + 12], s * (g[ho + 13] + a.groundY), s * g[ho + 14])
            java.util.Arrays.fill(rotTmp, 0f)
            rotTmp[15] = 1f
            for (c in 0 until 3) {
                val x = g[ho + c * 4]; val y = g[ho + c * 4 + 1]; val z = g[ho + c * 4 + 2]
                val l = sqrt(x * x + y * y + z * z).let { if (it < 1e-6f) 1f else it }
                rotTmp[c * 4] = x / l; rotTmp[c * 4 + 1] = y / l; rotTmp[c * 4 + 2] = z / l
            }
            Matrix.multiplyMM(tmpA, 0, model, 0, rotTmp, 0)
            System.arraycopy(tmpA, 0, model, 0, 16)
            Matrix.translateM(model, 0, a.itemOffset[0], a.itemOffset[1], a.itemOffset[2])
            if (a.itemRot[1] != 0f) Matrix.rotateM(model, 0, a.itemRot[1], 0f, 1f, 0f)
            if (a.itemRot[0] != 0f) Matrix.rotateM(model, 0, a.itemRot[0], 1f, 0f, 0f)
            if (a.itemRot[2] != 0f) Matrix.rotateM(model, 0, a.itemRot[2], 0f, 0f, 1f)
            val sc = (if (Blocks.isCube(held)) 0.3f else 0.5f) * a.itemScale
            Matrix.scaleM(model, 0, sc, sc, sc)
            val mesh = meshFor(held)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            drawMesh(mvp, model, mesh.vao, 0, mesh.count)
        }
        endHand()
    }

    private fun drawPlayer(light: Float, fogS: Float, fogE: Float, ex: Double, ey: Double, ez: Double) {
        val gpu = skinGpu
        val asset = CustomModel.asset
        if (gpu != null && asset != null && gpu.asset === asset) {
            drawCustomPlayer(gpu, asset, light, fogS, fogE, ex, ey, ez)
            return
        }
        val inv = game.input.inv
        val sel = game.input.selected.coerceIn(0, 8)
        val held = if (inv.count[sel] > 0) inv.id[sel] else 0
        val gb = if (Stats.flash > 0f) 0.45f else 1f
        drawSkinPlayer(
            light, fogS, fogE, ex, ey, ez,
            game.px, game.py, game.pz, game.yaw, game.pitch, game.sneaking, game.attackProgress,
            game.limbSwing, game.limbAmount, game.ageTicks, held, 1f, gb, gb
        )
    }

    /** Jogador com a skin padrao em qualquer posicao: o local (3a pessoa) e os remotos (multijogador). */
    private fun drawSkinPlayer(
        light: Float, fogS: Float, fogE: Float, ex: Double, ey: Double, ez: Double,
        x: Double, y: Double, z: Double, yaw: Double, pitch: Double, sneaking: Boolean,
        attack: Float, limbSwing: Float, limbAmount: Float, age: Float, held: Int,
        tr: Float, tg: Float, tb: Float
    ) {
        PlayerModel.pose(poses, limbSwing, limbAmount, age, (-pitch).toFloat(), sneaking, attack, held != 0)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            (x - ex).toFloat(),
            (y - ey - (if (sneaking) 0.125 else 0.0)).toFloat(),
            (z - ez).toFloat()
        )
        Matrix.rotateM(model, 0, (yaw * 180.0 / Math.PI).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(model, 0, -1f, -1f, 1f)
        Matrix.translateM(model, 0, 0f, -1.501f, 0f)

        beginHand(light, fogS, fogE, tr, tg, tb, skinTex)
        val vp = PlayerModel.VERTS_PER_PART
        val main = intArrayOf(PlayerModel.HEAD, PlayerModel.BODY, PlayerModel.R_ARM, PlayerModel.L_ARM, PlayerModel.R_LEG, PlayerModel.L_LEG)
        for (i in 0 until 6) {
            partMatrix(model2, model, poses[i])
            Matrix.scaleM(model2, 0, 1f / 16f, 1f / 16f, 1f / 16f)
            drawMesh(mvp, model2, pmVao, main[i] * vp, vp)
            drawMesh(mvp, model2, pmVao, (main[i] + 1) * vp, vp) // camada externa
        }

        // item na mao direita (ItemInHandLayer)
        if (held != 0) {
            partMatrix(model2, model, poses[PlayerModel.P_RARM])
            Matrix.rotateM(model2, 0, -90f, 1f, 0f, 0f)
            Matrix.rotateM(model2, 0, 180f, 0f, 1f, 0f)
            Matrix.translateM(model2, 0, 1f / 16f, 0.125f, -0.625f)
            if (Blocks.isCube(held)) {
                Matrix.translateM(model2, 0, 0f, 2.5f / 16f, 0f)
                Matrix.rotateM(model2, 0, 75f, 1f, 0f, 0f)
                Matrix.rotateM(model2, 0, 45f, 0f, 1f, 0f)
                Matrix.scaleM(model2, 0, 0.375f, 0.375f, 0.375f)
            } else {
                Matrix.translateM(model2, 0, 0f, 3f / 16f, 1f / 16f)
                Matrix.scaleM(model2, 0, 0.55f, 0.55f, 0.55f)
            }
            val mesh = meshFor(held)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            drawMesh(mvp, model2, mesh.vao, 0, mesh.count)
        }
        endHand()
    }

    private val tagIn = FloatArray(4)
    private val tagOut = FloatArray(4)

    // um tom leve por jogador (id % 4) para distinguir quem e quem com a mesma skin
    private val remoteTints = floatArrayOf(
        1f, 0.80f, 0.80f,
        0.80f, 1f, 0.80f,
        0.80f, 0.85f, 1f,
        1f, 1f, 0.70f
    )

    /** Desenha os outros jogadores e calcula onde fica o nome deles na tela. */
    private fun drawRemotePlayers(light: Float, fogS: Float, fogE: Float, ex: Double, ey: Double, ez: Double) {
        for (r in Net.remotes.values) {
            r.sv = false
            if (!r.visible) continue
            if (abs(r.x - ex) > 100.0 || abs(r.z - ez) > 100.0) continue
            val ti = (r.id % 4) * 3
            drawSkinPlayer(
                light, fogS, fogE, ex, ey, ez,
                r.x, r.y, r.z, r.yaw.toDouble(), r.pitch.toDouble(), r.sneaking, r.attack,
                r.limbSwing, r.limbAmount, r.ageTicks, r.held,
                remoteTints[ti], remoteTints[ti + 1], remoteTints[ti + 2]
            )
            tagIn[0] = (r.x - ex).toFloat()
            tagIn[1] = (r.y + (if (r.sneaking) 1.95 else 2.15) - ey).toFloat()
            tagIn[2] = (r.z - ez).toFloat()
            tagIn[3] = 1f
            Matrix.multiplyMV(tagOut, 0, mvp, 0, tagIn, 0)
            if (tagOut[3] > 0.1f) {
                r.sx = (tagOut[0] / tagOut[3] * 0.5f + 0.5f) * width
                r.sy = (1f - (tagOut[1] / tagOut[3] * 0.5f + 0.5f)) * height
                r.sv = true
            }
        }
    }

    private fun capture() {
        val w = width
        val h = height
        val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
        bb.rewind()
        val ib = bb.asIntBuffer()
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sr = (h - 1 - y) * w
            val dr = y * w
            for (x in 0 until w) {
                val p = ib.get(sr + x)
                out[dr + x] = (0xFF shl 24) or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p shr 16) and 0xFF)
            }
        }
        onShot(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888))
    }

    // ---------------------------------------------------------------- ceu

    private val sky = FloatArray(5) // r, g, b, luz, crepusculo

    private fun computeSky(t: Float, under: Boolean) {
        val sun = sin(t * 6.2831855f)
        var d = (sun + 0.15f) / 0.4f
        d = if (d < 0f) 0f else if (d > 1f) 1f else d
        d = d * d * (3f - 2f * d)
        var r = 0.02f + 0.51f * d
        var g = 0.03f + 0.73f * d
        var b = 0.09f + 0.91f * d
        val dusk = max(0f, 1f - Math.abs(sun) * 5f) * 0.6f
        r += (1.0f - r) * dusk
        g += (0.5f - g) * dusk
        b += (0.25f - b) * dusk
        if (under) {
            val k = 0.35f + 0.65f * d
            r = 0.06f * k; g = 0.22f * k; b = 0.42f * k
        }
        sky[0] = r; sky[1] = g; sky[2] = b
        sky[3] = 0.30f + 0.70f * d
        sky[4] = dusk
    }

    // ---------------------------------------------------------------- frustum

    private fun extractPlanes(m: FloatArray) {
        for (i in 0 until 6) {
            val sign = if (i % 2 == 0) 1f else -1f
            val row = i / 2
            for (j in 0 until 4) planes[i * 4 + j] = m[j * 4 + 3] + sign * m[j * 4 + row]
        }
    }

    private fun visible(minx: Float, miny: Float, minz: Float, maxx: Float, maxy: Float, maxz: Float): Boolean {
        for (i in 0 until 6) {
            val a = planes[i * 4]
            val b = planes[i * 4 + 1]
            val c = planes[i * 4 + 2]
            val d = planes[i * 4 + 3]
            val x = if (a > 0f) maxx else minx
            val y = if (b > 0f) maxy else miny
            val z = if (c > 0f) maxz else minz
            if (a * x + b * y + c * z + d < 0f) return false
        }
        return true
    }

    // ---------------------------------------------------------------- frame

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNs == 0L) lastNs = now
        val rawNs = now - lastNs
        lastNs = now
        var dt = rawNs / 1e9f
        if (dt > 0.05f) dt = 0.05f

        frameDt = dt
        game.update(dt)
        if (Atlas.version.get() != texVersion) { uploadAtlas(); dropItemMeshes() }
        if (PlayerSkin.version.get() != skinVer) uploadSkin()
        syncCustomModel()

        val pcx = floor(game.px / 16.0).toInt()
        val pcz = floor(game.pz / 16.0).toInt()
        wantCx = pcx
        wantCz = pcz
        wantR = radius
        if (pcx != lastCx || pcz != lastCz || radius != lastRadius) {
            scan(pcx, pcz)
            lastCx = pcx
            lastCz = pcz
            lastRadius = radius
        }

        // remeshes causados por quebrar/colocar blocos (prioridade maxima)
        while (true) {
            val k = game.world.remeshQueue.poll() ?: break
            if (meshes.containsKey(k)) submit(keyX(k), keyZ(k), -1, true)
        }

        // sobe para a GPU algumas malhas por frame (evita travadas)
        var budget = if (meshes.size < 24) 8 else 2
        while (budget > 0) {
            val r = results.poll() ?: break
            val key = chunkKey(r.cx, r.cz)
            inFlight.remove(key)
            if (r.buf == null) continue
            val dx = r.cx - pcx
            val dz = r.cz - pcz
            val lim = (radius + 2) * (radius + 2)
            if (dx * dx + dz * dz > lim) continue
            upload(r)
            budget--
        }

        frameCount++
        if (frameCount % 45 == 0) housekeep(pcx, pcz)

        // camera relativa (sem perda de precisao em coordenadas grandes)
        val yaw = game.yaw
        val pitch = game.pitch
        val dx = (-sin(yaw) * cos(pitch)).toFloat()
        val dy = sin(pitch).toFloat()
        val dz = (-cos(yaw) * cos(pitch)).toFloat()
        val targetFov = 70f + (if (game.sprinting) 8f else 0f) + (if (game.flying && game.sprinting) 6f else 0f)
        fovCur += (targetFov - fovCur) * min(1f, dt * 10f)
        Matrix.perspectiveM(proj, 0, fovCur, width.toFloat() / height, 0.1f, max(radius * 16f + 48f, 230f))
        // camera: 1a pessoa no olho; 3a pessoa (F5) 4 blocos atras/na frente, recuando se bater num bloco
        val eyeX = game.px
        val eyeY = game.py + game.eye
        val eyeZ = game.pz
        camX = eyeX; camY = eyeY; camZ = eyeZ
        vdx = dx; vdy = dy; vdz = dz
        if (Stats.camMode != 0) {
            val sg = if (Stats.camMode == 1) -1f else 1f
            val bx = dx * sg
            val by = dy * sg
            val bz = dz * sg
            var dist = 4.0
            var t = 0.2
            while (t <= 4.0) {
                val b = game.world.get(floor(eyeX + bx * t).toInt(), floor(eyeY + by * t).toInt(), floor(eyeZ + bz * t).toInt())
                if (b < 0 || (b != 0 && b != Blocks.WATER && !Blocks.isPlant(b))) { dist = max(0.3, t - 0.25); break }
                t += 0.1
            }
            camX = eyeX + bx * dist
            camY = eyeY + by * dist
            camZ = eyeZ + bz * dist
            vdx = -bx; vdy = -by; vdz = -bz
        }
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, vdx, vdy, vdz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        extractPlanes(mvp)

        computeSky(game.time, game.underwater)
        GLES30.glClearColor(sky[0], sky[1], sky[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawSky()
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        val fogEnd = radius * 16f - 6f
        if (game.underwater) GLES30.glUniform2f(uFog, 1f, 26f)
        else GLES30.glUniform2f(uFog, fogEnd * 0.55f, fogEnd)
        GLES30.glUniform3f(uSky, sky[0], sky[1], sky[2])
        GLES30.glUniform1f(uLight, sky[3])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(uTex, 0)

        val ex = camX
        val ey = camY
        val ez = camZ
        var drawn = 0
        var quadsDrawn = 0
        for (m in meshes.values) {
            if (m.quads == 0) continue
            val ox = (m.cx * 16 - ex).toFloat()
            val oy = (-ey).toFloat()
            val oz = (m.cz * 16 - ez).toFloat()
            if (!visible(ox, oy, oz, ox + 16f, oy + m.maxY, oz + 16f)) continue
            GLES30.glUniform3f(uOrigin, ox, oy, oz)
            GLES30.glBindVertexArray(m.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.quads * 6, GLES30.GL_UNSIGNED_SHORT, 0)
            drawn++
            quadsDrawn += m.quads
        }

        if (game.hitValid) {
            GLES30.glUseProgram(lineProg)
            GLES30.glUniformMatrix4fv(lMvp, 1, false, mvp, 0)
            GLES30.glUniform3f(lOff, (game.hx - ex).toFloat(), (game.hy - ey).toFloat(), (game.hz - ez).toFloat())
            GLES30.glBindVertexArray(lineVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 24)
        }
        GLES30.glBindVertexArray(0)

        drawBreakOverlay(ex, ey, ez)
        drawEntities(ex, ey, ez)
        drawClouds(ex, ey, ez, dt)

        // passe transparente: agua e vidro
        GLES30.glUseProgram(prog)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        for (m in meshes.values) {
            if (m.quads2 == 0) continue
            val ox = (m.cx * 16 - ex).toFloat()
            val oy = (-ey).toFloat()
            val oz = (m.cz * 16 - ez).toFloat()
            if (!visible(ox, oy, oz, ox + 16f, oy + m.maxY, oz + 16f)) continue
            GLES30.glUniform3f(uOrigin, ox, oy, oz)
            GLES30.glBindVertexArray(m.vao2)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.quads2 * 6, GLES30.GL_UNSIGNED_SHORT, 0)
            quadsDrawn += m.quads2
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(0)

        drawHand(dt)
        if (game.input.shotReq) {
            game.input.shotReq = false
            try { capture() } catch (t: Throwable) { Log.e("Blocos", "captura falhou", t) }
        }

        Stats.chunks = drawn
        Stats.quads = quadsDrawn

        frames++
        accNs += rawNs
        if (accNs >= 1_000_000_000L) {
            val fps = frames * 1e9f / accNs
            Stats.fps = fps
            adapt(fps)
            frames = 0
            accNs = 0
        }
    }

    companion object {
        private const val VS = """#version 300 es
layout(location = 0) in uvec4 a_pos;
layout(location = 1) in uvec2 a_tex;
uniform mat4 u_mvp;
uniform vec3 u_origin;
uniform vec2 u_fog;
out vec2 v_uv;
out float v_shade;
out float v_fog;
out float v_em;
void main() {
    vec3 p = vec3(a_pos.xyz) + u_origin;
    gl_Position = u_mvp * vec4(p, 1.0);
    uint tile = a_tex.x & 63u;
    v_em = float((a_tex.x >> 6u) & 1u);
    uint tc = a_tex.y;
    float tu = float(tc & 1u);
    float tv = float((tc >> 1u) & 1u);
    float col = float(tile & 7u);
    float row = float(tile >> 3u);
    const float e = 0.02;
    v_uv = vec2(col + mix(e, 1.0 - e, tu), row + mix(e, 1.0 - e, 1.0 - tv)) / 8.0;
    v_shade = float(a_pos.w) / 255.0;
    v_fog = clamp((length(p) - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);
}
"""
        private const val FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in float v_shade;
in float v_fog;
in float v_em;
uniform sampler2D u_tex;
uniform vec3 u_sky;
uniform float u_light;
out vec4 o_color;
void main() {
    vec4 t = texture(u_tex, v_uv);
    if (t.a < 0.1) discard;
    vec3 rgb = t.rgb / t.a;
    vec3 c = rgb * mix(v_shade * u_light, 1.0, v_em);
    o_color = vec4(mix(c, u_sky, v_fog), t.a);
}
"""
        private const val FVS = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec4 a_col;
uniform mat4 u_mvp;
uniform vec3 u_off;
uniform vec2 u_fog;
out vec4 v_col;
out float v_fog;
void main() {
    vec3 p = a_pos + u_off;
    gl_Position = u_mvp * vec4(p, 1.0);
    v_col = a_col;
    v_fog = clamp((length(p) - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);
}
"""
        private const val FFS = """#version 300 es
precision highp float;
in vec4 v_col;
in float v_fog;
uniform vec3 u_tint;
uniform float u_alpha;
uniform vec3 u_sky;
out vec4 o_color;
void main() {
    o_color = vec4(mix(v_col.rgb * u_tint, u_sky, v_fog), v_col.a * u_alpha);
}
"""
        private const val HVS = """#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in float a_sh;
uniform mat4 u_mvp;
uniform mat4 u_model;
uniform vec2 u_fog;
out vec2 v_uv;
out float v_sh;
out float v_fog;
void main() {
    gl_Position = u_mvp * vec4(a_pos, 1.0);
    v_uv = a_uv;
    v_sh = a_sh;
    v_fog = clamp((length((u_model * vec4(a_pos, 1.0)).xyz) - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);
}
"""
        private const val HFS = """#version 300 es
precision highp float;
in vec2 v_uv;
in float v_sh;
in float v_fog;
uniform sampler2D u_tex;
uniform float u_light;
uniform vec3 u_tint;
uniform vec3 u_sky;
out vec4 o_color;
void main() {
    vec4 t = texture(u_tex, v_uv);
    if (t.a < 0.1) discard;
    vec3 rgb = t.rgb / t.a;
    vec3 c = rgb * v_sh * u_light * u_tint;
    o_color = vec4(mix(c, u_sky, v_fog), 1.0);
}
"""
        private const val LVS = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 u_mvp;
uniform vec3 u_off;
void main() {
    gl_Position = u_mvp * vec4(a_pos + u_off, 1.0);
}
"""
        private const val LFS = """#version 300 es
precision mediump float;
out vec4 o_color;
void main() {
    o_color = vec4(0.0, 0.0, 0.0, 1.0);
}
"""
    }
}
