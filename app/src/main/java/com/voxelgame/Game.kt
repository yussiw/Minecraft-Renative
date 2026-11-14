package com.voxelgame

import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fisica e regras seguindo os valores da wiki do Minecraft Java:
 * andar 4,317 b/s, correr 5,612, agachar 1,295, voar 10,89; pulo ~1,25 bloco;
 * dano de queda = distancia - 3; fome/exaustao/regeneracao como no Java.
 */
class Game(val world: World, val input: Input) {
    companion object {
        const val HW = 0.3
        const val EYE = 1.62
        const val EYE_SNEAK = 1.27
        const val DAY_LEN = 1200f // 20 min, como no jogo
        const val GRAV = 28.2
    }

    var px = 8.5
    var py = 80.0
    var pz = 8.5
    private var vx = 0.0
    private var vy = 0.0
    private var vz = 0.0
    var yaw = 0.0
    var pitch = -0.25
    var onGround = false
        private set
    val velY: Double get() = vy
    var hSpeed = 0f
    private var bodyH = 1.8
    val eye: Double get() = if (sneaking) EYE_SNEAK else EYE

    /** 0 = amanhecer (6:00), 0.25 = meio-dia, 0.5 = por do sol, 0.75 = meia-noite. */
    var time = 0.02f
    var flying = false
    var inWater = false
    var underwater = false
    var sneaking = false
    var sprinting = false
    var creative = false
    var dead = false

    var health = 20f
    var food = 20
    var sat = 5f
    private var exh = 0f
    private var regenT = 0f
    private var air = 15f
    private var drownT = 0f
    private var hurtFlash = 0f
    private var fallDist = 0.0

    var hitValid = false
    var hx = 0
    var hy = 0
    var hz = 0
    private var hnx = 0
    private var hny = 0
    private var hnz = 0
    var breakProg = 0f
    private var brX = 0
    private var brY = -1
    private var brZ = 0
    private var breakCool = 0f
    private var useCool = 0f
    var swing = 0f

    // itens soltos, particulas e animacao do modelo do jogador
    val drops = ArrayList<ItemEntity>()
    val particles = Particles()
    var limbSwing = 0f
    var limbAmount = 0f
    var ageTicks = 0f
    private var mergeT = 0f
    private var hitPartT = 0f

    /** Progresso do golpe para o modelo (0 = parado, 0..1 durante o golpe). */
    val attackProgress: Float get() = if (swing > 0f) 1f - swing else 0f

    private var jumpPrev = false
    private var lastJumpTap = 0L
    private var lastMin = -1
    private val look = FloatArray(2)
    private val rnd = Random()

    init { respawn() }

    fun respawn() {
        world.chunk(0, 0)
        var y = H - 1
        while (y > 0 && world.get(8, y, 8).let { it <= 0 || Blocks.isPlant(it) }) y--
        px = 8.5; pz = 8.5; py = y + 1.0
        vx = 0.0; vy = 0.0; vz = 0.0
        fallDist = 0.0
    }

    /** Multijogador: leva o jogador para (x, y, z), subindo se o ponto estiver dentro de um bloco. */
    fun teleport(x: Double, y: Double, z: Double) {
        world.chunk(floor(x / 16.0).toInt(), floor(z / 16.0).toInt())
        px = x; pz = z
        var ny = y
        var n = 0
        while (n < 48 && collides(px, ny, pz)) { ny += 1.0; n++ }
        py = ny
        vx = 0.0; vy = 0.0; vz = 0.0
        fallDist = 0.0
    }

    private fun revive() {
        health = 20f; food = 20; sat = 5f; exh = 0f; air = 15f
        dead = false
        flying = false
        respawn()
        ScriptHost.simple("respawn")
    }

