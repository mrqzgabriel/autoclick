package com.gm.autoclick

import android.content.Context
import android.graphics.Point
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Sincroniza este celular com o servidor (VPS/EasyPanel).
 *
 * A cada [pollSeconds] o celular manda um relatório (versão do app, macro que
 * está rodando, bateria...) em POST /api/sync e recebe de volta:
 *  - os macros publicados no repositório (pasta macros/), já no formato do
 *    Porter, então a coordenada é reescalada se a tela for outra;
 *  - as configurações do config.json (intervalo, horário de silêncio, macro
 *    da bolha, autorun, comando);
 *  - a versão do app publicada: se for mais nova, o [Updater] baixa e instala.
 *
 * Regras que importam:
 *  - TODA a aplicação do resultado roda na main thread. O ClickerService e o
 *    Store são mexidos só por ela; a rede e a única coisa que vai pra thread.
 *  - Macro que veio do servidor tem uma chave (nome do arquivo). O mapa
 *    chave -> id local (remote_map) e o que permite ATUALIZAR o macro no lugar
 *    em vez de duplicar, e apagar do celular o que sumiu do repositório.
 *  - Macro gravado no celular pelo usuário nunca e tocado: só entram na
 *    dança os que tem chave no mapa.
 *  - Nada e aplicado no meio de uma gravação.
 */
object Sync {
    private const val TAG = "AutoClick"
    private const val PREF = "autoclick_sync"
    private const val KEY_SERVER = "server_url"
    private const val KEY_MAP = "remote_map"
    private const val KEY_POLL = "poll_seconds"
    private const val KEY_LAST_OK = "last_ok_at"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_MSG = "last_msg"
    private const val KEY_LAST_ERR = "last_err"
    private const val KEY_AUTORUN_FP = "autorun_fp"
    private const val KEY_COMMAND_ID = "command_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_CONFIG_REV = "config_rev"
    private const val KEY_SELECTED_KEY = "selected_key"

    const val DEFAULT_POLL_S = 300
    private const val MIN_POLL_S = 60
    private const val MAX_POLL_S = 3600

    // Primeira sincronização depois que o serviço liga: dá tempo do launcher
    // se montar e do resume (20s) decidir o que fazer antes.
    private const val FIRST_DELAY_MS = 25_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_RESPONSE_BYTES = 6_000_000

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var scheduled = false

    @Volatile
    private var inFlight = false

    /** Chamado na main thread quando uma sincronização termina (a tela redesenha). */
    @Volatile
    var listener: (() -> Unit)? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // =====================================================================
    // CONFIGURAÇÃO LOCAL
    // =====================================================================

