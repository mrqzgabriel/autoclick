package com.gm.autoclick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualiza o próprio app quando o servidor publica um versionCode maior.
 *
 * Caminho: baixa o APK pro cache -> confere o sha256 -> espera um momento
 * seguro (fora de uma passada do macro) -> instala por PackageInstaller.
 *
 * Por que instala SEM ninguém tocar na tela: no Android 12+ um app pode se
 * atualizar sem confirmação quando (1) declara UPDATE_PACKAGES_WITHOUT_USER_ACTION,
 * (2) pede USER_ACTION_NOT_REQUIRED na sessão, (3) o APK novo tem a mesma
 * assinatura e targetSdk >= 29 e (4) o celular permitiu "instalar apps
 * desconhecidos" pro AutoClick (uma vez; pelo cabo:
 * `appops set com.gm.autoclick REQUEST_INSTALL_PACKAGES allow`).
 * Se alguma condição faltar, o sistema devolve STATUS_PENDING_USER_ACTION com
 * uma tela de confirmação: guardamos o intent e a tela do app mostra o botão
 * "Instalar atualização". O painel do servidor avisa que falta o toque.
 *
 * Depois da instalação o processo morre, o Android religa o serviço de
 * acessibilidade sozinho e o resumeIfWasRunning retoma o macro.
 */
object Updater {
    private const val TAG = "AutoClick"
    private const val RETRY_AFTER_FAIL_MS = 30 * 60_000L
    private const val SAFE_POLL_MS = 15_000L
    private const val SAFE_MAX_WAIT_MS = 15 * 60_000L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_APK_BYTES = 80L * 1024 * 1024

    private val handler = Handler(Looper.getMainLooper())

    @Volatile var status = "idle"
    @Volatile var target = 0
    @Volatile var error = ""
    @Volatile var pendingUserIntent: Intent? = null

    // A caixa de confirmacao pendente ja foi aberta sozinha pela tela? (uma vez
    // por atualizacao; o botao "Atualizar" reabre quantas vezes for preciso)
    @Volatile var pendingShownAuto = false

    // Ultimo bloco "app" que o servidor mandou (versao publicada) e de onde
    // veio: e o que o botao "Atualizar" da tela usa pra saber se ha versao nova
    // sem esperar a proxima sincronizacao.
    @Volatile private var lastApp: JSONObject? = null
    @Volatile private var lastServerUrl = ""

    // O usuario apertou "Atualizar": pula a espera por momento seguro e abre a
    // caixa do sistema assim que ela existir, mesmo com o macro rodando.
    @Volatile private var userRequested = false

    @Volatile private var busy = false
    private var lastFailAt = 0L
    private var failures = 0

    // Recuo silencioso -> confirmacao: o HyperOS ABORTA a instalacao silenciosa
    // (USER_ACTION_NOT_REQUIRED) de um app comum, mesmo com "instalar apps
    // desconhecidos" liberado — devolve INSTALL_FAILED_ABORTED em vez de mostrar
    // a caixa padrao. Entao, se a silenciosa aborta, re-tenta UMA vez sem o
    // pedido de silencio: ai o sistema mostra "Atualizar?" e basta um toque. No
    // Android stock (emulador, MDM) a primeira ja passa silenciosa.
    private var pendingFile: File? = null
    private var pendingCode = 0
    private var lastAttemptSilent = false
    private var triedPrompt = false

    // Opcoes vindas do config.json ("update"): permitem trocar o modo de
    // instalacao pelo servidor, sem cabo. silent=false forca a tela de
    // confirmacao do sistema; packageSource muda o que declaramos como origem.
    @Volatile private var optSilent = true
    @Volatile private var optSource = "store"

    fun stateJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("target", target)
        .put("error", error)
        .put("failures", failures)

    /** O celular deixa o AutoClick instalar apps ("fontes desconhecidas")? */
    fun canInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (_: Throwable) {
        false
    }

    /** Texto curto pra tela do app. */
    fun summary(): String = when (status) {
        "idle" -> "atualizado"
        "downloading" -> "baixando a versão $target…"
        "waiting" -> "versão $target baixada, esperando o macro terminar a passada…"
        "installing" -> "instalando a versão $target…"
        "needs_user" -> "versão $target pronta: toque em \"Instalar atualização\""
        "failed" -> "falhou (${error.ifBlank { "?" }})"
        "done" -> "instalada"
        else -> status
    }

    /**
     * Chamado pela sincronização (main thread) com o bloco "app" do manifesto.
     */
    /** versionCode publicado no servidor, se for maior que o instalado; senao 0. */
    fun availableCode(): Int {
        val code = lastApp?.optInt("versionCode", 0) ?: 0
        return if (code > BuildConfig.VERSION_CODE) code else 0
    }

