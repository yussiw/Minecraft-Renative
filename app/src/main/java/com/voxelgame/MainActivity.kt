package com.voxelgame

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), HudHost {
    private lateinit var gl: GLSurfaceView
    private lateinit var hud: HudView
    private lateinit var input: Input
    private lateinit var world: World
    private lateinit var game: Game
    private lateinit var saveFile: File

    /** true = entrei na partida de outra pessoa: o mundo e dela, so o meu jogador e salvo (mp_player.dat). */
    private var joinMode = false
    private var mcLock: android.net.wifi.WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = object : Runnable {
        override fun run() {
            Thread { Save.save(saveFile, world, game, input, !joinMode) }.start()
            handler.postDelayed(this, 45_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // captura qualquer crash e salva num .txt legivel por qualquer gerenciador de arquivos,
        // sem precisar de PC/adb. Fica em Android/data/<pacote>/files/crash_log.txt
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val f = File(getExternalFilesDir(null), "crash_log.txt")
                f.writeText(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) +
                        "\n" + android.util.Log.getStackTraceString(e)
                )
            } catch (t: Throwable) { }
            Process.killProcess(Process.myPid())
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        val refresh = pickHighRefresh()

        // mods primeiro (registram blocos e tiles), depois o resource pack (que pode retexturizar tudo)
        try { ModManager.loadAll(filesDir) } catch (t: Throwable) { }
        try {
            val saved = File(filesDir, "pack.zip")
            if (saved.exists()) Atlas.loadPack(saved.absolutePath) else Atlas.rebuild()
        } catch (t: Throwable) {
            // pack corrompido: segue com as texturas geradas
            try { Atlas.rebuild() } catch (t2: Throwable) { }
        }

        try { CustomModel.loadSaved(filesDir) } catch (t: Throwable) { }
        Controls.load(this)
        input = Input()
        world = World()
        val join = Net.pendingJoin
        Net.pendingJoin = null
        joinMode = join != null
        saveFile = File(filesDir, if (joinMode) "mp_player.dat" else "world.dat")
        val saved = if (joinMode) {
            // traz inventario/vida do ultimo jogo; os blocos vem do anfitriao
            Save.load(saveFile, World()) ?: Save.load(File(filesDir, "world.dat"), World())
        } else Save.load(saveFile, world)
        game = Game(world, input)
        if (saved != null) game.restore(saved)
        if (joinMode) game.respawn()

        // multijogador
        Net.stop()
        Net.myName = getSharedPreferences("mp", MODE_PRIVATE).getString("name", null)
            ?: (Build.MODEL ?: "Jogador").take(16)
        Net.onClosed = { reason ->
            runOnUiThread {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                recreate() // volta ao mundo local
            }
        }
        if (join != null) {
            Stats.message = "Conectando a " + join.first + "..."
            Stats.messageUntil = System.currentTimeMillis() + 4000L
            Net.connect(world, join.first, join.second)
        }
        val renderer = GameRenderer(game, refresh) { bmp -> saveShot(bmp) }

        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 0, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(gl)
        hud = HudView(this, input, this)
        root.addView(hud)
        setContentView(root)
        hideSystemUi()
    }

    override fun saveExit() {
        Save.save(saveFile, world, game, input, !joinMode)
        finish()
    }

    // ---------------------------------------------------------------- multijogador (Wi-Fi)

    override fun mpHost() {
        // abre a porta fora da thread da interface (rede na thread principal e proibido no Android)
        Thread {
            val err = Net.startHost(world)
            runOnUiThread {
                val msg = err ?: "Partida aberta! Peça para o outro aparelho procurar a partida na rede."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun mpStop() {
        val client = Net.mode == Net.Mode.CLIENT
        Net.stop()
        if (client) {
            recreate() // volta ao seu mundo
        } else {
            Toast.makeText(this, "Partida encerrada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun mpJoin(ip: String, port: Int) {
        Net.pendingJoin = Pair(ip, port)
        recreate() // troca para um mundo vazio que o anfitriao preenche
    }

    override fun mpDiscovery(on: Boolean) {
        if (on) {
            try {
                // alguns aparelhos so entregam broadcast UDP com o "multicast lock"
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                if (mcLock == null) {
                    mcLock = wm.createMulticastLock("blocos").also { it.setReferenceCounted(false) }
                }
                mcLock?.acquire()
            } catch (t: Throwable) { }
            Net.startDiscovery()
        } else {
            Net.stopDiscovery()
            try { mcLock?.release() } catch (t: Throwable) { }
        }
    }

    override fun askText(title: String, hint: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setSingleLine(true)
        et.hint = hint
        et.setText(initial)
        et.setSelection(et.text.length)
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    override fun getPref(key: String, def: String): String =
        getSharedPreferences("mp", MODE_PRIVATE).getString(key, def) ?: def

    override fun setPref(key: String, value: String) {
        getSharedPreferences("mp", MODE_PRIVATE).edit().putString(key, value).apply()
    }

    override fun onBackPressed() {
        hud.back()
    }

    private fun saveShot(bmp: Bitmap) {
        Thread {
            val ok = try {
                val name = "blocos_" + SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).format(Date()) + ".png"
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Blocos")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
                    File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                true
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Captura de tela salva" else "Falha ao salvar a captura", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun pickPack() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, REQ_PACK)
        } catch (t: Throwable) {
            Toast.makeText(this, "Nao consegui abrir o seletor de arquivos", Toast.LENGTH_LONG).show()
        }
    }

    override fun pickMod() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_MOD)
        } catch (t: Throwable) {
            Toast.makeText(this, "Nao consegui abrir o seletor de arquivos", Toast.LENGTH_LONG).show()
        }
    }

    override fun pickPlayerModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_MODEL)
        } catch (t: Throwable) {
            Toast.makeText(this, "Nao consegui abrir o seletor de arquivos", Toast.LENGTH_LONG).show()
        }
    }

    override fun clearPlayerModel() {
        CustomModel.clear(filesDir)
        Toast.makeText(this, "Skin padrao restaurada", Toast.LENGTH_SHORT).show()
    }

    private fun addPlayerModel(data: Intent) {
        val uris = ArrayList<android.net.Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        Thread {
            val msg = try {
                var glb: ByteArray? = null
                var json: String? = null
                var zipMsg: String? = null
                for (u in uris) {
                    val n = rawName(u).lowercase()
                    when {
                        n.endsWith(".glb") -> glb = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                        n.endsWith(".json") -> json = contentResolver.openInputStream(u)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                        n.endsWith(".zip") -> {
                            val tmp = File(cacheDir, "player_model_tmp.zip")
                            contentResolver.openInputStream(u)!!.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                            zipMsg = CustomModel.importZip(filesDir, tmp)
                            tmp.delete()
                        }
                    }
                }
                if (zipMsg != null) zipMsg
                else if (glb != null) CustomModel.importGlb(filesDir, glb, json)
                else "Escolha um arquivo .glb (ou um .zip com ele)"
            } catch (t: Throwable) {
                "Nao consegui ler o arquivo: " + (t.message ?: "")
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun rawName(uri: android.net.Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0) name = c.getString(ix)
                }
            }
        } catch (t: Throwable) { }
        return name ?: uri.lastPathSegment ?: ""
    }

    /** Aplica ativar/desativar/excluir: recria a activity (o onPause salva o mundo) e os mods sao recarregados. */
    override fun reloadMods() {
        Toast.makeText(this, "Aplicando mudanças nos mods...", Toast.LENGTH_SHORT).show()
        recreate()
    }

    private fun displayName(uri: android.net.Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0) name = c.getString(ix)
                }
            }
        } catch (t: Throwable) { }
        var n = (name ?: uri.lastPathSegment ?: "mod").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val low = n.lowercase()
        if (!low.endsWith(".jar") && !low.endsWith(".zip") && !low.endsWith(".lua") && !low.endsWith(".js")) n += ".jar"
        return n
    }

    private fun addMods(data: Intent) {
        val uris = ArrayList<android.net.Uri>()
        val clip = data.clipData
        if (clip != null) for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        Thread {
            var ok = 0
            var lastErr = "Arquivo inválido"
            val dir = ModManager.dir(filesDir)
            dir.mkdirs()
            for (u in uris) {
                try {
                    val f = File(dir, displayName(u))
                    contentResolver.openInputStream(u)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    val bad = ModManager.validate(f) // zip/jar valido, ou script .lua/.js nao vazio
                    if (bad == null) ok++ else { f.delete(); lastErr = bad }
                } catch (t: Throwable) {
                    try { File(dir, displayName(u)).delete() } catch (t2: Throwable) { }
                }
            }
            runOnUiThread {
                if (ok > 0) {
                    Toast.makeText(this, "$ok mod(s) adicionado(s). Reiniciando...", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, lastErr + " (use .zip/.jar, .lua ou .js)", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MOD && resultCode == RESULT_OK && data != null) { addMods(data); return }
        if (requestCode == REQ_MODEL && resultCode == RESULT_OK && data != null) { addPlayerModel(data); return }
        if (requestCode != REQ_PACK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val msg = try {
                val tmp = File(filesDir, "pack_new.zip")
                contentResolver.openInputStream(uri)!!.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                val info = Atlas.loadPack(tmp.absolutePath)
                if (info.total > 0) {
                    tmp.copyTo(File(filesDir, "pack.zip"), true)
                    tmp.delete()
                    "Pack carregado: ${info.blocks} texturas, ${info.hud} sprites do HUD" +
                        (if (info.mods > 0) ", ${info.mods} de mods" else "")
                } else {
                    tmp.delete()
                    "Esse arquivo nao tem texturas no formato do Minecraft"
                }
            } catch (t: Throwable) {
                "Nao consegui ler esse arquivo (precisa ser um .zip de resource pack)"
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    companion object {
        private const val REQ_PACK = 7
        private const val REQ_MOD = 8
        private const val REQ_MODEL = 9
    }

    /** Pede ao sistema o maior refresh rate disponivel (90/120 Hz), na mesma resolucao. */
    private fun pickHighRefresh(): Float {
        return try {
            @Suppress("DEPRECATION")
            val d = windowManager.defaultDisplay
            var best = d.mode
            for (m in d.supportedModes) {
                if (m.physicalWidth == best.physicalWidth && m.physicalHeight == best.physicalHeight &&
                    m.refreshRate > best.refreshRate
                ) best = m
            }
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
            window.attributes = lp
            best.refreshRate
        } catch (t: Throwable) {
            60f
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        hideSystemUi()
        handler.postDelayed(autosave, 45_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(autosave)
        hud.pauseIfPlaying()
        gl.onPause()
        Save.save(saveFile, world, game, input, !joinMode)
        try { ScriptHost.flush() } catch (t: Throwable) { }
        super.onPause()
    }

    override fun onDestroy() {
        // sem isto o callback tentaria recriar uma Activity que ja esta indo embora
        Net.onClosed = null
        Net.stop()
        Net.stopDiscovery()
        try { mcLock?.release() } catch (t: Throwable) { }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }
}
