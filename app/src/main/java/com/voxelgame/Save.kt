package com.voxelgame

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class SavedState(
    val px: Double, val py: Double, val pz: Double,
    val yaw: Double, val pitch: Double, val time: Float, val selected: Int,
    val ids: IntArray, val counts: IntArray,
    val flying: Boolean, val creative: Boolean,
    val health: Float, val food: Int, val sat: Float
)

/** Salva so os chunks editados + jogador. O resto e regerado pela semente. */
object Save {
    private const val MAGIC = 0x424C4F33

    @Synchronized
    fun save(file: File, world: World, game: Game, input: Input, withChunks: Boolean = true) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeDouble(game.px)
                out.writeDouble(game.py)
                out.writeDouble(game.pz)
                out.writeDouble(game.yaw)
                out.writeDouble(game.pitch)
                out.writeFloat(game.time)
                out.writeInt(input.selected)
                for (i in 0 until 36) { out.writeInt(input.inv.id[i]); out.writeInt(input.inv.count[i]) }
                out.writeBoolean(game.flying)
                out.writeBoolean(game.creative)
                out.writeFloat(game.health)
                out.writeInt(game.food)
                out.writeFloat(game.sat)
                // no multijogador (cliente) os chunks pertencem ao anfitriao: salva so o jogador
                val edited = if (withChunks) world.chunks.values.filter { it.edited } else emptyList()
                out.writeInt(edited.size)
                for (c in edited) {
                    out.writeInt(c.cx)
                    out.writeInt(c.cz)
                    val bos = ByteArrayOutputStream()
                    DeflaterOutputStream(bos).use { it.write(c.blocks) }
                    val data = bos.toByteArray()
                    out.writeInt(data.size)
                    out.write(data)
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.e("Blocos", "falha ao salvar", t)
        }
    }

    fun load(file: File, world: World): SavedState? {
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                if (inp.readInt() != MAGIC) throw IOException("formato desconhecido")
                val px = inp.readDouble()
                val py = inp.readDouble()
                val pz = inp.readDouble()
                val yaw = inp.readDouble()
                val pitch = inp.readDouble()
                val time = inp.readFloat()
                val selected = inp.readInt()
                val ids = IntArray(36)
                val counts = IntArray(36)
                for (i in 0 until 36) { ids[i] = inp.readInt(); counts[i] = inp.readInt().coerceIn(0, 64) }
                val flying = inp.readBoolean()
                val creative = inp.readBoolean()
                val health = inp.readFloat()
                val food = inp.readInt().coerceIn(0, 20)
                val sat = inp.readFloat()
                val n = inp.readInt()
                for (i in 0 until n) {
                    val cx = inp.readInt()
                    val cz = inp.readInt()
                    val len = inp.readInt()
                    if (len <= 0 || len > 2_000_000) throw IOException("chunk invalido")
                    val data = ByteArray(len)
                    inp.readFully(data)
                    val c = Chunk(cx, cz)
                    DataInputStream(InflaterInputStream(ByteArrayInputStream(data))).readFully(c.blocks)
                    c.edited = true
                    world.chunks[chunkKey(cx, cz)] = c
                }
                SavedState(px, py, pz, yaw, pitch, time, selected, ids, counts, flying, creative, health, food, sat)
            }
        } catch (t: Throwable) {
            Log.e("Blocos", "falha ao carregar", t)
            null
        }
    }
}