    fun availableName(): String = lastApp?.optString("versionName", "") ?: ""

    /**
     * Botao "Atualizar" da tela. O usuario pediu, entao vai ate o fim: se a
     * caixa do sistema ja esta pronta, abre; se so falta baixar, baixa e abre;
     * se nao ha versao nova, diz isso. Mensagens vao pro toast da tela.
     */
    fun installNow(ctx: Context, say: (String) -> Unit) {
        if (pendingUserIntent != null) {
            if (!showPending(ctx)) say("Não deu pra abrir a caixa de instalação. Tente de novo.")
            return
        }
        val code = availableCode()
        if (code == 0) {
            say("Já está na versão mais nova (${BuildConfig.VERSION_NAME}).")
            return
        }
        if (busy) {
            when (status) {
                "downloading" -> say("Baixando a atualização…")
                // baixada, esperando a passada acabar: o toque pula a espera
                "waiting" -> {
                    userRequested = true
                    say("Instalando em instantes…")
                }
                else -> say("Instalando…")
            }
            return
        }
        userRequested = true
        // libera a nova tentativa mesmo dentro da janela de 30 min pos-falha
        lastFailAt = 0L
        status = "idle"
        say("Baixando a versão ${availableName()}…")
        check(ctx, lastApp, lastServerUrl, null)
    }

    fun check(ctx: Context, app: JSONObject?, serverUrl: String, opts: JSONObject? = null) {
        if (opts != null) {
            optSilent = opts.optBoolean("silent", true)
            optSource = opts.optString("packageSource", "store").lowercase()
        }
        if (app != null) {
            lastApp = app
            lastServerUrl = serverUrl
        }
        if (app == null) return
        val code = app.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) {
            if (status != "idle" && !busy) {
                status = "idle"
                error = ""
                pendingUserIntent = null
            }
            return
        }
        if (busy) return
        if (status == "needs_user" && target == code) return
        if (status == "failed" && target == code &&
            SystemClock.uptimeMillis() - lastFailAt < RETRY_AFTER_FAIL_MS
        ) return

        val rel = app.optString("url", "/apk/AutoClick.apk")
        val url = if (rel.startsWith("http")) rel else serverUrl.trimEnd('/') + "/" + rel.trimStart('/')
        val sha = app.optString("sha256", "").lowercase()
        val size = app.optLong("size", 0L)

