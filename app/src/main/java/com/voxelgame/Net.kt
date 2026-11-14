package com.voxelgame

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * Outro jogador (de outro aparelho). Os campos t* chegam da rede; x/y/z/yaw/pitch sao suavizados
 * a cada frame por [step] (so a thread do jogo mexe neles) e usados pelo renderizador.
 */
class RemotePlayer(val id: Int, @Volatile var name: String) {
    @Volatile var tx = 0.0
    @Volatile var ty = 0.0
    @Volatile var tz = 0.0
    @Volatile var tyaw = 0f
    @Volatile var tpitch = 0f
    @Volatile var sneaking = false
    @Volatile var dead = false
    @Volatile var attack = 0f
    @Volatile var held = 0
    @Volatile var seen = false

    var x = 0.0
    var y = 0.0
    var z = 0.0
    var yaw = 0f
    var pitch = 0f
    var limbSwing = 0f
    var limbAmount = 0f
    var ageTicks = 0f
    private var init = false

    /** Posicao do nome na tela (escrita pelo renderizador, lida pelo HUD). */
    @Volatile var sx = 0f
    @Volatile var sy = 0f
    @Volatile var sv = false

    val visible: Boolean get() = seen && init && !dead

    fun step(dt: Float) {
        if (!seen) return
        if (!init) {
            x = tx; y = ty; z = tz; yaw = tyaw; pitch = tpitch
            init = true
            return
        }
        val k = min(1.0, 16.0 * dt)
        val ox = x
        val oz = z
        if (abs(tx - x) + abs(tz - z) > 10.0 || abs(ty - y) > 10.0) {
            x = tx; y = ty; z = tz
        } else {
            x += (tx - x) * k
            y += (ty - y) * k
            z += (tz - z) * k
        }
        var dy = (tyaw - yaw).toDouble()
        while (dy > PI) dy -= 2.0 * PI
        while (dy < -PI) dy += 2.0 * PI
        yaw += (dy * k).toFloat()
        pitch += (tpitch - pitch) * k.toFloat()

        // mesma animacao de andar do jogador local (WalkAnimationState)
        val tickF = dt / 0.05f
        val perTick = if (tickF > 0f) hypot(x - ox, z - oz).toFloat() / tickF else 0f
        limbAmount += (min(perTick * 4f, 1f) - limbAmount) * (1f - 0.6f.pow(tickF))
        limbSwing += limbAmount * tickF
        ageTicks += tickF
    }
}

/** Partida encontrada na rede local por [Net.startDiscovery]. */
class FoundHost(val host: String, val name: String, val port: Int) {
    @Volatile var last = 0L
}

/**
 * Multijogador em rede local (Wi-Fi). Um aparelho hospeda (servidor TCP), os outros entram.
 *
 * O mundo base e gerado pela mesma semente nos dois lados, entao so as EDICOES de blocos viajam:
 * ao entrar, o anfitriao manda os chunks editados; depois cada bloco colocado/quebrado e repassado.
 * Posicao, olhar, agachar, golpe e item na mao sao enviados 20x por segundo.
 *
 * Nao depende de Android (so de java.net), para poder ser testado na JVM.
 */
object Net {
    const val PORT = 25566
    const val DISC_PORT = 25567
    private const val VERSION = 1
    const val MAX_PLAYERS = 8
    private const val MAX_FRAME = 1 shl 20

    private const val T_HELLO = 1
    private const val T_WELCOME = 2
    private const val T_REJECT = 3
    private const val T_JOIN = 4
    private const val T_LEAVE = 5
    private const val T_CHUNK = 6
    private const val T_SYNCED = 7
    private const val T_STATE = 8
    private const val T_BLOCK = 9
    private const val T_TIME = 10
    private const val T_KICK = 11

    enum class Mode { OFF, HOST, CLIENT }

    @Volatile var mode = Mode.OFF
        private set

    /** Cliente: ja recebeu o WELCOME. Anfitriao: sempre true. */
    @Volatile var connected = false
        private set

    /** Texto curto para o menu (ex.: "Conectando..."). */
    @Volatile var status = ""
        private set

    @Volatile var myName = "Jogador"
    @Volatile var myId = 0
        private set

    /** Os OUTROS jogadores. No cliente inclui o anfitriao (id 0). */
    val remotes = ConcurrentHashMap<Int, RemotePlayer>()

    /** Cliente: chamado (em outra thread) quando a conexao acaba sem o usuario pedir. */
    @Volatile var onClosed: ((String) -> Unit)? = null