    fun restore(s: SavedState) {
        px = s.px; py = s.py; pz = s.pz
        yaw = s.yaw; pitch = s.pitch
        time = s.time
        flying = s.flying
        creative = s.creative
        health = s.health; food = s.food; sat = s.sat
        vx = 0.0; vy = 0.0; vz = 0.0
        world.chunk(floor(px / 16.0).toInt(), floor(pz / 16.0).toInt())
        input.selected = s.selected.coerceIn(0, 8)
        for (i in 0 until 36) { input.inv.id[i] = s.ids[i]; input.inv.count[i] = s.counts[i] }
        if (health <= 0f) { health = 20f; food = 20 }
    }

    private fun solidAt(b: Int): Boolean = b < 0 || (b != 0 && b != Blocks.WATER && !Blocks.isPlant(b))

    private fun collides(x: Double, y: Double, z: Double, hgt: Double = bodyH): Boolean {
        val x0 = floor(x - HW).toInt()
        val x1 = floor(x + HW - 1e-7).toInt()
        val y0 = floor(y).toInt()
        val y1 = floor(y + hgt - 1e-7).toInt()
        val z0 = floor(z - HW).toInt()
        val z1 = floor(z + HW - 1e-7).toInt()
        for (bx in x0..x1) for (bz in z0..z1) for (by in y0..y1) {
            if (solidAt(world.get(bx, by, bz))) return true
        }
        return false
    }

    /** Ha chao sob o corpo? (usado pelo agachar para nao cair da borda) */
    private fun supported(x: Double, y: Double, z: Double): Boolean {
        val x0 = floor(x - HW).toInt()
        val x1 = floor(x + HW - 1e-7).toInt()
        val z0 = floor(z - HW).toInt()
        val z1 = floor(z + HW - 1e-7).toInt()
        val by = floor(y - 0.05).toInt()
        for (bx in x0..x1) for (bz in z0..z1) if (solidAt(world.get(bx, by, bz))) return true
        return false
    }

    private fun canHop(nx: Double, nz: Double, intent: Boolean, alongX: Boolean): Boolean {
        if (!intent || flying || sneaking || !(onGround || inWater)) return false
        return if (alongX) !collides(nx, py + 1.1, pz) else !collides(px, py + 1.1, nz)
    }

    private fun moveX(d: Double, intent: Boolean) {
        if (d == 0.0) return
        val nx = px + d
        if (sneaking && onGround && !flying && !supported(nx, py, pz)) { vx = 0.0; return }
        if (!collides(nx, py, pz)) { px = nx; return }
        if (canHop(nx, pz, intent, true)) { vy = if (inWater) 6.5 else 8.4; onGround = false; return }
        vx = 0.0
        px = if (d > 0) floor(nx + HW) - HW - 1e-6 else floor(nx - HW) + 1.0 + HW + 1e-6
    }

    private fun moveZ(d: Double, intent: Boolean) {
        if (d == 0.0) return
        val nz = pz + d
        if (sneaking && onGround && !flying && !supported(px, py, nz)) { vz = 0.0; return }
        if (!collides(px, py, nz)) { pz = nz; return }
        if (canHop(px, nz, intent, false)) { vy = if (inWater) 6.5 else 8.4; onGround = false; return }
        vz = 0.0
        pz = if (d > 0) floor(nz + HW) - HW - 1e-6 else floor(nz - HW) + 1.0 + HW + 1e-6
    }

    private fun moveY(d: Double) {
        val ny = py + d
        if (!collides(px, ny, pz)) {
            py = ny
            if (d != 0.0) onGround = false
            if (d < 0) fallDist += -d
            return
        }
        if (d < 0) {
            py = floor(ny) + 1.0
            onGround = true
            landed()
        } else {
            py = floor(ny + bodyH) - bodyH - 1e-6
        }
        vy = 0.0
    }

    private fun landed() {
        if (fallDist > 3.0 && !creative && !flying && !inWater) hurt(ceil(fallDist - 3.0).toFloat())
        fallDist = 0.0
        if (flying) flying = false
    }

