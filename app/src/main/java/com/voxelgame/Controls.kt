package com.voxelgame

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tudo que um botao de tela pode acionar. */
enum class Act(val label: String, val short: String, val hold: Boolean = false, val composite: Boolean = false) {
    DPAD("Direcional (setas)", "", false, true),
    STICK("Analógico", "", false, true),
    JUMP("Pular (Espaço)", "PULAR", true),
    SNEAK("Agachar (Shift)", "AGACH", true),
    SPRINT("Correr (Ctrl)", "CORRER", true),
    FLY_DOWN("Descer voando", "DESCER", true),
    ATTACK("Quebrar (clique esq.)", "QUEBRAR", true),
    USE("Colocar/Usar (clique dir.)", "USAR", true),
    PICK("Pegar bloco (clique meio)", "PEGAR"),
    FLY_TOGGLE("Alternar voo", "VOAR"),
    INVENTORY("Inventário (E)", "E"),
    DROP("Soltar item (Q)", "Q"),
    HOTBAR_PREV("Slot anterior", "<"),
    HOTBAR_NEXT("Próximo slot", ">"),
    PAUSE("Menu (ESC)", "ESC"),
    BACK("Voltar (BACK)", "BACK"),
    F1("Ocultar HUD (F1)", "F1"),
    F2("Captura de tela (F2)", "F2"),
    F3("Tela de debug (F3)", "F3"),
    F4("Modo de jogo (F3+F4)", "F4"),
    F5("Câmera (F5)", "F5")
}

class ControlButton(
    var act: Act,
    var fx: Float,
    var fy: Float,
    var size: Float,
    var toggle: Boolean = false,
    var alpha: Int = 55
) {
    @Transient var on = false
    @Transient var pressed = false
}

object Controls {
    val buttons = ArrayList<ControlButton>()
    var sens = 1.0f
    var guiScale = 0 // 0 = automatico
    var screenTap = true

    fun defaults() {
        buttons.clear()
        buttons.add(ControlButton(Act.DPAD, 0.14f, 0.72f, 150f))
        buttons.add(ControlButton(Act.JUMP, 0.90f, 0.74f, 64f))
        buttons.add(ControlButton(Act.SNEAK, 0.78f, 0.86f, 46f))
        buttons.add(ControlButton(Act.SPRINT, 0.80f, 0.64f, 46f, true))
        buttons.add(ControlButton(Act.PAUSE, 0.41f, 0.08f, 36f))
        buttons.add(ControlButton(Act.INVENTORY, 0.59f, 0.08f, 36f))
        buttons.add(ControlButton(Act.F5, 0.50f, 0.08f, 36f))
    }

    fun load(ctx: Context) {
        defaults()
        try {
            val sp = ctx.getSharedPreferences("controls", Context.MODE_PRIVATE)
            sens = sp.getFloat("sens", 1.0f)
            guiScale = sp.getInt("gui", 0)
            screenTap = sp.getBoolean("tap", true)
            val js = sp.getString("layout", null) ?: return
            val arr = JSONObject(js).getJSONArray("buttons")
            val list = ArrayList<ControlButton>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val a = try { Act.valueOf(o.getString("a")) } catch (t: Throwable) { continue }
                list.add(
                    ControlButton(
                        a, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                        o.getDouble("s").toFloat(), o.optBoolean("t", false), o.optInt("o", 55)
                    )
                )
            }
            // layouts salvos antes do F5 existir ganham o botao de camera uma unica vez
            if (list.none { it.act == Act.F5 } && !sp.getBoolean("f5added", false)) {
                list.add(ControlButton(Act.F5, 0.50f, 0.08f, 36f))
                sp.edit().putBoolean("f5added", true).apply()
            }
            buttons.clear()
            buttons.addAll(list)
        } catch (t: Throwable) {
            defaults()
        }
    }

    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (b in buttons) {
                val o = JSONObject()
                o.put("a", b.act.name)
                o.put("x", b.fx.toDouble())
                o.put("y", b.fy.toDouble())
                o.put("s", b.size.toDouble())
                o.put("t", b.toggle)
                o.put("o", b.alpha)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("buttons", arr)
            ctx.getSharedPreferences("controls", Context.MODE_PRIVATE).edit()
                .putString("layout", root.toString())
                .putFloat("sens", sens)
                .putInt("gui", guiScale)
                .putBoolean("tap", screenTap)
                .apply()
        } catch (t: Throwable) {
            // sem espaco / sem permissao: ignora
        }
    }
}
