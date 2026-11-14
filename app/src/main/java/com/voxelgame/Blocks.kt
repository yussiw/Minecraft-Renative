package com.voxelgame

import kotlin.math.min

object Stats {
    @Volatile var fps = 0f
    @Volatile var radius = 0
    @Volatile var chunks = 0
    @Volatile var quads = 0
    @Volatile var x = 0
    @Volatile var y = 0
    @Volatile var z = 0
    @Volatile var fx = 0.0
    @Volatile var fy = 0.0
    @Volatile var fz = 0.0
    @Volatile var yaw = 0.0
    @Volatile var pitch = 0.0
    @Volatile var clock = "06:00"
    @Volatile var day = 0
    @Volatile var facing = ""
    @Volatile var underwater = false
    @Volatile var flying = false
    @Volatile var sprinting = false
    @Volatile var sneaking = false
    @Volatile var creative = false
    @Volatile var dead = false
    @Volatile var health = 20f
    @Volatile var food = 20
    @Volatile var air = 10
    @Volatile var flash = 0f
    @Volatile var camMode = 0 // 0 = primeira pessoa, 1 = terceira (costas), 2 = terceira (frente)
    @Volatile var debug = false
    @Volatile var message = ""
    @Volatile var messageUntil = 0L
}

/** Acao de clicar com o botao direito num bloco de mod (data/<ns>/blocks, arquivos .json -> "on_use"). */
class UseAction(
    val give: String?, val giveCount: Int, val heal: Float, val feed: Int,
    val message: String?, val consume: Boolean, val replace: String?, val cooldown: Float
)

/** Comportamento de um bloco de mod. */
class BlockDef(
    val hardness: Float, val light: Int, val damage: Float, val tool: String?,
    val dropKey: String?, val dropCount: Int, val use: UseAction?
) {
    /** Callbacks de script (Lua/JS): clique direito, quebrar e colocar este bloco. */
    @Volatile var onUse: ScriptCallback? = null
    @Volatile var onBreak: ScriptCallback? = null
    @Volatile var onPlace: ScriptCallback? = null
}

/** Item de mod (id >= 101). */
class ItemDef(
    val id: Int, val key: String, val name: String, val tile: Int,
    val hunger: Int, val sat: Float, val heal: Float,
    val tool: String?, val toolSpeed: Float, val attack: Float
) {
    /** Callback de script: clique direito com o item na mao. */
    @Volatile var onUse: ScriptCallback? = null
}

/** Minerio / geracao de mundo: "worldgen" no JSON do bloco. */
class Ore(val id: Int, val minY: Int, val maxY: Int, val tries: Int, val vein: Int)

/** Pilha de itens: 36 slots (0..8 = hotbar), como no Minecraft Java. */
class Inventory {
    val id = IntArray(36)
    val count = IntArray(36)

    /** Devolve quantos itens nao couberam. */
    fun add(item: Int, n: Int): Int {
        var left = n
        for (i in 0 until 36) {
            if (left > 0 && id[i] == item && count[i] in 1..63) {
                val m = min(64 - count[i], left)
                count[i] += m
                left -= m
            }
        }
        for (i in 0 until 36) {
            if (left > 0 && count[i] == 0) {
                id[i] = item
                val m = min(64, left)
                count[i] = m
                left -= m
            }
        }
        return left
    }

    fun take(slot: Int, n: Int) {
        count[slot] -= n
        if (count[slot] <= 0) { count[slot] = 0; id[slot] = 0 }
    }

    fun set(slot: Int, item: Int, n: Int) {
        id[slot] = if (n > 0) item else 0
        count[slot] = n
    }
}

class Input {
    @Volatile var moveX = 0f
    @Volatile var moveY = 0f
    @Volatile var stickFlick = false
    @Volatile var jump = false
    @Volatile var sneak = false
    @Volatile var sprint = false
    @Volatile var down = false
    @Volatile var attack = false
    @Volatile var useHeld = false
    @Volatile var useReq = false
    @Volatile var dropReq = false
    @Volatile var pickReq = false
    @Volatile var flyToggle = false
    @Volatile var camToggle = false
    @Volatile var modeToggle = false
    @Volatile var respawnReq = false
    @Volatile var shotReq = false
    @Volatile var paused = false
    @Volatile var hideHud = false
    @Volatile var hotbarDelta = 0
    @Volatile var selected = 0