    private fun hurt(amount: Float) {
        if (creative || dead || amount <= 0f) return
        val a = ScriptHost.hurt(amount, health)   // scripts podem cancelar (<= 0) ou mudar o dano
        if (a <= 0f) return
        health -= a
        exh += 0.1f
        hurtFlash = 0.5f
        if (health <= 0f) {
            health = 0f
            dead = true
            input.attack = false
            ScriptHost.simple("death")
        }
    }

    private fun toggleFly() {
        flying = !flying
        vy = 0.0
        fallDist = 0.0
    }

    private var scriptsStarted = false

    fun update(dt: Float) {
        if (!scriptsStarted) { scriptsStarted = true; ScriptHost.start(this) }
        Net.tick(this, dt) // multijogador (nao faz nada fora de uma partida em rede)
        // eventos validos mesmo com o jogo pausado
        if (input.modeToggle) {
            input.modeToggle = false
            creative = !creative
            flying = false
            if (creative) { health = 20f; food = 20; sat = 5f }
        }
        if (input.respawnReq) { input.respawnReq = false; if (dead) revive() }
        if (input.camToggle) { input.camToggle = false; Stats.camMode = (Stats.camMode + 1) % 3 }
        if (input.paused || dead) { publish(); return }

        input.takeLook(look)
        yaw -= look[0].toDouble()
        pitch = (pitch - look[1].toDouble()).coerceIn(-1.54, 1.54)

        time = (time + dt / DAY_LEN) % 1f
        hurtFlash = max(0f, hurtFlash - dt)
        swing = max(0f, swing - dt * 3.4f)

        val dsel = input.hotbarDelta
        if (dsel != 0) { input.hotbarDelta = 0; input.selected = ((input.selected + dsel) % 9 + 9) % 9 }
        val sel = input.selected.coerceIn(0, 8)
        if (input.dropReq) {
            input.dropReq = false
            if (input.inv.count[sel] > 0) {
                val did = input.inv.id[sel]
                input.inv.take(sel, 1)
                throwItem(did)
            }
        }

        // pulo duplo = alternar voo (criativo)
        val nowN = System.nanoTime()
        if (input.jump && !jumpPrev) {
            if (creative && nowN - lastJumpTap < 300_000_000L) { toggleFly(); lastJumpTap = 0L } else lastJumpTap = nowN
        }
        jumpPrev = input.jump
        if (input.flyToggle) { input.flyToggle = false; if (creative) toggleFly() }
        if (!creative) flying = false

        val fx = floor(px).toInt()
        val fz = floor(pz).toInt()
        inWater = world.get(fx, floor(py + 0.5).toInt(), fz) == Blocks.WATER
        underwater = world.get(fx, floor(py + eye).toInt(), fz) == Blocks.WATER
        if (inWater || flying) fallDist = 0.0

        // agachar
        val wantSneak = input.sneak && !flying
        if (wantSneak) sneaking = true
        else if (sneaking && !collides(px, py, pz, 1.8)) sneaking = false
        bodyH = if (sneaking) 1.5 else 1.8

        val mx = input.moveX.toDouble()
        val my = input.moveY.toDouble()
        val mag = min(1.0, hypot(mx, my))
        val canSprint = creative || food > 6
        val wantSprint = input.sprint || (input.stickFlick && my > 0.5)
        sprinting = my > 0.3 && wantSprint && canSprint && !sneaking

        var speed = when {
            flying -> if (sprinting) 21.6 else 10.89
            sneaking -> 1.295
            sprinting -> 5.612
            else -> 4.317
        }
        if (inWater && !flying) speed *= 0.5

        val sy = sin(yaw)
        val cy = cos(yaw)
        var wx = cy * mx - sy * my
        var wz = -sy * mx - cy * my
        val wl = hypot(wx, wz)
        if (wl > 1.0) { wx /= wl; wz /= wl }
        val dtd = dt.toDouble()
        val k = min(1.0, (if (flying) 8.0 else if (onGround) 16.0 else 5.0) * dtd)
        vx += (wx * speed - vx) * k
        vz += (wz * speed - vz) * k

        if (input.jump && onGround && !flying && !inWater) {
            vy = 8.4
            onGround = false
            if (!creative) exh += if (sprinting) 0.2f else 0.05f
        }

        val ox = px
        val oz = pz
        val steps = ceil(dtd / 0.008).toInt().coerceIn(1, 10)
        val h = dtd / steps
        val intent = mag > 0.15
        val flyDown = input.down || (input.sneak && flying)
        for (i in 0 until steps) {
            if (flying) {
                val up = (if (input.jump) 1.0 else 0.0) - (if (flyDown) 1.0 else 0.0)
                vy += (up * 9.0 - vy) * min(1.0, 8.0 * h)
            } else if (inWater) {
                val target = if (input.jump) 3.6 else -1.8
                vy += (target - vy) * min(1.0, 5.0 * h)
            } else {
                vy = max(vy - GRAV * h, -78.0)
            }
            moveX(vx * h, intent)
            moveZ(vz * h, intent)
            moveY(vy * h)
        }
        if (sprinting && !creative) exh += (0.1 * hypot(px - ox, pz - oz)).toFloat()

        // animacao de andar do modelo (WalkAnimationState: velocidade suavizada, 1 tick = 0,05 s)
        val tickF = dt / 0.05f
        val perTick = if (tickF > 0f) hypot(px - ox, pz - oz).toFloat() / tickF else 0f
        limbAmount += (min(perTick * 4f, 1f) - limbAmount) * (1f - 0.6f.pow(tickF))
        limbSwing += limbAmount * tickF
        hSpeed += (hypot(px - ox, pz - oz).toFloat() / max(dt, 1e-4f) - hSpeed) * min(1f, 12f * dt)
        ageTicks += tickF
        if (py < -30) { if (creative) respawn() else { hurt(4f); respawn() } }

        survivalTick(dt)
        touchDamage(dt)
        actCool = max(0f, actCool - dt)
        ScriptHost.tick(dt)

        // interacao com o mundo
        raycast(if (creative) 5.0 else 4.5)
        doBreak(dt)
        if (input.useReq) { input.useReq = false; use() }
        useCool -= dt
        if (input.useHeld && useCool <= 0f) { useCool = 0.25f; use() }
        if (input.pickReq) { input.pickReq = false; pick() }

        updateDrops(dt)
        particles.update(world, dt)

        publish()
    }

