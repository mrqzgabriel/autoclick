package com.gm.autoclick

import org.json.JSONArray
import org.json.JSONObject

/** Um ponto em coordenada de tela (pixels absolutos). */
data class Pt(val x: Float, val y: Float)

/**
 * Um passo gravado.
 * delayBeforeMs guarda a pausa real que voce fez antes desse toque.
 * app e o pacote que estava na frente quando o passo foi gravado: e por ele
 * que a reproducao sabe se ainda esta na rota certa (ver guarda no
 * ClickerService). Vazio = passo antigo, aprendido na primeira passada.
 */
data class Step(
    val type: String,
    val pts: List<Pt>,
    val durationMs: Long,
    var delayBeforeMs: Long,
    var app: String = "",
    /**
     * Id do elemento que fica embaixo do ponto do toque (ex: "send" do botao de
     * enviar do WhatsApp). A guarda exige que ele esteja na tela antes de tocar.
     * E o que pega o caso que o app sozinho nao pega: WhatsApp ABERTO mas numa
     * tela de erro/ban, sem o botao. Vazio = nao aprendido ou tela sem ids
     * (pagina web nao expoe id nenhum).
     */
    var anchor: String = ""
) {
    val label: String
        get() = when (type) {
            SWIPE -> "Arrasto (${durationMs}ms)"
            HOME -> "Tela inicial"
            REFRESH -> "Atualizar página (F5)"
            else -> if (durationMs >= 400) "Toque longo (${durationMs}ms)" else "Toque"
        }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in pts) arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
        return JSONObject()
            .put("type", type)
            .put("pts", arr)
            .put("dur", durationMs)
            .put("delay", delayBeforeMs)
            .put("app", app)
            .put("anchor", anchor)
    }

    companion object {
        const val TAP = "tap"
        const val SWIPE = "swipe"

        // Vai pra tela inicial via performGlobalAction, não por gesto. Arrasto
        // de baixo pra cima INJETADO não aciona a navegação do sistema: o toque
        // cai no app da frente (no WhatsApp puxava o teclado e digitava).
        const val HOME = "home"

        // Atualiza a página do Chrome (F5): abre o menu ⋮ e clica em "Atualizar"
        // pelos nós de acessibilidade, não por coordenada. Gesto de puxar pra
        // baixo não serve: só funciona com a página no topo e vira rolagem no
        // resto dos casos.
        const val REFRESH = "refresh"

        fun fromJson(o: JSONObject): Step {
            val arr = o.optJSONArray("pts") ?: JSONArray()
            val pts = ArrayList<Pt>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                pts.add(Pt(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
            }
            return Step(
                o.optString("type", TAP),
                pts,
                o.optLong("dur", 60L),
                o.optLong("delay", 300L),
                o.optString("app", ""),
                o.optString("anchor", "")
            )
        }
    }
}

class Macro(
    var name: String,
    val steps: MutableList<Step> = mutableListOf(),
    var loops: Int = 0,
    var loopDelayMs: Long = 800,
    var fixedDelayMs: Long = 0,
    var speed: Float = 1f,
    val id: String = System.currentTimeMillis().toString(),
    /**
     * Nome de um drawable com o print de como a tela tem que estar pro macro
     * funcionar. Vazio = macro sem guia (o botão de imagem nem aparece).
     */
    var guide: String = "",
    /**
     * Guarda de rota: antes de cada passo, confere se o app da frente e o mesmo
     * de quando o passo foi gravado. Se nao for, fecha as abas do navegador,
     * volta pra tela inicial e recomeca a passada do zero.
     */
    var autoRecover: Boolean = true
) {
    val loopsLabel: String get() = if (loops <= 0) "infinito" else "${loops}x"

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in steps) arr.put(s.toJson())
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("loops", loops)
            .put("loopDelay", loopDelayMs)
            .put("fixedDelay", fixedDelayMs)
            .put("speed", speed.toDouble())
            .put("guide", guide)
            .put("recover", autoRecover)
            .put("steps", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<Step>(arr.length())
            for (i in 0 until arr.length()) steps.add(Step.fromJson(arr.getJSONObject(i)))
            return Macro(
                name = o.optString("name", "Macro"),
                steps = steps,
                loops = o.optInt("loops", 0),
                loopDelayMs = o.optLong("loopDelay", 800L),
                fixedDelayMs = o.optLong("fixedDelay", 0L),
                speed = o.optDouble("speed", 1.0).toFloat(),
                id = o.optString("id", System.currentTimeMillis().toString()),
                guide = o.optString("guide", ""),
                autoRecover = o.optBoolean("recover", true)
            )
        }
    }
}