    /** URL do servidor: a que o usuário/comando gravou, senão a que veio no APK. */
    fun serverUrl(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_SERVER, null)?.trim().orEmpty()
        val url = if (saved.isNotEmpty()) saved else BuildConfig.SERVER_URL.trim()
        return url.trimEnd('/')
    }

    fun setServerUrl(ctx: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        prefs(ctx).edit().putString(KEY_SERVER, clean).apply()
        Log.i(TAG, "servidor configurado: ${clean.ifEmpty { "(nenhum)" }}")
    }

    /** Identidade estável deste celular (sobrevive a atualização do app). */
    fun deviceId(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "sem-id"
    } catch (_: Throwable) {
        "sem-id"
    }

    fun deviceName(ctx: Context): String = prefs(ctx).getString(KEY_DEVICE_NAME, "") ?: ""

    fun pollSeconds(ctx: Context): Int =
        prefs(ctx).getInt(KEY_POLL, DEFAULT_POLL_S).coerceIn(MIN_POLL_S, MAX_POLL_S)

    /** Uma linha pra tela: "há 2 min · 3 macros" ou "erro: ...". */
    fun lastSummary(ctx: Context): String {
        val p = prefs(ctx)
        val at = p.getLong(KEY_LAST_AT, 0L)
        if (at == 0L) return "ainda não sincronizou"
        val ago = ago(System.currentTimeMillis() - at)
        val err = p.getString(KEY_LAST_ERR, "") ?: ""
        if (err.isNotEmpty()) {
            val okAt = p.getLong(KEY_LAST_OK, 0L)
            val okStr = if (okAt > 0) " · última boa ${ago(System.currentTimeMillis() - okAt)}" else ""
            return "$ago · erro: $err$okStr"
        }
        val msg = p.getString(KEY_LAST_MSG, "") ?: ""
        return "$ago · ok${if (msg.isNotEmpty()) " · $msg" else ""}"
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "há ${s}s"
            s < 3600 -> "há ${s / 60} min"
            s < 86_400 -> "há ${s / 3600} h"
            else -> "há ${s / 86_400} dias"
        }
    }

    // =====================================================================
    // AGENDAMENTO
    // =====================================================================

    private val periodic = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            try {
                runNow(ctx, "periódica")
            } catch (t: Throwable) {
                Log.e(TAG, "sync periódica tropeçou", t)
            }
            handler.postDelayed(this, pollSeconds(ctx) * 1000L)
        }
    }

    /**
     * Liga o relógio (uma vez por processo). Chamado pelo serviço ao conectar e
     * pela tela ao abrir. Idempotente: chamar de novo não duplica.
     */
    fun ensureScheduled(ctx: Context) {
        app = ctx.applicationContext
        if (scheduled) return
        scheduled = true
        handler.postDelayed(periodic, FIRST_DELAY_MS)
    }

    /** O serviço (re)conectou: relógio ligado e um relatório logo em seguida. */
    fun onServiceConnected(ctx: Context) {
        val was = scheduled
        ensureScheduled(ctx)
        // já estava agendado (o serviço só religou): manda um relatório novo
        // pra tela de status mostrar que voltou, sem esperar o próximo tick
        if (was) handler.postDelayed({ app?.let { syncSoon(it) } }, FIRST_DELAY_MS)
    }

    /** Sincroniza agora, a não ser que a última tentativa tenha sido há pouco. */
    fun syncSoon(ctx: Context, minGapMs: Long = 60_000L) {
        val at = prefs(ctx).getLong(KEY_LAST_AT, 0L)
        if (System.currentTimeMillis() - at < minGapMs) return
        runNow(ctx, "pedido")
    }

    // =====================================================================
    // UMA SINCRONIZAÇÃO
    // =====================================================================

    /**
     * Roda uma sincronização. Chamar da MAIN THREAD: o relatório lê o estado do
     * serviço aqui mesmo, antes de ir pra rede.
     */
    fun runNow(ctx: Context, reason: String, done: ((ok: Boolean, msg: String) -> Unit)? = null) {
        val appCtx = ctx.applicationContext
        app = appCtx
        val base = serverUrl(appCtx)
        if (base.isEmpty()) {
            record(appCtx, ok = false, msg = "servidor não configurado")
            done?.invoke(false, "Servidor não configurado")
            listener?.invoke()
            return
        }
        if (inFlight) {
            done?.invoke(false, "Já está sincronizando")
            return
        }
        inFlight = true
        val report = try {
            buildReport(appCtx).toString()
        } catch (t: Throwable) {
            inFlight = false
            Log.e(TAG, "não consegui montar o relatório", t)
            done?.invoke(false, "Falha ao montar o relatório")
            return
        }
        Log.i(TAG, "sync ($reason) -> $base")
        Thread {
            val result = runCatching { post("$base/api/sync", report) }
            handler.post {
                inFlight = false
                val outcome = result.fold(
                    { manifest ->
                        try {
                            val msg = apply(appCtx, manifest)
                            record(appCtx, ok = true, msg = msg)
                            true to msg
                        } catch (t: Throwable) {
                            Log.e(TAG, "falha ao aplicar a sincronização", t)
                            val m = "ao aplicar: ${t.message ?: t.javaClass.simpleName}"
                            record(appCtx, ok = false, msg = m)
                            false to m
                        }
                    },
                    { t ->
                        val m = t.message ?: t.javaClass.simpleName
                        Log.i(TAG, "sync falhou: $m")
                        record(appCtx, ok = false, msg = m)
                        false to m
                    }
                )
                listener?.invoke()
                done?.invoke(outcome.first, outcome.second)
            }
        }.start()
    }

    private fun record(ctx: Context, ok: Boolean, msg: String) {
        val e = prefs(ctx).edit().putLong(KEY_LAST_AT, System.currentTimeMillis())
        if (ok) e.putLong(KEY_LAST_OK, System.currentTimeMillis()).putString(KEY_LAST_MSG, msg).putString(KEY_LAST_ERR, "")
        else e.putString(KEY_LAST_ERR, msg)
        e.apply()
    }

    private fun post(url: String, body: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AutoClick/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (BuildConfig.SYNC_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Token", BuildConfig.SYNC_TOKEN)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { readAll(it) } ?: ""
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("error", "")
                } catch (_: Throwable) {
                    ""
                }
                throw IllegalStateException("HTTP $code${if (detail.isNotEmpty()) " ($detail)" else ""}")
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error", "resposta inválida"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw IllegalStateException("resposta grande demais")
        }
        return out.toString("UTF-8")
    }

    // =====================================================================
    // RELATÓRIO
    // =====================================================================

    private fun buildReport(ctx: Context): JSONObject {
        val s = ClickerService.instance
        val size = try {
            Porter.screenSize(s ?: ctx)
        } catch (_: Throwable) {
            Point(0, 0)
        }
        val map = loadMap(ctx)
        val synced = JSONArray()
        for (k in map.keys()) {
            synced.put(JSONObject().put("key", k).put("sha", map.optJSONObject(k)?.optString("sha") ?: ""))
        }
        val locals = JSONArray()
        for (m in Store.load(ctx)) {
            locals.put(JSONObject().put("id", m.id).put("name", m.name).put("steps", m.steps.size))
        }
        val p = prefs(ctx)
        return JSONObject()
            .put("id", deviceId(ctx))
            .put("name", deviceName(ctx))
            .put("model", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("screen", JSONObject().put("w", size.x).put("h", size.y))
            .put("service", s != null)
            .put("state", s?.stateJson() ?: JSONObject().put("playing", false))
            .put("battery", batteryPct(ctx))
            .put("canInstall", Updater.canInstall(ctx))
            .put("update", Updater.stateJson())
            .put("macros", synced)
            .put("localMacros", locals)
            .put("selected", Store.selectedId(ctx) ?: "")
            .put("configRev", p.getString(KEY_CONFIG_REV, "") ?: "")
            .put("autorunFp", p.getString(KEY_AUTORUN_FP, "") ?: "")
            .put("commandId", p.getString(KEY_COMMAND_ID, "") ?: "")
            .put("pollSeconds", pollSeconds(ctx))
            .put("uptimeS", SystemClock.uptimeMillis() / 1000)
            .put("sentAt", System.currentTimeMillis())
    }

    private fun batteryPct(ctx: Context): Int = try {
        ctx.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (_: Throwable) {
        -1
    }

    // =====================================================================
    // APLICAR O QUE O SERVIDOR MANDOU (main thread)
    // =====================================================================

    private fun apply(ctx: Context, m: JSONObject): String {
        val s = ClickerService.instance
        val p = prefs(ctx)

        m.optJSONObject("device")?.optString("name")?.let {
            if (it.isNotBlank()) p.edit().putString(KEY_DEVICE_NAME, it).apply()
        }

        val cfg = m.optJSONObject("config") ?: JSONObject()
        p.edit()
            .putInt(KEY_POLL, cfg.optInt("pollSeconds", DEFAULT_POLL_S).coerceIn(MIN_POLL_S, MAX_POLL_S))
            .putString(KEY_CONFIG_REV, m.optString("configRev", ""))
            .apply()
        cfg.optJSONObject("quietHours")?.let {
            Store.setQuietHours(ctx, it.optInt("start", 21), it.optInt("end", 9))
        }

        // No meio de uma gravação nada muda: trocar macro ou reiniciar agora
        // jogaria fora o que está sendo gravado.
        if (s?.recording == true) return "gravando, nada aplicado"

        val macrosMsg = applyMacros(ctx, m.optJSONArray("macros") ?: JSONArray(), s)
        applySelected(ctx, cfg, s)
        applyAutorun(ctx, cfg.optJSONObject("autorun"), s)
        applyCommand(ctx, cfg.optJSONObject("command"), s)
        Updater.check(ctx, m.optJSONObject("app"), serverUrl(ctx), cfg.optJSONObject("update"))
        return macrosMsg
    }

    // ---------- macros ----------

    private fun loadMap(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString(KEY_MAP, null) ?: "{}")
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun saveMap(ctx: Context, map: JSONObject) {
        prefs(ctx).edit().putString(KEY_MAP, map.toString()).apply()
    }

    /** Id local do macro que o servidor chama de [key], se já estiver neste celular. */
    fun localIdFor(ctx: Context, key: String): String? {
        if (key.isBlank()) return null
        val id = loadMap(ctx).optJSONObject(key)?.optString("id") ?: return null
        return if (Store.byId(ctx, id) != null) id else null
    }

    /** Chave no servidor de um macro local (pra tela mostrar "vem do servidor"). */
    fun keyFor(ctx: Context, localId: String): String? {
        val map = loadMap(ctx)
        for (k in map.keys()) if (map.optJSONObject(k)?.optString("id") == localId) return k
        return null
    }

    private fun applyMacros(ctx: Context, remote: JSONArray, s: ClickerService?): String {
        val map = loadMap(ctx)
        val list = Store.load(ctx)
        val screen = try {
            Porter.screenSize(s ?: ctx)
        } catch (_: Throwable) {
            Point(0, 0)
        }
        var changed = false
        var added = 0
        var updated = 0
        var removed = 0
        val replacedIds = ArrayList<String>()
        val removedIds = ArrayList<String>()
        val seen = HashSet<String>()

        for (i in 0 until remote.length()) {
            val r = remote.optJSONObject(i) ?: continue
            val key = r.optString("key", "")
            val sha = r.optString("sha", "")
            val envelope = r.optJSONObject("envelope") ?: continue
            if (key.isBlank()) continue
            seen.add(key)

            val entry = map.optJSONObject(key)
            val local = entry?.optString("id")?.let { id -> list.firstOrNull { it.id == id } }

            if (local != null) {
                if (entry.optString("sha") == sha) continue // já está igual
                val imp = parseEnvelope(envelope, screen) ?: continue
                replaceContent(local, imp.macro)
                map.put(key, JSONObject().put("id", local.id).put("sha", sha))
                replacedIds.add(local.id)
                updated++
                changed = true
                Log.i(TAG, "macro \"$key\" atualizado (${imp.macro.steps.size} passos)")
                continue
            }

            val imp = parseEnvelope(envelope, screen) ?: continue
            val mappedIds = HashSet<String>()
            for (k in map.keys()) map.optJSONObject(k)?.optString("id")?.let { mappedIds.add(it) }

            // Macro local com o mesmo nome que ainda não pertence a chave
            // nenhuma (ex: o "Aquecimento" que veio semeado no app): adota em
            // vez de duplicar. Se os passos são os mesmos, mantém os locais,
            // que já tem a rota aprendida.
            val adopt = list.firstOrNull { it.name == imp.macro.name && it.id !in mappedIds }
            if (adopt != null) {
                if (stepsEquivalent(adopt.steps, imp.macro.steps)) {
                    copySettings(adopt, imp.macro)
                } else {
                    replaceContent(adopt, imp.macro)
                    replacedIds.add(adopt.id)
                }
                map.put(key, JSONObject().put("id", adopt.id).put("sha", sha))
                updated++
                changed = true
                Log.i(TAG, "macro \"$key\" adotou o local \"${adopt.name}\"")
                continue
            }

            val novo = Macro(
                name = imp.macro.name.ifBlank { key },
                steps = imp.macro.steps,
                loops = imp.macro.loops,
                loopDelayMs = imp.macro.loopDelayMs,
                fixedDelayMs = imp.macro.fixedDelayMs,
                speed = imp.macro.speed,
                id = newId(list),
                guide = imp.macro.guide,
                autoRecover = imp.macro.autoRecover
            )
            list.add(novo)
            map.put(key, JSONObject().put("id", novo.id).put("sha", sha))
            added++
            changed = true
            Log.i(TAG, "macro \"$key\" instalado (${novo.steps.size} passos)")
        }

        // Sumiu do repositório: sai do celular. Só o que veio do servidor.
        for (key in map.keys().asSequence().toList()) {
            if (key in seen) continue
            val id = map.optJSONObject(key)?.optString("id") ?: ""
            map.remove(key)
            if (list.removeAll { it.id == id }) {
                removedIds.add(id)
                removed++
                Log.i(TAG, "macro \"$key\" removido (saiu do servidor)")
            }
            changed = true
        }

        if (!changed) return "${map.length()} macros"

        Store.save(ctx, list)
        saveMap(ctx, map)
        val sel = Store.selectedId(ctx)
        if (sel != null && list.none { it.id == sel }) Store.setSelected(ctx, list.firstOrNull()?.id)

        // O serviço segura uma CÓPIA do macro em execução: avisa pra ele trocar
        // (ou parar, se o macro foi removido). Depois do save, nunca antes.
        if (s != null) {
            val cur = s.currentMacroId
            if (cur != null && cur in removedIds) {
                s.stopPlayback("Macro removido no servidor")
            } else if (cur != null && cur in replacedIds) {
                Store.byId(ctx, cur)?.let { s.restartWith(it) }
            }
            s.showBubbleNow()
        }
        val parts = ArrayList<String>()
        if (added > 0) parts.add("$added novo(s)")
        if (updated > 0) parts.add("$updated atualizado(s)")
        if (removed > 0) parts.add("$removed removido(s)")
        return parts.joinToString(", ").ifEmpty { "${map.length()} macros" }
    }

    private fun parseEnvelope(envelope: JSONObject, screen: Point): Porter.Imported? = try {
        Porter.parseImport(
            ByteArrayInputStream(envelope.toString().toByteArray(Charsets.UTF_8)),
            screen
        )
    } catch (t: Throwable) {
        Log.e(TAG, "macro do servidor inválido: ${t.message}")
        null
    }

    private fun replaceContent(local: Macro, from: Macro) {
        local.steps.clear()
        local.steps.addAll(from.steps)
        copySettings(local, from)
    }

    private fun copySettings(local: Macro, from: Macro) {
        local.name = from.name.ifBlank { local.name }
        local.loops = from.loops
        local.loopDelayMs = from.loopDelayMs
        local.fixedDelayMs = from.fixedDelayMs
        local.speed = from.speed
        local.autoRecover = from.autoRecover
        if (from.guide.isNotBlank()) local.guide = from.guide
    }

    /** Mesmos toques (tipo, pontos até 2px, duração, pausa), ignorando app/âncora aprendidos. */
    private fun stepsEquivalent(a: List<Step>, b: List<Step>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.type != y.type || x.pts.size != y.pts.size) return false
            if (x.durationMs != y.durationMs || x.delayBeforeMs != y.delayBeforeMs) return false
            for (j in x.pts.indices) {
                if (abs(x.pts[j].x - y.pts[j].x) > 2f || abs(x.pts[j].y - y.pts[j].y) > 2f) return false
            }
        }
        return true
    }

    private fun newId(list: List<Macro>): String {
        val ids = list.map { it.id }.toSet()
        var n = System.currentTimeMillis()
        while (ids.contains(n.toString())) n++
        return n.toString()
    }

    // ---------- macro da bolha ----------

    private fun applySelected(ctx: Context, cfg: JSONObject, s: ClickerService?) {
        val key = cfg.optString("selectedMacro", "")
        val p = prefs(ctx)
        // só age quando o VALOR muda no servidor: se o usuário escolheu outro
        // macro no celular, a sincronização seguinte não desfaz a escolha dele
        if (p.getString(KEY_SELECTED_KEY, "") == key) return
        val id = if (key.isBlank()) null else localIdFor(ctx, key)
        if (key.isNotBlank() && id == null) return // macro ainda não chegou: tenta na próxima
        p.edit().putString(KEY_SELECTED_KEY, key).apply()
        if (id != null && Store.selectedId(ctx) != id) {
            Store.setSelected(ctx, id)
            s?.showBubbleNow()
        }
    }

    // ---------- autorun ----------

    /**
     * autorun no config.json: {enabled, macro, gapMs, quiet, runId}. Com
     * enabled=true, cada celular comeca o macro UMA vez por combinacao de
     * valores (mudar o runId faz todo mundo recomecar). enabled=false nao faz
     * nada: nao para quem ja roda.
     */
    private fun applyAutorun(ctx: Context, a: JSONObject?, s: ClickerService?) {
        if (a == null) return
        val enabled = a.optBoolean("enabled", false)
        val key = a.optString("macro", "")
        val gap = a.optLong("gapMs", 0L).coerceIn(0L, 3_600_000L)
        val quiet = a.optBoolean("quiet", false)
        val runId = a.optString("runId", "")
        val fp = "$enabled|$key|$gap|$quiet|$runId"
        val p = prefs(ctx)
        if (p.getString(KEY_AUTORUN_FP, "") == fp) return
        if (s == null) {
            Log.i(TAG, "autorun: serviço desligado, fica pra quando ligar")
            return
        }
        if (!enabled) {
            // Desligado = o servidor NAO manda rodar; quem ja esta rodando
            // continua. Pra parar todo mundo existe o command "stop".
            p.edit().putString(KEY_AUTORUN_FP, fp).apply()
            return
        }
        val id = localIdFor(ctx, key)
        if (id == null) {
            Log.i(TAG, "autorun: macro \"$key\" não está neste celular")
            return
        }
        val m = Store.byId(ctx, id) ?: return
        p.edit().putString(KEY_AUTORUN_FP, fp).apply()
        Store.setSelected(ctx, m.id)
        Log.i(TAG, "autorun: \"${m.name}\" intervalo=${gap / 1000}s especial=$quiet (runId=$runId)")
        s.applyAutorun(m, gap, quiet)
    }

    // ---------- comando avulso ----------

    /** command no config.json: {id, action}. Cada id roda uma vez só. */
    private fun applyCommand(ctx: Context, c: JSONObject?, s: ClickerService?) {
        if (c == null) return
        val id = c.optString("id", "")
        val action = c.optString("action", "").trim().lowercase()
        if (id.isBlank() || action.isBlank()) return
        val p = prefs(ctx)
        if (p.getString(KEY_COMMAND_ID, "") == id) return
        if (s == null) return // sem serviço não dá pra obedecer; tenta na próxima
        p.edit().putString(KEY_COMMAND_ID, id).apply()
        Log.i(TAG, "comando $id: $action")
        when (action) {
            "stop", "parar" -> s.stopPlayback("Parado pelo servidor")
            "restart", "reiniciar" -> s.restartCurrent()
            "relearn", "reaprender" -> s.relearnAndRestart()
            "home", "inicio" -> s.goHomeNow()
            else -> Log.i(TAG, "comando desconhecido: $action")
        }
    }
}
