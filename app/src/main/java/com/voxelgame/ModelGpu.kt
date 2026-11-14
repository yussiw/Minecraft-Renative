package com.voxelgame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Desenha o ModelAsset na GPU com skinning. As matrizes das juntas vao numa textura RGBA32F
 * (4 texels por matriz), entao nao ha limite de juntas por uniform.
 */
class SkinnedModelGpu(val asset: ModelAsset) {
    private var prog = 0
    private var uMvp = 0
    private var uModel = 0
    private var uFog = 0
    private var uLight = 0
    private var uTint = 0
    private var uSky = 0
    private var uBase = 0
    private var uMode = 0
    private var uCut = 0
    private var uTex = 0
    private var uPal = 0
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var palTex = 0
    private var white = 0
    private val texIds = ArrayList<Int>()
    private val matTex = IntArray(asset.materials.size)
    private val palBuf: FloatBuffer =
        ByteBuffer.allocateDirect(asset.palSize * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mvp = FloatArray(16)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader modelo: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    fun create() {
        prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, compile(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(prog, compile(GLES30.GL_FRAGMENT_SHADER, FS))
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link modelo: " + GLES30.glGetProgramInfoLog(prog))
        fun u(n: String) = GLES30.glGetUniformLocation(prog, n)
        uMvp = u("u_mvp"); uModel = u("u_model"); uFog = u("u_fog"); uLight = u("u_light")
        uTint = u("u_tint"); uSky = u("u_sky"); uBase = u("u_base"); uMode = u("u_mode")
        uCut = u("u_cut"); uTex = u("u_tex"); uPal = u("u_pal")

        // geometria
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        val va = IntArray(1)
        GLES30.glGenVertexArrays(1, va, 0)
        vao = va[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        asset.vertData.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxOf(1, asset.vertCount) * 56, asset.vertData, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 56, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 56, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 56, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_SHORT, false, 56, 32)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 56, 40)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        asset.idxData.position(0)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, asset.idxData.capacity(), asset.idxData, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        // textura das juntas
        val t = IntArray(2)
        GLES30.glGenTextures(2, t, 0)
        palTex = t[0]
        white = t[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, palTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 4, asset.palSize, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)

        // textura branca (materiais sem textura)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, white)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(byteArrayOf(-1, -1, -1, -1)).position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)

        // texturas dos materiais (cache por imagem + wrap)
        val cache = HashMap<String, Int>()
        for (i in asset.materials.indices) {
            val m = asset.materials[i]
            val bytes = if (m.image >= 0 && m.image < asset.glb.images.size) asset.glb.images[m.image] else null
            if (bytes == null) { matTex[i] = white; continue }
            val key = "${m.image}:${m.wrapS}:${m.wrapT}"
            matTex[i] = cache.getOrPut(key) { loadTexture(bytes, m.wrapS, m.wrapT) }
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun wrap(w: Int) = if (w == 33071 || w == 33648) w else GLES30.GL_REPEAT

    private fun loadTexture(bytes: ByteArray, ws: Int, wt: Int): Int {
        val bo = BitmapFactory.Options()
        bo.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        var sample = 1
        while (bo.outWidth / sample > 2048 || bo.outHeight / sample > 2048) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return white
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        texIds.add(t[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap(ws))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap(wt))
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        bmp.recycle()
        return t[0]
    }

    /** base = projecao*view (relativo a camera); model = transformacao do modelo (ja com escala). */
    fun draw(
        base: FloatArray, model: FloatArray, palette: FloatArray,
        light: Float, fogS: Float, fogE: Float, tr: Float, tg: Float, tb: Float, sky: FloatArray
    ) {
        if (prog == 0 || asset.draws.isEmpty()) return
        GLES30.glUseProgram(prog)
        Matrix.multiplyMM(mvp, 0, base, 0, model, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform2f(uFog, fogS, fogE)
        GLES30.glUniform1f(uLight, light)
        GLES30.glUniform3f(uTint, tr, tg, tb)
        GLES30.glUniform3f(uSky, sky[0], sky[1], sky[2])
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform1i(uPal, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, palTex)
        palBuf.position(0)
        palBuf.put(palette, 0, asset.palSize * 16)
        palBuf.position(0)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 4, asset.palSize, GLES30.GL_RGBA, GLES30.GL_FLOAT, palBuf)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        GLES30.glBindVertexArray(vao)
        for (d in asset.draws) {
            val mi = d.material
            val m = if (mi >= 0 && mi < asset.materials.size) asset.materials[mi] else null
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (m != null) matTex[mi] else white)
            if (m != null) GLES30.glUniform4f(uBase, m.color[0], m.color[1], m.color[2], m.color[3])
            else GLES30.glUniform4f(uBase, 1f, 1f, 1f, 1f)
            val mode = m?.mode ?: 0
            GLES30.glUniform1i(uMode, mode)
            GLES30.glUniform1f(uCut, m?.cutoff ?: 0.5f)
            if (m == null || m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE) else GLES30.glEnable(GLES30.GL_CULL_FACE)
            if (mode == 2) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.count, GLES30.GL_UNSIGNED_INT, d.first * 4)
            if (mode == 2) {
                GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glDepthMask(true)
            }
        }
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        try {
            if (prog != 0) GLES30.glDeleteProgram(prog)
            if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
            val all = ArrayList<Int>(texIds)
            all.add(palTex)
            all.add(white)
            GLES30.glDeleteTextures(all.size, all.toIntArray(), 0)
        } catch (t: Throwable) { }
        prog = 0
    }

    companion object {
        private const val VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_nrm;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec4 a_joints;
layout(location = 4) in vec4 a_weights;
uniform mat4 u_mvp;
uniform mat4 u_model;
uniform vec2 u_fog;
uniform highp sampler2D u_pal;
out vec2 v_uv;
out float v_sh;
out float v_fog;
mat4 jm(float j) {
    int i = int(j + 0.5);
    return mat4(texelFetch(u_pal, ivec2(0, i), 0), texelFetch(u_pal, ivec2(1, i), 0),
                texelFetch(u_pal, ivec2(2, i), 0), texelFetch(u_pal, ivec2(3, i), 0));
}
void main() {
    mat4 sk = a_weights.x * jm(a_joints.x) + a_weights.y * jm(a_joints.y)
            + a_weights.z * jm(a_joints.z) + a_weights.w * jm(a_joints.w);
    vec4 p = sk * vec4(a_pos, 1.0);
    vec3 n = mat3(sk) * a_nrm;
    gl_Position = u_mvp * p;
    vec3 wn = normalize(mat3(u_model) * n);
    float sh = wn.y >= 0.0 ? mix(0.8, 1.0, wn.y) : mix(0.8, 0.5, -wn.y);
    v_sh = sh * (1.0 - 0.15 * abs(wn.x));
    v_uv = a_uv;
    v_fog = clamp((length((u_model * p).xyz) - u_fog.x) / (u_fog.y - u_fog.x), 0.0, 1.0);
}
"""
        private const val FS = """#version 300 es
precision highp float;
in vec2 v_uv;
in float v_sh;
in float v_fog;
uniform sampler2D u_tex;
uniform vec4 u_base;
uniform int u_mode;
uniform float u_cut;
uniform float u_light;
uniform vec3 u_tint;
uniform vec3 u_sky;
out vec4 o_color;
void main() {
    vec4 t = texture(u_tex, v_uv);
    vec3 rgb = t.a > 0.0 ? t.rgb / t.a : t.rgb;
    float a = t.a * u_base.a;
    if (u_mode == 1 && a < u_cut) discard;
    if (u_mode == 2 && a < 0.01) discard;
    vec3 c = rgb * u_base.rgb * v_sh * u_light * u_tint;
    o_color = vec4(mix(c, u_sky, v_fog), u_mode == 2 ? a : 1.0);
}
"""
    }
}
