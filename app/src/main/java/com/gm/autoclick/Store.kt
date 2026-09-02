package com.gm.autoclick

import android.content.Context
import org.json.JSONArray

/** Guarda os macros em SharedPreferences como JSON. Sem banco, sem dependencia. */
object Store {
    private const val PREF = "autoclick"
    private const val KEY_MACROS = "macros"
    private const val KEY_SELECTED = "selected"
    private const val KEY_DRAFT = "draft"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_SEED_VERSION = "seed_version"

    // Execucao em andamento, pra retomar sozinho depois que o sistema derrubar
    // o servico ou o celular reiniciar. So um PEDIDO EXPLICITO de parar apaga.
    private const val KEY_RUN_ID = "run_macro_id"
    private const val KEY_RUN_GAP = "run_gap"
    private const val KEY_RUN_QUIET = "run_quiet"

    // Horario de silencio do modo Especial. Padrao 21h-9h; o config.json do
    // servidor pode mudar (quietHours).
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    /** Macro que já vem no app (assets/default_macro.json). */
    private const val DEFAULT_ASSET = "default_macro.json"
    private const val DEFAULT_NAME = "Aquecimento"

    /**
     * Sobe de número toda vez que os passos do macro que vem no app mudarem.
     * Sem isso, quem já tem o app instalado nunca receberia a versão nova: a
     * flag "seeded" faz o seed rodar uma vez só.
     * 2 = passos regravados em 03/08/2026.
     */
    private const val SEED_VERSION = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Macro> {
        val raw = prefs(ctx).getString(KEY_MACROS, null) ?: return mutableListOf()
        val out = mutableListOf<Macro>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(Macro.fromJson(arr.getJSONObject(i)))
        } catch (_: Throwable) {
            // json corrompido: comeca de novo em vez de crashar
        }
        return out
    }

    fun save(ctx: Context, list: List<Macro>) {
        val arr = JSONArray()
        for (m in list) arr.put(m.toJson())
        prefs(ctx).edit().putString(KEY_MACROS, arr.toString()).apply()
    }

    fun add(ctx: Context, macro: Macro) {
        val list = load(ctx)
        list.add(macro)
        save(ctx, list)
    }

    fun update(ctx: Context, macro: Macro) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == macro.id }
        if (i >= 0) list[i] = macro else list.add(macro)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
        if (selectedId(ctx) == id) setSelected(ctx, list.firstOrNull()?.id)
    }

    fun byId(ctx: Context, id: String?): Macro? =
        if (id == null) null else load(ctx).firstOrNull { it.id == id }

    /**
     * Instala o macro "Aquecimento" que vem junto com o app, uma única vez, e
     * atualiza os passos dele quando o app traz uma gravação nova (SEED_VERSION).
     * Passa pelo Porter pra herdar o reescale: o macro foi gravado numa tela
     * 720x1600 e num celular de outra resolução os toques são ajustados.
     * Se o usuário excluir depois, a flag impede que ele volte.
     */
    fun seedDefaultIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        val seeded = p.getBoolean(KEY_SEEDED, false)
        // quem instalou antes da SEED_VERSION existir só tinha a flag: isso é a versão 1
        val version = p.getInt(KEY_SEED_VERSION, if (seeded) 1 else 0)
        if (seeded && version >= SEED_VERSION) return
        // marca antes: falha não repete a cada abertura
        p.edit().putBoolean(KEY_SEEDED, true).putInt(KEY_SEED_VERSION, SEED_VERSION).apply()
        try {
            val imported = Porter.parseImport(
                ctx.assets.open(DEFAULT_ASSET),
                Porter.screenSize(ctx)
            )
            val list = load(ctx)
            val existing = list.firstOrNull { it.name == DEFAULT_NAME }
            if (existing != null) {
                // Já estava instalado: troca SÓ os passos. Nome, id (a bolha
                // aponta pro id), guia por imagem e os ajustes que o usuário
                // fez (repetições, velocidade, intervalo fixo) ficam de pé.
                existing.steps.clear()
                existing.steps.addAll(imported.macro.steps)
                save(ctx, list)
                return
            }
            // Excluiu o padrão em algum momento: respeitar e não ressuscitar.
            if (seeded) return
            val macro = Macro(
                name = DEFAULT_NAME,
                steps = imported.macro.steps,
                loops = imported.macro.loops,
                loopDelayMs = imported.macro.loopDelayMs,
                fixedDelayMs = imported.macro.fixedDelayMs,
                speed = imported.macro.speed,
                id = System.currentTimeMillis().toString(),
                guide = "guia_home",
                autoRecover = imported.macro.autoRecover
            )
            list.add(macro)
            save(ctx, list)
            if (selectedId(ctx) == null) setSelected(ctx, macro.id)
        } catch (t: Throwable) {
            android.util.Log.e("AutoClick", "não deu pra instalar o macro padrão", t)
        }
    }

    /**
     * Gravacao em andamento, salva a cada passo. Se o sistema matar o servico
     * no meio (HyperOS faz isso), o trabalho nao vai embora.
     */
    fun saveDraft(ctx: Context, draft: Macro?) {
        val e = prefs(ctx).edit()
        if (draft == null) e.remove(KEY_DRAFT) else e.putString(KEY_DRAFT, draft.toJson().toString())
        e.apply()
    }

    fun loadDraft(ctx: Context): Macro? {
        val raw = prefs(ctx).getString(KEY_DRAFT, null) ?: return null
        return try {
            Macro.fromJson(org.json.JSONObject(raw))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * O que estava rodando, pra retomar depois que o sistema derrubar o servico
     * (o HyperOS faz isso) ou o celular reiniciar. Guardado no disco de
     * proposito: memoria nao sobrevive a nenhum dos dois.
     */
    class RunState(val macroId: String, val gapMs: Long, val quiet: Boolean)

    fun saveRunState(ctx: Context, macroId: String, gapMs: Long, quiet: Boolean) {
        prefs(ctx).edit()
            .putString(KEY_RUN_ID, macroId)
            .putLong(KEY_RUN_GAP, gapMs)
            .putBoolean(KEY_RUN_QUIET, quiet)
            .apply()
    }

    fun loadRunState(ctx: Context): RunState? {
        val p = prefs(ctx)
        val id = p.getString(KEY_RUN_ID, null) ?: return null
        return RunState(id, p.getLong(KEY_RUN_GAP, 0L), p.getBoolean(KEY_RUN_QUIET, false))
    }

    /** So chamar quando o PARAR foi pedido de verdade: pela bolha ou por comando. */
    fun clearRunState(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_RUN_ID)
            .remove(KEY_RUN_GAP)
            .remove(KEY_RUN_QUIET)
            .apply()
    }

    fun quietStart(ctx: Context): Int = prefs(ctx).getInt(KEY_QUIET_START, 21).coerceIn(0, 23)

    fun quietEnd(ctx: Context): Int = prefs(ctx).getInt(KEY_QUIET_END, 9).coerceIn(0, 23)

    fun setQuietHours(ctx: Context, start: Int, end: Int) {
        prefs(ctx).edit()
            .putInt(KEY_QUIET_START, start.coerceIn(0, 23))
            .putInt(KEY_QUIET_END, end.coerceIn(0, 23))
            .apply()
    }

    fun selectedId(ctx: Context): String? = prefs(ctx).getString(KEY_SELECTED, null)

    fun setSelected(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    fun selected(ctx: Context): Macro? {
        val byId = byId(ctx, selectedId(ctx))
        if (byId != null) return byId
        return load(ctx).firstOrNull()
    }
}