    private fun survivalTick(dt: Float) {
        if (creative) { air = 15f; return }
        if (exh >= 4f) {
            exh -= 4f
            if (sat > 0f) sat = max(0f, sat - 1f) else food = max(0, food - 1)
        }
        if (sat > 0f && food >= 20 && health < 20f) {
            regenT += dt
            if (regenT >= 0.5f) {
                regenT = 0f
                val a = min(sat, 6f)
                health = min(20f, health + a / 6f)
                exh += a
            }
        } else if (food >= 18 && health < 20f) {
            regenT += dt
            if (regenT >= 4f) { regenT = 0f; health = min(20f, health + 1f); exh += 6f }
        } else if (food <= 0) {
            regenT += dt
            if (regenT >= 4f) { regenT = 0f; if (health > 1f) hurt(1f) }
        } else regenT = 0f

        if (underwater) {
            air -= dt
            if (air <= 0f) {
                air = 0f
                drownT += dt
                if (drownT >= 1f) { drownT = 0f; hurt(2f) }
            }
        } else {
            air = min(15f, air + dt * 4f)
            drownT = 0f
        }
    }

    private fun doBreak(dt: Float) {
        if (input.attack && hitValid && hy > 0) {
            if (hx != brX || hy != brY || hz != brZ) { brX = hx; brY = hy; brZ = hz; breakProg = 0f }
            if (swing <= 0f) swing = 1f
            if (breakCool > 0f) { breakCool -= dt; return }
            val b = world.get(hx, hy, hz)
            val t = if (creative) 0f else Blocks.hardness(b) * 1.5f / toolMul(b)
            if (t <= 0f) {
                breakBlock(hx, hy, hz)
                breakCool = 0.25f
            } else {
                breakProg += dt / t
                // particula na face minerada a cada 4 ticks
                hitPartT += dt
                if (hitPartT >= 0.2f) {
                    hitPartT = 0f
                    particles.hit(hx, hy, hz, hnx, hny, hnz, Blocks.particleTile(b))
                }
                if (breakProg >= 1f) { breakBlock(hx, hy, hz); breakProg = 0f; breakCool = 0.25f }
            }
        } else {
            breakProg = 0f
            hitPartT = 0f
            brY = -1
            if (breakCool > 0f) breakCool -= dt
            if (input.attack && swing <= 0f) swing = 1f
        }
    }