    val inv = Inventory()

    private val lock = Any()
    private var lx = 0f
    private var ly = 0f

    fun addLook(dx: Float, dy: Float) {
        synchronized(lock) { lx += dx; ly += dy }
    }

    fun takeLook(out: FloatArray) {
        synchronized(lock) { out[0] = lx; out[1] = ly; lx = 0f; ly = 0f }
    }
}

object Blocks {
    // blocos: 1 grama, 2 terra, 3 pedra, 4 areia, 5 tronco, 6 folhas, 7 tabuas, 8 pedregulho,
    //         9 neve, 10 cascalho, 11 vidro, 12 agua, 13 tijolos, 14 grama alta, 15 dente-de-leao, 16 margarida
    // itens (>= 100): 100 maca
    const val GLASS = 11
    const val WATER = 12
    const val APPLE = 100
    const val MAX_BLOCK = 16

    // blocos de mods ocupam os ids 17..99 (itens comecam em 100)
    const val MOD_FIRST = 17
    const val MOD_LAST = 99
    const val ITEM_FIRST = 101
    const val ITEM_LAST = 1999

    /** "namespace:nome" -> id (blocos e itens, base e mods). */
    val keyToId = HashMap<String, Int>()
    /** id -> "namespace:nome" (so mods; os blocos base usam BASE_KEYS). */
    private val idToKey = HashMap<Int, String>()
    val defs = HashMap<Int, BlockDef>()
    val items = HashMap<Int, ItemDef>()
    @Volatile var ores: List<Ore> = emptyList()
    val glow = BooleanArray(MOD_LAST + 1)

    private val VANILLA = mapOf(
        "grass_block" to 1, "dirt" to 2, "stone" to 3, "sand" to 4, "oak_log" to 5, "oak_leaves" to 6,
        "oak_planks" to 7, "cobblestone" to 8, "snow_block" to 9, "snow" to 9, "gravel" to 10, "glass" to 11,
        "bricks" to 13, "short_grass" to 14, "grass" to 14, "dandelion" to 15, "oxeye_daisy" to 16, "apple" to 100
    )

    fun idOf(key: String): Int {
        val k = key.lowercase()
        if (k.startsWith("minecraft:")) return VANILLA[k.removePrefix("minecraft:")] ?: 0
        if (!k.contains(':')) return VANILLA[k] ?: keyToId[k] ?: 0
        return keyToId[k] ?: 0
    }

    val top = IntArray(MOD_LAST + 1)
    val side = IntArray(MOD_LAST + 1)
    val bottom = IntArray(MOD_LAST + 1)
    val names = Array(MOD_LAST + 1) { "" }