    /** Entrar numa partida: guardado aqui porque a Activity e recriada para trocar de mundo. */
    @Volatile var pendingJoin: Pair<String, Int>? = null

    // ------------------------------------------------------------------ estado interno

    @Volatile private var epoch = 0
    @Volatile private var world: World? = null
    @Volatile private var game: Game? = null
    private var server: ServerSocket? = null
    @Volatile private var srv: Conn? = null
    private val conns = ConcurrentHashMap<Int, Conn>()
    private val nextId = AtomicInteger(1)
    private val tp = AtomicReference<DoubleArray?>(null)
    @Volatile private var timeIn = Float.NaN
    private var lastState = 0L
    private var lastTime = 0L
    private val synced = ArrayList<Long>()

    private class Frame(val type: Int, val data: DataInputStream)

    private class Conn(val sock: Socket) {
        @Volatile var id = -1
        @Volatile var name = ""
        @Volatile var alive = true
        @Volatile var ready = false
        val lock = Any()
        val pending = ArrayList<ByteArray>()
        private val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 8192))
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 8192))
        private val q = LinkedBlockingQueue<ByteArray>()
        private val poison = ByteArray(0)

        fun startWriter() {
            val t = Thread({
                try {
                    while (true) {
                        val p = q.take()
                        if (p === poison) break
                        out.write(p)
                        if (q.isEmpty()) out.flush()
                    }
                } catch (t: Throwable) {
                }
                try { out.flush() } catch (t: Throwable) { }
                close()
            }, "net-writer")
            t.isDaemon = true
            t.start()
        }

        /** Pacotes "descartaveis" (posicao) sao ignorados se a fila estiver cheia. */
        fun send(p: ByteArray, droppable: Boolean = false) {
            if (!alive) return
            val n = q.size
            if (droppable && n > 100) return
            if (n > 20000) { close(); return }
            q.add(p)
        }

        /** Fecha o socket depois de enviar tudo que ja esta na fila. */
        fun finish() { q.add(poison) }

        fun close() {
            alive = false
            try { sock.close() } catch (t: Throwable) { }
        }
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread(body, name)
        t.isDaemon = true
        t.start()
    }

    private inline fun pkt(type: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(64)
        val d = DataOutputStream(bos)
        d.writeInt(0)
        d.writeByte(type)
        body(d)
        d.flush()
        val a = bos.toByteArray()
        val len = a.size - 4
        a[0] = (len ushr 24).toByte()
        a[1] = (len ushr 16).toByte()
        a[2] = (len ushr 8).toByte()
        a[3] = len.toByte()
        return a
    }

    private fun readFrame(inp: DataInputStream): Frame {
        val len = inp.readInt()
        if (len < 1 || len > MAX_FRAME) throw IOException("pacote invalido")
        val b = ByteArray(len)
        inp.readFully(b)
        val d = DataInputStream(ByteArrayInputStream(b))
        return Frame(d.readUnsignedByte(), d)
    }

    private fun clean(s: String): String {
        val t = s.replace('|', ' ').trim()
        return (if (t.length > 16) t.substring(0, 16) else t).ifEmpty { "Jogador" }
    }

    private fun event(msg: String) {
        Stats.message = msg
        Stats.messageUntil = System.currentTimeMillis() + 3500L
    }

    /** Resumo dos blocos/itens/minerios registrados: os dois lados precisam ter os mesmos mods. */
    fun signature(): Int {
        val sb = StringBuilder()
        sb.append(SEED).append(';')
        for (e in Blocks.keyToId.entries.sortedBy { it.key }) sb.append(e.key).append('=').append(e.value).append(',')
        for (o in Blocks.ores) sb.append(o.id).append(':').append(o.minY).append(':').append(o.maxY).append(':')
            .append(o.tries).append(':').append(o.vein).append(',')
        return sb.toString().hashCode()
    }

    // ------------------------------------------------------------------ API publica

    fun playerNames(): List<String> {
        val l = ArrayList<String>()
        l.add(myName)
        for (r in remotes.values.sortedBy { it.id }) l.add(r.name)
        return l
    }

    /** IPs deste aparelho na rede (para mostrar ao anfitriao). */
    fun localIps(): List<String> {
        val r = ArrayList<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return r
            for (ni in java.util.Collections.list(en)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val h = a.hostAddress
                        if (h != null) r.add(h)
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return r
    }

    /** Hospeda o mundo [w]. Devolve null se deu certo, ou a mensagem de erro. */
    @Synchronized
    fun startHost(w: World): String? {
        if (mode != Mode.OFF) return "Você já está numa partida em rede"
        val ss: ServerSocket
        try {
            ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
        } catch (t: Throwable) {
            return "Não consegui abrir a porta $PORT: " + (t.message ?: "")
        }
        epoch++
        val ep = epoch
        server = ss
        world = w
        mode = Mode.HOST
        myId = 0
        connected = true
        status = "Hospedando"
        remotes.clear()
        conns.clear()
        nextId.set(1)
        lastState = 0L
        lastTime = 0L
        w.onLocalSet = { x, y, z, id -> broadcast(blockPkt(x, y, z, id), -1, false) }
        spawn("net-accept") { acceptLoop(ss, ep) }
        spawn("net-announce") { announceLoop(ep) }
        return null
    }

    /** Entra na partida de [host]. O resultado (sucesso ou falha) chega por [status] / [onClosed]. */
    @Synchronized
    fun connect(w: World, host: String, port: Int) {
        if (mode != Mode.OFF) return
        epoch++
        val ep = epoch
        world = w
        mode = Mode.CLIENT
        myId = -1
        connected = false
        status = "Conectando a $host..."
        remotes.clear()
        synced.clear()
        timeIn = Float.NaN
        tp.set(null)
        lastState = 0L
        w.onLocalSet = { x, y, z, id ->
            val c = srv
            if (c != null && connected) c.send(blockPkt(x, y, z, id))
        }
        spawn("net-client") { clientLoop(host, port, ep) }
    }

    /** Sai da rede (anfitriao: derruba todos; cliente: desconecta). Pode ser chamado a qualquer hora. */
    @Synchronized
    fun stop() {
        if (mode == Mode.OFF && server == null && srv == null) return
        epoch++
        val wasHost = mode == Mode.HOST
        mode = Mode.OFF
        connected = false
        try { server?.close() } catch (t: Throwable) { }
        server = null
        if (wasHost) {
            for (c in conns.values) {
                c.send(pkt(T_KICK) { it.writeUTF("O anfitrião encerrou a partida") })
                c.finish()
            }
        }
        conns.clear()
        val s = srv
        if (s != null) {
            s.send(pkt(T_KICK) { it.writeUTF("") })
            s.finish()
        }
        srv = null
        remotes.clear()
        world?.onLocalSet = null
        world = null
        game = null
        status = ""
    }

    private fun fail(msg: String) {
        val wasClient = mode == Mode.CLIENT
        stop()
        if (wasClient) onClosed?.invoke(msg)
    }

    /** Chamado a cada frame pela thread do jogo (Game.update). */
    fun tick(g: Game, dt: Float) {
        if (mode == Mode.OFF) return
        game = g
        val t = tp.getAndSet(null)
        if (t != null) g.teleport(t[0], t[1], t[2])
        val ti = timeIn
        if (!ti.isNaN()) { timeIn = Float.NaN; g.time = ti }
        for (r in remotes.values) r.step(dt)

        val now = System.nanoTime()
        if (now - lastState >= 50_000_000L) {
            lastState = now
            val sel = g.input.selected.coerceIn(0, 8)
            val held = if (g.input.inv.count[sel] > 0) g.input.inv.id[sel] else 0
            val flags = (if (g.sneaking) 1 else 0) or (if (g.dead) 2 else 0)
            val yaw = Math.IEEEremainder(g.yaw, 2.0 * PI).toFloat()
            if (mode == Mode.HOST) {
                broadcast(statePkt(0, g.px, g.py, g.pz, yaw, g.pitch.toFloat(), flags, g.attackProgress, held), -1, true)
            } else if (connected) {
                srv?.send(statePkt(myId, g.px, g.py, g.pz, yaw, g.pitch.toFloat(), flags, g.attackProgress, held), true)
            }
        }
        if (mode == Mode.HOST && now - lastTime >= 2_000_000_000L) {
            lastTime = now
            val tm = g.time
            broadcast(pkt(T_TIME) { it.writeFloat(tm) }, -1, true)
        }
    }

    // ------------------------------------------------------------------ pacotes

    private fun blockPkt(x: Int, y: Int, z: Int, id: Int): ByteArray = pkt(T_BLOCK) {
        it.writeInt(x); it.writeInt(y); it.writeInt(z); it.writeShort(id)
    }

    private fun statePkt(
        id: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float,
        flags: Int, attack: Float, held: Int
    ): ByteArray = pkt(T_STATE) {
        it.writeInt(id)
        it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
        it.writeFloat(yaw); it.writeFloat(pitch)
        it.writeByte(flags)
        it.writeFloat(attack)
        it.writeShort(held)
    }

    /** Manda [p] a todos os clientes menos [except]. Quem ainda esta sincronizando recebe depois. */
    private fun broadcast(p: ByteArray, except: Int, droppable: Boolean) {
        for (c in conns.values) {
            if (c.id == except) continue
            if (!c.ready) {
                synchronized(c.lock) {
                    if (!c.ready) {
                        if (!droppable) c.pending.add(p)
                        return@synchronized
                    }
                    c.send(p, droppable)
                }
                continue
            }
            c.send(p, droppable)
        }
    }

    private fun applyState(
        r: RemotePlayer, x: Double, y: Double, z: Double, yaw: Float, pitch: Float,
        flags: Int, attack: Float, held: Int
    ) {
        if (x.isNaN() || y.isNaN() || z.isNaN() || yaw.isNaN() || pitch.isNaN()) return
        r.tx = x; r.ty = y; r.tz = z
        r.tyaw = yaw; r.tpitch = pitch
        r.sneaking = (flags and 1) != 0
        r.dead = (flags and 2) != 0
        r.attack = attack
        r.held = held
        r.seen = true
    }

    // ------------------------------------------------------------------ anfitriao

    private fun acceptLoop(ss: ServerSocket, ep: Int) {
        while (epoch == ep && !ss.isClosed) {
            val s = try { ss.accept() } catch (t: Throwable) { break }
            spawn("net-serve") { serve(s, ep) }
        }
    }

    private fun serve(s: Socket, ep: Int) {
        var c: Conn? = null
        var id = -1
        try {
            s.tcpNoDelay = true
            s.soTimeout = 30000
            val cn = Conn(s)
            c = cn
            val f = readFrame(cn.inp)
            if (f.type != T_HELLO) throw IOException("esperava HELLO")
            val ver = f.data.readInt()
            val sig = f.data.readInt()
            val name = clean(f.data.readUTF())
            cn.startWriter()
            val w = world
            val g = game
            if (epoch != ep || w == null) { cn.close(); return }
            var why: String? = null
            if (ver != VERSION) why = "Versões do jogo diferentes"
            else if (sig != signature()) why = "Os mods/blocos são diferentes nos dois aparelhos"
            else if (conns.size >= MAX_PLAYERS - 1) why = "A partida está cheia"
            if (why != null) {
                val msg: String = why
                cn.send(pkt(T_REJECT) { it.writeUTF(msg) })
                cn.finish()
                return
            }

            id = nextId.getAndIncrement()
            cn.id = id
            cn.name = name
            conns[id] = cn

            val hx = g?.px ?: 8.5
            val hy = g?.py ?: 80.0
            val hz = g?.pz ?: 8.5
            val tm = g?.time ?: 0f
            val hn = myName
            cn.send(pkt(T_WELCOME) {
                it.writeInt(id); it.writeUTF(hn); it.writeFloat(tm)
                it.writeDouble(hx); it.writeDouble(hy); it.writeDouble(hz)
            })
            cn.send(pkt(T_JOIN) { it.writeInt(0); it.writeUTF(hn) })
            for (r in remotes.values) {
                val rid = r.id
                val rn = r.name
                cn.send(pkt(T_JOIN) { it.writeInt(rid); it.writeUTF(rn) })
            }

            // copia dos chunks editados; o resto o cliente gera pela semente
            val snap = ArrayList<Triple<Int, Int, ByteArray>>()
            for (ch in w.chunks.values) if (ch.edited) snap.add(Triple(ch.cx, ch.cz, ch.blocks.clone()))
            for (t in snap) {
                val bos = ByteArrayOutputStream(4096)
                DeflaterOutputStream(bos).use { it.write(t.third) }
                val data = bos.toByteArray()
                cn.send(pkt(T_CHUNK) {
                    it.writeInt(t.first); it.writeInt(t.second); it.writeInt(data.size); it.write(data)
                })
            }
            cn.send(pkt(T_SYNCED) { })
            synchronized(cn.lock) {
                for (p in cn.pending) cn.send(p)
                cn.pending.clear()
                cn.ready = true
            }

            remotes[id] = RemotePlayer(id, name)
            broadcast(pkt(T_JOIN) { it.writeInt(id); it.writeUTF(name) }, id, false)
            event("$name entrou na partida")

            while (epoch == ep && cn.alive) {
                val fr = readFrame(cn.inp)
                val d = fr.data
                when (fr.type) {
                    T_STATE -> {
                        d.readInt() // o id e o da conexao, nao confiamos no que veio
                        val x = d.readDouble(); val y = d.readDouble(); val z = d.readDouble()
                        val yaw = d.readFloat(); val pitch = d.readFloat()
                        val flags = d.readUnsignedByte()
                        val attack = d.readFloat()
                        val held = d.readUnsignedShort()
                        val r = remotes[id]
                        if (r != null) applyState(r, x, y, z, yaw, pitch, flags, attack, held)
                        broadcast(statePkt(id, x, y, z, yaw, pitch, flags, attack, held), id, true)
                    }
                    T_BLOCK -> {
                        val x = d.readInt(); val y = d.readInt(); val z = d.readInt()
                        val b = d.readUnsignedShort()
                        if (y in 0 until H && b in 0..127) {
                            w.setRemote(x, y, z, b)
                            broadcast(blockPkt(x, y, z, b), id, false)
                        }
                    }
                    T_KICK -> return
                    else -> {}
                }
            }
        } catch (t: Throwable) {
            // cliente caiu: limpeza abaixo
        } finally {
            if (id >= 0) {
                conns.remove(id)
                val r = remotes.remove(id)
                if (r != null && epoch == ep) {
                    event(r.name + " saiu da partida")
                    broadcast(pkt(T_LEAVE) { it.writeInt(r.id) }, id, false)
                }
            }
            c?.close()
        }
    }

    // ------------------------------------------------------------------ cliente

    private fun clientLoop(host: String, port: Int, ep: Int) {
        var c: Conn? = null
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 6000)
            s.tcpNoDelay = true
            s.soTimeout = 30000
            val cn = Conn(s)
            c = cn
            if (epoch != ep) { cn.close(); return }
            srv = cn
            cn.startWriter()
            val nm = myName
            val sig = signature()
            cn.send(pkt(T_HELLO) { it.writeInt(VERSION); it.writeInt(sig); it.writeUTF(nm) })
            while (epoch == ep && cn.alive) handleClient(readFrame(cn.inp), ep)
        } catch (t: Throwable) {
            if (epoch == ep) {
                val m = if (t is SocketTimeoutException) "Tempo esgotado: sem resposta do anfitrião"
                else if (c == null || !connected) "Não consegui conectar em $host (" + (t.message ?: t.javaClass.simpleName) + ")"
                else "Conexão perdida"
                fail(m)
            }
        } finally {
            if (epoch != ep) c?.close()
        }
    }

    private fun handleClient(f: Frame, ep: Int) {
        val d = f.data
        val w = world ?: return
        when (f.type) {
            T_WELCOME -> {
                myId = d.readInt()
                val hn = clean(d.readUTF())
                val tm = d.readFloat()
                val r = RemotePlayer(0, hn)
                r.tx = d.readDouble(); r.ty = d.readDouble(); r.tz = d.readDouble()
                remotes[0] = r
                timeIn = tm
                status = "Sincronizando mundo..."
                connected = true
            }
            T_REJECT -> fail("Recusado: " + d.readUTF())
            T_KICK -> {
                val m = d.readUTF()
                fail(if (m.isEmpty()) "Desconectado" else m)
            }
            T_JOIN -> {
                val id = d.readInt()
                val name = clean(d.readUTF())
                if (id != myId) {
                    val r = remotes[id]
                    if (r != null) r.name = name else remotes[id] = RemotePlayer(id, name)
                }
            }
            T_LEAVE -> remotes.remove(d.readInt())
            T_CHUNK -> {
                val cx = d.readInt()
                val cz = d.readInt()
                val len = d.readInt()
                if (len <= 0 || len > MAX_FRAME) throw IOException("chunk invalido")
                val data = ByteArray(len)
                d.readFully(data)
                val blocks = ByteArray(16 * 16 * H)
                DataInputStream(InflaterInputStream(ByteArrayInputStream(data))).readFully(blocks)
                val key = chunkKey(cx, cz)
                val old = w.chunks[key]
                if (old != null) {
                    System.arraycopy(blocks, 0, old.blocks, 0, blocks.size)
                    old.edited = true
                } else {
                    val nc = Chunk(cx, cz)
                    System.arraycopy(blocks, 0, nc.blocks, 0, blocks.size)
                    nc.edited = true
                    w.chunks[key] = nc
                }
                synced.add(key)
                remesh(w, key)
            }
            T_SYNCED -> {
                val h = remotes[0]
                if (h != null) tp.set(doubleArrayOf(h.tx, h.ty, h.tz))
                status = "Conectado a " + (h?.name ?: "anfitrião")
                event(status)
                // refaz as malhas um pouco depois: algum chunk pode estar sendo montado com o dado antigo
                val keys = ArrayList<Long>(synced)
                spawn("net-remesh") {
                    try { Thread.sleep(1500) } catch (t: Throwable) { }
                    if (epoch == ep) for (k in keys) remesh(w, k)
                }
            }
            T_STATE -> {
                val id = d.readInt()
                val x = d.readDouble(); val y = d.readDouble(); val z = d.readDouble()
                val yaw = d.readFloat(); val pitch = d.readFloat()
                val flags = d.readUnsignedByte()
                val attack = d.readFloat()
                val held = d.readUnsignedShort()
                if (id != myId) {
                    val r = remotes[id]
                    if (r != null) applyState(r, x, y, z, yaw, pitch, flags, attack, held)
                }
            }
            T_BLOCK -> {
                val x = d.readInt(); val y = d.readInt(); val z = d.readInt()
                val b = d.readUnsignedShort()
                if (y in 0 until H && b in 0..127) w.setRemote(x, y, z, b)
            }
            T_TIME -> timeIn = d.readFloat()
            else -> {}
        }
    }

    private fun remesh(w: World, key: Long) {
        val cx = keyX(key)
        val cz = keyZ(key)
        for (dx in -1..1) for (dz in -1..1) w.remeshQueue.add(chunkKey(cx + dx, cz + dz))
    }

    // ------------------------------------------------------------------ descoberta (UDP broadcast)

    private val found = ConcurrentHashMap<String, FoundHost>()
    private var discSock: DatagramSocket? = null

    /** Partidas vistas nos ultimos 5 s. */
    fun hosts(): List<FoundHost> {
        val now = System.currentTimeMillis()
        return found.values.filter { now - it.last < 5000L }.sortedBy { it.name }
    }

    @Synchronized
    fun startDiscovery() {
        if (discSock != null) return
        found.clear()
        val ds: DatagramSocket
        try {
            ds = DatagramSocket(null)
            ds.reuseAddress = true
            ds.broadcast = true
            ds.bind(InetSocketAddress(DISC_PORT))
            ds.soTimeout = 700
        } catch (t: Throwable) {
            return
        }
        discSock = ds
        spawn("net-discover") {
            val buf = ByteArray(256)
            while (discSock === ds && !ds.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                    val ip = p.address?.hostAddress
                    val port = if (parts.size >= 4) parts[2].toIntOrNull() else null
                    if (parts.size >= 4 && parts[0] == "BLOCOS1" && ip != null && port != null) {
                        val fh = found[ip] ?: FoundHost(ip, parts[1], port).also { found[ip] = it }
                        fh.last = System.currentTimeMillis()
                    }
                } catch (t: SocketTimeoutException) {
                } catch (t: Throwable) {
                    if (ds.isClosed) break
                }
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        try { discSock?.close() } catch (t: Throwable) { }
        discSock = null
    }

    private fun broadcastAddrs(): List<InetAddress> {
        val r = ArrayList<InetAddress>()
        try { r.add(InetAddress.getByName("255.255.255.255")) } catch (t: Throwable) { }
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            if (en != null) {
                for (ni in java.util.Collections.list(en)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val b = ia.broadcast
                        if (b != null && !r.contains(b)) r.add(b)
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return r
    }

    /** O anfitriao avisa a rede a cada 1,2 s; quem esta na tela "Procurar partidas" ve o nome aparecer. */
    private fun announceLoop(ep: Int) {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket()
            ds.broadcast = true
            while (epoch == ep) {
                val data = ("BLOCOS1|" + clean(myName) + "|" + PORT + "|" + VERSION).toByteArray(Charsets.UTF_8)
                for (a in broadcastAddrs()) {
                    try { ds.send(DatagramPacket(data, data.size, a, DISC_PORT)) } catch (t: Throwable) { }
                }
                Thread.sleep(1200)
            }
        } catch (t: Throwable) {
        } finally {
            try { ds?.close() } catch (t: Throwable) { }
        }
    }
}