    private fun breakBlock(x: Int, y: Int, z: Int) {
        val b = world.get(x, y, z)
        if (b <= 0) return
        if (!ScriptHost.blockBreak(b, x, y, z)) return
        particles.burst(x, y, z, Blocks.particleTile(b))
        world.set(x, y, z, 0)
        if (!creative) {
            exh += 0.005f
            val d = Blocks.drop(b, rnd.nextFloat())
            if (d != 0) popDrop(d, Blocks.dropCount(b), x, y, z)
        }
        val up = world.get(x, y + 1, z)
        if (Blocks.isPlant(up)) {
            particles.burst(x, y + 1, z, Blocks.particleTile(up))
            world.set(x, y + 1, z, 0)
            if (!creative && up != 14) popDrop(up, 1, x, y + 1, z)
        }
    }

    /** Solta um item no mundo como no Java: pulinho aleatorio e 0,5 s ate poder ser coletado. */
    private fun popDrop(id: Int, n: Int, x: Int, y: Int, z: Int) {
        if (id <= 0 || n <= 0) return
        drops.add(
            ItemEntity(
                id, n,
                x + 0.5 + (rnd.nextDouble() - 0.5) * 0.5,
                y + 0.5 + (rnd.nextDouble() - 0.5) * 0.5 - ItemEntity.HALF,
                z + 0.5 + (rnd.nextDouble() - 0.5) * 0.5,
                (rnd.nextDouble() * 0.2 - 0.1) * 20.0, 4.0, (rnd.nextDouble() * 0.2 - 0.1) * 20.0,
                0.5f
            )
        )
        Drops.limit(drops)
    }

    /** Tecla Q: joga 1 item na direcao do olhar (0,3 b/tick), coletavel de novo so depois de 2 s. */
    private fun throwItem(id: Int) {
        val cp = cos(pitch)
        val dx = -sin(yaw) * cp
        val dz = -cos(yaw) * cp
        val dy = sin(pitch)
        val a = rnd.nextDouble() * 6.283185307
        val r = 0.02 * rnd.nextDouble()
        drops.add(
            ItemEntity(
                id, 1, px, py + eye - 0.3, pz,
                (dx * 0.3 + cos(a) * r) * 20.0,
                (dy * 0.3 + 0.1 + (rnd.nextDouble() - rnd.nextDouble()) * 0.1) * 20.0,
                (dz * 0.3 + sin(a) * r) * 20.0,
                2.0f
            )
        )
        Drops.limit(drops)
    }