    private val BASE_ITEMS = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16, 100)

    /** Blocos que aparecem no inventario criativo (base + mods carregados). */
    @Volatile var creativeItems: IntArray = BASE_ITEMS
        private set

    init {
        val t = intArrayOf(0, 0, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18)
        val s = intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18)
        val b = intArrayOf(0, 2, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18)
        val n = arrayOf(
            "Ar", "Bloco de grama", "Terra", "Pedra", "Areia", "Tronco de carvalho", "Folhas de carvalho",
            "Tábuas de carvalho", "Pedregulho", "Neve", "Cascalho", "Vidro", "Água", "Tijolos",
            "Grama alta", "Dente-de-leão", "Margarida"
        )
        for (i in t.indices) { top[i] = t[i]; side[i] = s[i]; bottom[i] = b[i]; names[i] = n[i] }
        resetMods()
    }

    /** Ids de mod sem mod instalado viram cubo "textura faltando" (o mundo salvo nao quebra). */
    fun resetMods() {
        for (i in MOD_FIRST..MOD_LAST) {
            top[i] = Atlas.MISSING; side[i] = Atlas.MISSING; bottom[i] = Atlas.MISSING
            names[i] = "Bloco desconhecido"
            glow[i] = false
        }
        keyToId.clear(); idToKey.clear(); defs.clear(); items.clear()
        ores = emptyList()
        creativeItems = BASE_ITEMS
    }

    /** Liga "namespace:nome" a um id (bloco ou item de mod). */
    fun bind(key: String, id: Int) {
        val k = key.lowercase()
        keyToId[k] = id
        idToKey[id] = k
    }

    private val BASE_KEYS = arrayOf(
        "minecraft:air", "minecraft:grass_block", "minecraft:dirt", "minecraft:stone", "minecraft:sand",
        "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:oak_planks", "minecraft:cobblestone",
        "minecraft:snow_block", "minecraft:gravel", "minecraft:glass", "minecraft:water", "minecraft:bricks",
        "minecraft:short_grass", "minecraft:dandelion", "minecraft:oxeye_daisy"
    )

    /** id -> chave textual ("minecraft:dirt", "meumod:rubi"); o inverso de idOf. */
    fun keyOf(id: Int): String = when {
        id == 0 -> "minecraft:air"
        id in 1..MAX_BLOCK -> BASE_KEYS[id]
        id == APPLE -> "minecraft:apple"
        else -> idToKey[id] ?: "desconhecido:$id"
    }

    fun registerDef(id: Int, key: String, d: BlockDef) {
        bind(key, id)
        defs[id] = d
        glow[id] = d.light > 0
    }

    fun registerItem(d: ItemDef) {
        items[d.id] = d
        bind(d.key, d.id)
    }

    fun registerMod(id: Int, name: String, tTop: Int, tSide: Int, tBottom: Int) {
        top[id] = tTop; side[id] = tSide; bottom[id] = tBottom
        names[id] = name
    }

    fun finishMods(ids: List<Int>, itemIds: List<Int>) {
        creativeItems = BASE_ITEMS + ids.sorted() + itemIds.sorted()
    }

    fun isPlant(id: Int) = id in 14..16
    fun isItem(id: Int) = id >= 100
    fun isCube(id: Int) = id in 1..13 || id in MOD_FIRST..MOD_LAST

    fun name(id: Int): String = when {
        id == APPLE -> "Maçã"
        id in 1..MOD_LAST -> names[id]
        id >= ITEM_FIRST -> items[id]?.name ?: "Item desconhecido"
        else -> ""
    }

    fun iconTile(id: Int): Int = when {
        id == APPLE -> 15
        id == 1 -> 1
        id == 5 -> 5
        id in 1..MOD_LAST -> top[id]
        id >= ITEM_FIRST -> items[id]?.tile ?: Atlas.MISSING
        else -> 0
    }

    /** Ferramenta ideal para quebrar o bloco (acelera a quebra). */
    fun tool(id: Int): String? = defs[id]?.tool ?: when (id) {
        2, 4, 1, 9, 10 -> "shovel"
        3, 8, 13 -> "pickaxe"
        5, 7 -> "axe"
        else -> null
    }

    /** Segundos para quebrar com a mao (dureza do wiki x 1,5). */
    fun hardness(id: Int): Float = defs[id]?.hardness ?: when (id) {
        1 -> 0.6f
        2 -> 0.5f
        3 -> 1.5f
        4 -> 0.5f
        5 -> 2.0f
        6 -> 0.2f
        7 -> 2.0f
        8 -> 2.0f
        9 -> 0.2f
        10 -> 0.6f
        11 -> 0.3f
        13 -> 2.0f
        14, 15, 16 -> 0f
        else -> 1f
    }

    fun dropCount(id: Int): Int = defs[id]?.dropCount ?: 1

    fun drop(id: Int, r: Float): Int = if (defs.containsKey(id)) {
        val k = defs[id]!!.dropKey
        if (k == null) id else if (k.isEmpty() || k == "none") 0 else idOf(k)
    } else when (id) {
        1 -> 2
        3 -> 8
        6 -> if (r < 0.025f) APPLE else 0
        11, 14 -> 0
        else -> id
    }

    /** Tile usado nas particulas de quebra (como no Java: bloco de grama solta particulas de terra). */
    fun particleTile(id: Int): Int = when {
        id == 1 -> top[2]
        id in 1..MOD_LAST -> if (isCube(id)) side[id] else iconTile(id)
        else -> iconTile(id)
    }

    fun foodValue(id: Int): Int = if (id == APPLE) 4 else items[id]?.hunger ?: 0
    fun foodSat(id: Int): Float = if (id == APPLE) 2.4f else items[id]?.sat ?: 0f
    fun healValue(id: Int): Float = items[id]?.heal ?: 0f
}