        busy = true
        target = code
        status = "downloading"
        error = ""
        pendingUserIntent = null
        Log.i(TAG, "atualização: ${BuildConfig.VERSION_CODE} -> $code ($url)")
        val appCtx = ctx.applicationContext
        Thread {
            val r = runCatching { download(appCtx, url, code, sha, size) }
            handler.post {
                r.fold(
                    { file -> installWhenSafe(appCtx, file, code, 0L) },
                    { t -> fail("download: ${t.message ?: t.javaClass.simpleName}") }
                )
            }
        }.start()
    }

    private fun updateDir(ctx: Context): File = File(ctx.cacheDir, "update").apply { mkdirs() }

    private fun download(ctx: Context, url: String, code: Int, sha: String, size: Long): File {
        val dir = updateDir(ctx)
        val file = File(dir, "AutoClick-$code.apk")
        // sobra de outra versão não serve pra nada
        dir.listFiles()?.forEach { if (it != file) it.delete() }
        if (file.exists() && sha.isNotEmpty() && sha256(file) == sha) {
            Log.i(TAG, "atualização: APK $code já estava baixado")
            return file
        }
        val tmp = File(dir, "AutoClick-$code.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "AutoClick/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (BuildConfig.SYNC_TOKEN.isNotEmpty()) conn.setRequestProperty("X-Token", BuildConfig.SYNC_TOKEN)
            val http = conn.responseCode
            if (http !in 200..299) throw IllegalStateException("HTTP $http")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        total += n
                        if (total > MAX_APK_BYTES) throw IllegalStateException("APK grande demais")
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (size > 0 && total != size) throw IllegalStateException("tamanho $total != $size")
            if (sha.isNotEmpty() && got != sha) throw IllegalStateException("sha256 não bate")
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "atualização: APK $code baixado ($total bytes)")
            return file
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Instalar mata o processo: se fizer isso no meio de uma passada, o
     * WhatsApp fica aberto numa conversa e a rota se perde. Então espera o
     * macro estar parado ou na espera entre passadas (com folga). Depois de
     * 15 min esperando, instala de qualquer jeito: a recuperação de rota
     * resolve o resto, e ficar preso numa versão velha é pior.
     */
    private fun installWhenSafe(ctx: Context, file: File, code: Int, waited: Long) {
        val s = ClickerService.instance
        // pedido pelo botao: nao espera a passada terminar (a recuperacao de rota
        // resolve o que sobrar)
        val safe = userRequested || s == null || s.safeToUpdate()
        if (!safe && waited < SAFE_MAX_WAIT_MS) {
            status = "waiting"
            handler.postDelayed({ installWhenSafe(ctx, file, code, waited + SAFE_POLL_MS) }, SAFE_POLL_MS)
            return
        }
        pendingFile = file
        pendingCode = code
        triedPrompt = false
        // Silenciosa primeiro se o config permitir; senao ja pede confirmacao.
        attempt(ctx, file, code, silent = optSilent, waited = waited)
    }

    private fun attempt(ctx: Context, file: File, code: Int, silent: Boolean, waited: Long) {
        status = "installing"
        lastAttemptSilent = silent
        Log.i(TAG, "atualização: instalando $code silent=$silent (esperou ${waited / 1000}s)")
        Thread {
            val r = runCatching { commit(ctx, file, code, silent) }
            handler.post { r.onFailure { fail("instalar: ${it.message ?: it.javaClass.simpleName}") } }
        }.start()
    }

    private fun commit(ctx: Context, file: File, code: Int, silent: Boolean) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31 && silent) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // "loja": o servidor do AutoClick e a nossa loja. Sem isso o Android
            // 13+ trata como sideload e trava as configurações restritas de
            // novo a cada atualização (a chave de acessibilidade fica cinza).
            val src = when (optSource) {
                "unspecified", "none" -> PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED
                "other" -> PackageInstaller.PACKAGE_SOURCE_OTHER
                "local" -> PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                "downloaded" -> PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
                else -> PackageInstaller.PACKAGE_SOURCE_STORE
            }
            params.setPackageSource(src)
        }
        Log.i(TAG, "atualização: sessão silent=$silent source=$optSource")
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("AutoClick.apk", 0, file.length()).use { out ->
                FileInputStream(file).use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java)
                .setAction(UpdateReceiver.ACTION)
                .putExtra(UpdateReceiver.EXTRA_CODE, code)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            // MUTABLE e obrigatorio: o sistema preenche o status dentro do intent
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, code, intent, flags)
            session.commit(pending.intentSender)
        } catch (t: Throwable) {
            try {
                session.abandon()
            } catch (_: Throwable) {
            }
            throw t
        } finally {
            session.close()
        }
    }

    private fun fail(msg: String) {
        busy = false
        userRequested = false
        status = "failed"
        error = msg
        failures++
        lastFailAt = SystemClock.uptimeMillis()
        Log.e(TAG, "atualização falhou: $msg")
    }

    /** Resultado da sessão, entregue pelo UpdateReceiver. */
    fun onInstallStatus(ctx: Context, st: Int, msg: String, confirm: Intent?) {
        Log.i(TAG, "atualização: status=$st msg=$msg")
        when (st) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                busy = false
                status = "needs_user"
                pendingUserIntent = confirm?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                pendingShownAuto = false
                // Com o macro rodando, a caixa de confirmação por cima da tela
                // derrubaria a rota. Parado, pode aparecer: quem pegar o celular
                // só precisa tocar em Instalar. Se foi o botao "Atualizar" que
                // pediu, abre de qualquer jeito: a pessoa esta com o celular na mao.
                if (userRequested || ClickerService.instance?.playing != true) showPending(ctx)
                userRequested = false
            }
            PackageInstaller.STATUS_SUCCESS -> {
                busy = false
                status = "done"
                userRequested = false
                pendingUserIntent = null
                try {
                    updateDir(ctx).listFiles()?.forEach { it.delete() }
                } catch (_: Throwable) {
                }
            }
            else -> {
                // A silenciosa foi abortada (HyperOS): re-tenta uma vez pedindo a
                // confirmacao do sistema, em vez de desistir.
                val f = pendingFile
                if (lastAttemptSilent && !triedPrompt && f != null) {
                    triedPrompt = true
                    Log.i(TAG, "atualização: silenciosa abortada (status $st), re-tentando com confirmação")
                    attempt(ctx, f, pendingCode, silent = false, waited = 0L)
                } else {
                    fail("status $st ${msg.ifBlank { "" }}".trim())
                }
            }
        }
    }

    /** Abre a tela de confirmação do sistema (quando a instalação silenciosa não foi permitida). */
    fun showPending(ctx: Context): Boolean {
        val i = pendingUserIntent ?: return false
        return try {
            ctx.startActivity(i)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "não consegui abrir a confirmação de instalação", t)
            false
        }
    }
}