    /** Fisica, juncao e coleta dos itens soltos (caixa de coleta = jogador + 1 bloco nos lados, 0,5 em cima/baixo). */
    private fun updateDrops(dt: Float) {
        if (drops.isEmpty()) return
        mergeT += dt
        if (mergeT >= 0.25f) { mergeT = 0f; Drops.merge(drops) }
        val x0 = px - HW - 1.0
        val x1 = px + HW + 1.0
        val y0 = py - 0.5
        val y1 = py + bodyH + 0.5
        val z0 = pz - HW - 1.0
        val z1 = pz + HW + 1.0
        val it = drops.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.age += dt
            if (e.collectT >= 0f) {
                e.collectT += dt
                if (e.collectT >= 0.15f) it.remove()
                continue
            }
            if (e.age >= ItemEntity.LIFE || e.y < -30.0 || abs(e.x - px) > 96.0 || abs(e.z - pz) > 96.0) {
                it.remove()
                continue
            }
            if (e.delay > 0f) e.delay -= dt
            Drops.step(e, world, dt)
            if (!dead && e.delay <= 0f && e.count > 0 &&
                e.x + ItemEntity.HALF > x0 && e.x - ItemEntity.HALF < x1 &&
                e.y + ItemEntity.SIZE > y0 && e.y < y1 &&
                e.z + ItemEntity.HALF > z0 && e.z - ItemEntity.HALF < z1
            ) {
                val left = input.inv.add(e.id, e.count)
                if (left <= 0) {
                    e.collectT = 0f
                    e.sx = e.x; e.sy = e.y; e.sz = e.z
                } else e.count = left
            }
        }
    }

    private fun use() {
        val sel = input.selected.coerceIn(0, 8)
        val item = input.inv.id[sel]
        val cnt = input.inv.count[sel]
        // clique direito num bloco de mod com "on_use" (nao agachado)
        if (hitValid && !sneaking) {
            val hb = world.get(hx, hy, hz)
            if (ScriptHost.blockUse(hb, hx, hy, hz)) { swing = 1f; return }
            val u = Blocks.defs[hb]?.use
            if (u != null) { swing = 1f; runUse(u, hx, hy, hz); return }
        }
        if (cnt <= 0) return
        swing = 1f
        if (Blocks.isItem(item)) {
            if (!ScriptHost.itemUse(item, sel, cnt)) return
            val hunger = Blocks.foodValue(item)
            val heal = Blocks.healValue(item)
            val canEat = hunger > 0 && (food < 20 || creative)
            val canHeal = heal > 0f && (health < 20f || creative)
            if (canEat || canHeal) {
                if (hunger > 0) {
                    food = min(20, food + hunger)
                    sat = min(food.toFloat(), sat + Blocks.foodSat(item))
                }
                if (heal > 0f) health = min(20f, health + heal)
                if (!creative) input.inv.take(sel, 1)
            }
            return
        }
        if (!hitValid) return
        var bx = hx + hnx
        var by = hy + hny
        var bz = hz + hnz
        if (Blocks.isPlant(world.get(hx, hy, hz))) { bx = hx; by = hy; bz = hz }
        if (by < 0 || by >= H) return
        val cur = world.get(bx, by, bz)
        if (cur != 0 && cur != Blocks.WATER && !Blocks.isPlant(cur)) return
        if (Blocks.isPlant(item)) {
            val below = world.get(bx, by - 1, bz)
            if (below != 1 && below != 2) return
        } else {
            if (px + HW > bx && px - HW < bx + 1 && py + bodyH > by && py < by + 1 && pz + HW > bz && pz - HW < bz + 1) return
        }
        if (!ScriptHost.blockPlace(item, bx, by, bz)) return
        world.set(bx, by, bz, item)
        if (!creative) input.inv.take(sel, 1)
    }

    private var actCool = 0f
    private var dmgT = 0f

    /** Multiplicador de velocidade de quebra da ferramenta na mao. */
    private fun toolMul(b: Int): Float {
        val sel = input.selected.coerceIn(0, 8)
        if (input.inv.count[sel] <= 0) return 1f
        val d = Blocks.items[input.inv.id[sel]] ?: return 1f
        val want = Blocks.tool(b)
        return if (d.tool != null && d.tool == want) max(1f, d.toolSpeed) else 1f
    }

    private fun runUse(a: UseAction, x: Int, y: Int, z: Int) {
        if (actCool > 0f) return
        actCool = a.cooldown
        val g = a.give
        if (g != null) {
            val id = Blocks.idOf(g)
            if (id > 0) input.inv.add(id, a.giveCount)
        }
        if (a.heal > 0f) health = min(20f, health + a.heal)
        if (a.feed > 0) {
            food = min(20, food + a.feed)
            sat = min(food.toFloat(), sat + a.feed * 0.6f)
        }
        val m = a.message
        if (m != null) { Stats.message = m; Stats.messageUntil = System.currentTimeMillis() + 2500L }
        val rp = a.replace
        if (rp != null) {
            val id = if (rp == "minecraft:air" || rp == "air") 0 else Blocks.idOf(rp)
            if (id >= 0 && (id > 0 || rp.endsWith("air"))) world.set(x, y, z, id)
        } else if (a.consume) {
            world.set(x, y, z, 0)
        }
    }

    /** Blocos de mod com "damage" machucam ao encosta-los (HP por segundo). */
    private fun touchDamage(dt: Float) {
        if (creative || Blocks.defs.isEmpty()) return
        var dmg = 0f
        fun chk(x: Double, y: Double, z: Double) {
            val d = Blocks.defs[world.get(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())]
            if (d != null && d.damage > dmg) dmg = d.damage
        }
        val m = HW + 0.06
        for (yy in doubleArrayOf(0.2, 1.0)) {
            chk(px + m, py + yy, pz); chk(px - m, py + yy, pz); chk(px, py + yy, pz + m); chk(px, py + yy, pz - m)
        }
        chk(px, py - 0.06, pz)
        if (dmg > 0f) {
            dmgT += dt
            if (dmgT >= 0.5f) { dmgT = 0f; hurt(dmg * 0.5f) }
        } else dmgT = 0f
    }

    private fun pick() {
        if (!hitValid) return
        val b = world.get(hx, hy, hz)
        if (b <= 0 || b == Blocks.WATER) return
        for (i in 0 until 9) {
            if (input.inv.count[i] > 0 && input.inv.id[i] == b) { input.selected = i; return }
        }
        if (creative) input.inv.set(input.selected.coerceIn(0, 8), b, 64)
    }

    private fun raycast(maxDist: Double) {
        hitValid = false
        val ox = px
        val oy = py + eye
        val oz = pz
        val dx = -sin(yaw) * cos(pitch)
        val dy = sin(pitch)
        val dz = -cos(yaw) * cos(pitch)
        var x = floor(ox).toInt()
        var y = floor(oy).toInt()
        var z = floor(oz).toInt()
        val sx = if (dx > 0) 1 else -1
        val sy = if (dy > 0) 1 else -1
        val sz = if (dz > 0) 1 else -1
        val big = 1e30
        val tdx = if (dx != 0.0) abs(1.0 / dx) else big
        val tdy = if (dy != 0.0) abs(1.0 / dy) else big
        val tdz = if (dz != 0.0) abs(1.0 / dz) else big
        var tmx = if (dx == 0.0) big else (if (dx > 0) (x + 1 - ox) else (ox - x)) * tdx
        var tmy = if (dy == 0.0) big else (if (dy > 0) (y + 1 - oy) else (oy - y)) * tdy
        var tmz = if (dz == 0.0) big else (if (dz > 0) (z + 1 - oz) else (oz - z)) * tdz
        var nx = 0
        var ny = 0
        var nz = 0
        var t = 0.0
        while (t <= maxDist) {
            val b = world.get(x, y, z)
            if (b < 0) return
            if (b > 0 && b != Blocks.WATER && (nx != 0 || ny != 0 || nz != 0)) {
                hitValid = true
                hx = x; hy = y; hz = z
                hnx = nx; hny = ny; hnz = nz
                return
            }
            if (tmx < tmy && tmx < tmz) {
                x += sx; t = tmx; tmx += tdx; nx = -sx; ny = 0; nz = 0
            } else if (tmy < tmz) {
                y += sy; t = tmy; tmy += tdy; nx = 0; ny = -sy; nz = 0
            } else {
                z += sz; t = tmz; tmz += tdz; nx = 0; ny = 0; nz = -sz
            }
        }
    }

    // ------------------------------------------------------------ API para scripts (ver ScriptApi)

    fun scriptTeleport(x: Double, y: Double, z: Double) {
        world.chunk(floor(x / 16.0).toInt(), floor(z / 16.0).toInt())
        px = x; py = y; pz = z
        vx = 0.0; vy = 0.0; vz = 0.0
        fallDist = 0.0
    }

    fun scriptVelocity(): DoubleArray = doubleArrayOf(vx, vy, vz)

    fun scriptSetVelocity(x: Double, y: Double, z: Double) {
        vx = x; vy = y; vz = z
        onGround = false
        fallDist = 0.0
    }

    fun scriptHurt(a: Float) = hurt(a)

    fun scriptHeal(a: Float) {
        if (!dead && a > 0f) health = min(20f, health + a)
    }

    fun scriptSetHealth(v: Float) {
        if (dead) return
        health = v.coerceIn(0f, 20f)
        if (health <= 0f) {
            dead = true
            input.attack = false
            ScriptHost.simple("death")
        }
    }

    fun scriptSetFood(n: Int) { food = n.coerceIn(0, 20) }

    fun scriptSetCreative(on: Boolean) {
        if (creative == on) return
        creative = on
        flying = false
        if (on) { health = 20f; food = 20; sat = 5f }
    }

    fun scriptSetFlying(on: Boolean) {
        if (!creative) return
        flying = on
        vy = 0.0
        fallDist = 0.0
    }

    fun scriptDrop(id: Int, n: Int, x: Int, y: Int, z: Int) = popDrop(id, n, x, y, z)

    /** [x, y, z, nx, ny, nz] do bloco mirado, ou null. */
    fun scriptTarget(): IntArray? = if (hitValid) intArrayOf(hx, hy, hz, hnx, hny, hnz) else null

    fun scriptMessage(text: String, seconds: Double) {
        Stats.message = text
        Stats.messageUntil = System.currentTimeMillis() + (seconds.coerceIn(0.5, 30.0) * 1000.0).toLong()
    }

    /** Hora do dia (0..24), como no relogio do jogo. */
    fun scriptHours(): Double = (time * 24.0 + 6.0) % 24.0

    fun scriptSetTime(hours: Double) {
        time = ((((hours - 6.0) % 24.0) + 24.0) % 24.0 / 24.0).toFloat()
    }

    private fun publish() {
        Stats.x = floor(px).toInt()
        Stats.y = floor(py).toInt()
        Stats.z = floor(pz).toInt()
        Stats.fx = px; Stats.fy = py; Stats.fz = pz
        Stats.yaw = yaw; Stats.pitch = pitch
        Stats.underwater = underwater
        Stats.flying = flying
        Stats.sprinting = sprinting
        Stats.sneaking = sneaking
        Stats.creative = creative
        Stats.dead = dead
        Stats.health = health
        Stats.food = food
        Stats.air = if (air >= 15f) 10 else ceil(air / 1.5f).toInt().coerceIn(0, 10)
        Stats.flash = hurtFlash
        val dxf = -sin(yaw)
        val dzf = -cos(yaw)
        Stats.facing = if (abs(dzf) > abs(dxf)) (if (dzf < 0) "norte (-Z)" else "sul (+Z)")
        else (if (dxf > 0) "leste (+X)" else "oeste (-X)")
        val mins = (((time * 24f + 6f) % 24f) * 60f).toInt()
        if (mins != lastMin) {
            lastMin = mins
            Stats.clock = String.format("%02d:%02d", mins / 60, mins % 60)
        }
    }
}
