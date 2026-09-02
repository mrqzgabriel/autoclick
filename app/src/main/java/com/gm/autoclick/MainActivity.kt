package com.gm.autoclick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var empty: TextView
    private lateinit var serverInfo: TextView
    private lateinit var deviceIdView: TextView
    private lateinit var btnUpdate: Button
    private lateinit var btnAllowInstall: Button

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.tvStatus)
        listContainer = findViewById(R.id.listContainer)
        empty = findViewById(R.id.tvEmpty)

        // Primeira execução: instala o macro "Aquecimento" que vem no app
        Store.seedDefaultIfNeeded(this)

        // A pilula de status abre as configuracoes de acessibilidade (tambem
        // quando ligado: e la que se desliga pra usar app de banco).
        status.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Procure \"AutoClick\" e ligue")
            } catch (_: Throwable) {
                toast("Abra Configuracoes > Acessibilidade")
            }
        }

        findViewById<Button>(R.id.btnRecord).setOnClickListener { recordDialog() }

        findViewById<Button>(R.id.btnBubble).setOnClickListener {
            val s = ClickerService.instance
            if (s == null) needService() else {
                s.showBubbleNow()
                toast("Bolha ligada")
            }
        }

        findViewById<Button>(R.id.btnHelp).setOnClickListener { helpDialog() }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            try {
                // */* de proposito: WhatsApp/gerenciadores reportam .json como
                // octet-stream e um filtro por mime deixaria o arquivo acinzentado
                importLauncher.launch(arrayOf("*/*"))
            } catch (_: Throwable) {
                toast("Não deu pra abrir o seletor de arquivos")
            }
        }

        // ---- card do servidor (VPS): sincronizacao e atualizacao do app ----
        serverInfo = findViewById(R.id.tvServerInfo)
        deviceIdView = findViewById(R.id.tvDeviceId)
        btnUpdate = findViewById(R.id.btnUpdateApp)
        btnAllowInstall = findViewById(R.id.btnAllowInstall)
        // Botao "Atualizar": baixa (se precisar) e abre a caixa do sistema
        btnUpdate.setOnClickListener {
            Updater.installNow(this) { msg -> toast(msg) }
            refreshStatus()
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            Sync.runNow(this, "botão") { ok, msg ->
                toast(if (ok) "Sincronizado: $msg" else "Não sincronizou: $msg")
                refresh()
            }
            refreshStatus()
        }
        findViewById<Button>(R.id.btnCopyId).setOnClickListener { copyDeviceId() }
        findViewById<Button>(R.id.btnServer).setOnClickListener { serverDialog() }
        btnAllowInstall.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                toast("Ligue \"Permitir desta fonte\" pro AutoClick")
            } catch (_: Throwable) {
                toast("Abra Configurações > Apps > Acesso especial > Instalar apps desconhecidos")
            }
        }
        Sync.ensureScheduled(this)

        handleAutorun(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutorun(intent)
    }

    /**
     * Dispara o macro por comando, sem ninguem tocar na tela. Hoje serve pro
     * teste pelo cabo; e a mesma porta que o servidor vai usar pra comandar os
     * celulares.
     *
     *   am start -n com.gm.autoclick/.MainActivity --ez autorun true --ei gapms 30000
     *   am start -n com.gm.autoclick/.MainActivity --ez autorun true --ez parar true
     *   am start -n com.gm.autoclick/.MainActivity --es server https://meu-servidor
     *   am start -n com.gm.autoclick/.MainActivity --ez sync true
     *
     * gapms  = intervalo entre passadas em ms (0 = loop normal)
     * quiet  = modo Especial (dorme das 21h as 9h)
     * parar  = so interrompe o que estiver rodando
     * server = grava o endereco do servidor e sincroniza na hora
     * sync   = sincroniza com o servidor agora
     */
    private fun handleAutorun(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra("server")?.let { url ->
            intent.removeExtra("server")
            Sync.setServerUrl(this, url)
            toast("Servidor: ${url.trim().ifBlank { "removido" }}")
            Sync.runNow(this, "comando") { _, _ -> refresh() }
        }
        if (intent.getBooleanExtra("sync", false)) {
            intent.removeExtra("sync")
            Sync.runNow(this, "comando") { ok, msg ->
                toast(if (ok) "Sincronizado: $msg" else "Não sincronizou: $msg")
                refresh()
            }
        }
        if (!intent.getBooleanExtra("autorun", false)) return
        // consome o pedido: sem isso, girar a tela ou voltar pro app dispararia
        // o macro de novo sozinho
        intent.removeExtra("autorun")

        val s = ClickerService.instance
        if (s == null) {
            toast("Serviço desligado: ligue em Acessibilidade")
            return
        }
        if (intent.getBooleanExtra("parar", false)) {
            s.stopPlayback("Parado por comando")
            return
        }
        val m = Store.selected(this)
        if (m == null) {
            toast("Nenhum macro selecionado")
            return
        }
        // relearn: esquece app+alvo de cada passo pra reaprender do zero (usado
        // quando a rota foi aprendida sob uma tela errada, ex: popup do Chrome)
        if (intent.getBooleanExtra("relearn", false)) {
            m.steps.forEach { it.app = ""; it.anchor = "" }
            Store.update(this, m)
        }
        val gap = intent.getIntExtra("gapms", 0).toLong().coerceIn(0L, 3_600_000L)
        val quiet = intent.getBooleanExtra("quiet", false)
        Store.setSelected(this, m.id)
        // 3 s de folga E volta pra tela inicial: assim a passada comeca de onde
        // o macro espera comecar, nao de cima da tela do proprio app.
        s.start(m, 3000, gap, quietWindow = quiet)
        goHome()
    }

    override fun onResume() {
        super.onResume()
        // a sincronizacao termina em segundo plano: redesenha a lista quando chegar
        Sync.listener = { if (!isFinishing && !isDestroyed) refresh() }
        Sync.syncSoon(this)
        refresh()
        // Atualizacao baixada esperando o toque: abre a caixa do sistema sozinha
        // ao entrar no app (uma vez por atualizacao). E o passo manual que o
        // HyperOS exige, reduzido a tocar em "Atualizar".
        if (Updater.pendingUserIntent != null && !Updater.pendingShownAuto) {
            Updater.pendingShownAuto = true
            handler.postDelayed({ if (!isFinishing) Updater.showPending(this) }, 400)
        }
        // O serviço pode ligar/religar com a tela já aberta (o usuário liga em
        // Acessibilidade, ou o HyperOS mata e o Android reconecta). Sem esse
        // poll a pílula fica mentindo até sair e voltar no app.
        statusPoll.run()
    }

    override fun onPause() {
        super.onPause()
        Sync.listener = null
        handler.removeCallbacks(statusPoll)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------

    /** Só a pílula: barato o bastante pra rodar de segundo em segundo. */
    private fun refreshStatus() {
        val on = ClickerService.instance != null
        status.text = if (on) "●  Serviço ligado" else "●  Serviço desligado · toque pra ligar"
        status.setBackgroundResource(if (on) R.drawable.bg_status_on else R.drawable.bg_status_off)
        status.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.status_on_text else R.color.status_off_text)
        )
        refreshServer()
    }

    /** Card do servidor: endereco, ultima sincronizacao, versao e atualizacao. */
    private fun refreshServer() {
        val url = Sync.serverUrl(this)
        val sb = StringBuilder()
        sb.append("Servidor: ").append(url.ifEmpty { "não configurado" }).append('\n')
        sb.append("Sincronização: ").append(Sync.lastSummary(this)).append('\n')
        sb.append("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · ${Updater.summary()}")
        if (!Updater.canInstall(this)) {
            sb.append("\nAtualização automática: falta permitir \"instalar apps desconhecidos\"")
        }
        serverInfo.text = sb
        val name = Sync.deviceName(this)
        val chip = Sync.chipPhone(this)
        deviceIdView.text = "Este celular: ${if (name.isNotEmpty()) "$name · " else ""}${Sync.deviceId(this)}" +
            "\nChip detectado: ${chip.ifEmpty { "ainda não (lê na 1ª passada do macro)" }}"
        // Botao de atualizar: some quando nao ha versao nova; o texto acompanha a fase
        val avail = Updater.availableCode()
        val pending = Updater.pendingUserIntent != null
        btnUpdate.visibility = if (avail > 0 || pending) View.VISIBLE else View.GONE
        btnUpdate.text = when {
            Updater.status == "downloading" -> "Baixando a atualização…"
            Updater.status == "waiting" -> "Instalar a atualização agora"
            Updater.status == "installing" -> "Instalando…"
            pending -> "Instalar a atualização ${Updater.availableName().ifEmpty { "" }}".trim()
            else -> "Atualizar o AutoClick para ${Updater.availableName()}".trim()
        }
        btnAllowInstall.visibility = if (Updater.canInstall(this)) View.GONE else View.VISIBLE
    }

    private fun copyDeviceId() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("AutoClick", Sync.deviceId(this)))
        toast("ID copiado: use no config.json do servidor (devices)")
    }

    private fun serverDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), 0)
        }
        val field = EditText(this).apply {
            hint = "https://autoclick-xxxx.easypanel.host"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Sync.serverUrl(this@MainActivity))
        }
        box.addView(field)
        AlertDialog.Builder(this)
            .setTitle("Servidor")
            .setMessage(
                "Endereço do servidor (EasyPanel) de onde vêm os macros, as " +
                    "configurações e as atualizações do app. Sincroniza a cada " +
                    "${Sync.pollSeconds(this) / 60} min."
            )
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                Sync.setServerUrl(this, field.text.toString())
                refreshStatus()
                Sync.runNow(this, "servidor novo") { ok, msg ->
                    toast(if (ok) "Sincronizado: $msg" else "Não sincronizou: $msg")
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refresh() {
        refreshStatus()

        val macros = Store.load(this)
        val selected = Store.selectedId(this)
        empty.visibility = if (macros.isEmpty()) View.VISIBLE else View.GONE
        listContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (m in macros) {
            val row = inflater.inflate(R.layout.item_macro, listContainer, false) as MaterialCardView
            val isSel = m.id == selected

            row.isChecked = isSel
            row.findViewById<TextView>(R.id.tvName).text = m.name
            row.findViewById<TextView>(R.id.tvBadge).visibility =
                if (isSel) View.VISIBLE else View.GONE
            row.findViewById<TextView>(R.id.tvInfo).text = metaLine(m)

            row.findViewById<Button>(R.id.btnRun).setOnClickListener { runWithDelay(m) }
            row.findViewById<Button>(R.id.btnSelect).setOnClickListener {
                Store.setSelected(this, m.id)
                ClickerService.instance?.showBubbleNow()
                toast("\"${m.name}\" é o macro da bolha")
                refresh()
            }
            val guideBtn = row.findViewById<View>(R.id.btnGuide)
            guideBtn.visibility = if (m.guide.isNotBlank()) View.VISIBLE else View.GONE
            guideBtn.setOnClickListener { guideDialog(m) }

            row.findViewById<View>(R.id.btnMore).setOnClickListener { v -> macroMenu(v, m) }

            listContainer.addView(row)
        }
    }

    /** So mostra o que difere do padrao: "14 passos · ∞ · 2x · 500ms". */
    private fun metaLine(m: Macro): String {
        val sb = StringBuilder("${m.steps.size} passos · ")
        sb.append(if (m.loops <= 0) "∞" else "${m.loops}×")
        if (m.speed != 1f) {
            val s = if (m.speed % 1f == 0f) m.speed.toInt().toString() else m.speed.toString()
            sb.append(" · ${s}x")
        }
        if (m.fixedDelayMs > 0) sb.append(" · ${m.fixedDelayMs}ms")
        return sb.toString()
    }

    /** Popup com o print de como a tela precisa estar pro macro acertar os toques. */
    private fun guideDialog(m: Macro) {
        val resId = resources.getIdentifier(m.guide, "drawable", packageName)
        if (resId == 0) {
            toast("Imagem de referência não encontrada")
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null)
        view.findViewById<TextView>(R.id.tvGuideText).text =
            "Pra este macro funcionar certo, a tela inicial do celular precisa estar " +
                "assim, com os ícones nesta mesma ordem, e o site já aberto.\n\n" +
                "Se você mudar os ícones de lugar, os toques vão cair no lugar errado."
        view.findViewById<android.widget.ImageView>(R.id.ivGuide).setImageResource(resId)

        AlertDialog.Builder(this)
            .setTitle("Como a tela deve estar")
            .setView(view)
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun macroMenu(anchor: View, m: Macro) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_macro, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.btnCfg -> {
                        configDialog(m)
                        true
                    }
                    R.id.btnExport -> {
                        shareMacro(m)
                        true
                    }
                    R.id.btnDel -> {
                        confirmDelete(m)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmDelete(m: Macro) {
        AlertDialog.Builder(this)
            .setTitle("Excluir \"${m.name}\"?")
            .setPositiveButton("Excluir") { _, _ ->
                Store.delete(this, m.id)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runWithDelay(m: Macro) {
        if (ClickerService.instance == null) return needService()
        val options = arrayOf(
            "Agora (loop normal)",
            "A cada 30 segundos (teste)",
            "A cada 5 min",
            "A cada 10 min",
            "Especial (5 min, dorme 21h-9h)"
        )
        val intervals = longArrayOf(0L, 30_000L, 5 * 60_000L, 10 * 60_000L, 5 * 60_000L)
        AlertDialog.Builder(this)
            .setTitle("Rodar \"${m.name}\"")
            .setItems(options) { _, which ->
                // Busca o serviço AGORA: entre abrir o diálogo e clicar, o sistema
                // pode ter reiniciado o serviço e a instância velha falha calada.
                val s = ClickerService.instance ?: return@setItems needService()
                Store.setSelected(this, m.id)
                // tela inicial antes de começar, pra o passo 1 aprender a rota
                // certa (senão aprende o app que estiver na frente)
                s.goHomeNow()
                s.start(m, 3000, intervals[which], quietWindow = which == 4)
                toast(
                    if (which == 0) "Começa em 5 segundos: abra o app agora"
                    else "Começa em 5 s, roda 1x e repete ${options[which].lowercase()}"
                )
                moveTaskToBack(true)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // EXPORTAR / IMPORTAR
    // ------------------------------------------------------------------

    private fun shareMacro(m: Macro) {
        val uri: Uri = try {
            Porter.export(this, m)
        } catch (_: Throwable) {
            toast("Não deu pra criar o arquivo.")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            // NADA de EXTRA_TEXT junto: o WhatsApp descarta o anexo
            clipData = ClipData.newRawUri("macro", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Enviar \"${m.name}\""))
        } catch (_: Throwable) {
            toast("Nenhum app pra compartilhar o arquivo.")
        }
    }

    private fun importFrom(uri: Uri) {
        // Thread: um arquivo do Drive ainda nao baixado bloqueia lendo da rede
        Thread {
            val result = runCatching {
                Porter.parseImport(contentResolver.openInputStream(uri), Porter.screenSize(this))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(::finishImport, ::showImportError)
            }
        }.start()
    }

    private fun finishImport(imp: Porter.Imported) {
        val list = Store.load(this)

        // id original = timestamp de criacao: colidiria ao reimportar
        val ids = list.map { it.id }.toSet()
        var idNum = System.currentTimeMillis()
        while (ids.contains(idNum.toString())) idNum++

        var name = imp.macro.name.ifBlank { "Macro" }
        if (list.any { it.name == name }) {
            var n = 2
            while (list.any { it.name == "$name ($n)" }) n++
            name = "$name ($n)"
        }

        val novo = Macro(
            name = name,
            steps = imp.macro.steps,
            loops = imp.macro.loops,
            loopDelayMs = imp.macro.loopDelayMs,
            fixedDelayMs = imp.macro.fixedDelayMs,
            speed = imp.macro.speed,
            id = idNum.toString(),
            autoRecover = imp.macro.autoRecover
        )
        Store.add(this, novo)
        refresh()

        val dest = Porter.screenSize(this)
        when {
            imp.rescaled -> {
                var msg = "Este macro foi gravado numa tela ${imp.origW}×${imp.origH} " +
                    "e a sua é ${dest.x}×${dest.y}. Os toques foram ajustados na " +
                    "proporção. Rode uma vez pra conferir."
                if (imp.aspectDiffers) {
                    msg += "\n\nAs telas têm formatos diferentes; alguns toques " +
                        "podem cair fora do lugar."
                }
                AlertDialog.Builder(this)
                    .setTitle("\"$name\" importado")
                    .setMessage(msg)
                    .setPositiveButton("Entendi", null)
                    .show()
            }
            imp.origW == 0 -> {
                AlertDialog.Builder(this)
                    .setTitle("\"$name\" importado")
                    .setMessage(
                        "O arquivo não diz de que tela veio, então os toques não " +
                            "foram ajustados. Rode uma vez pra conferir."
                    )
                    .setPositiveButton("Entendi", null)
                    .show()
            }
            else -> toast("\"$name\" importado.")
        }
    }

    private fun showImportError(t: Throwable) {
        val msg = if (t is Porter.ImportError) t.message else "Não deu pra ler esse arquivo."
        AlertDialog.Builder(this)
            .setTitle("Não importou")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------------

    private fun recordDialog() {
        if (ClickerService.instance == null) return needService()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), 0)
        }
        val name = EditText(this).apply {
            hint = "Nome do macro"
            setText("Macro ${Store.load(this@MainActivity).size + 1}")
        }
        val pass = CheckBox(this).apply {
            text = "Repassar meus toques pro app (deixe marcado se a tela muda durante a sequência)"
            isChecked = true
            textSize = 13f
        }
        box.addView(name)
        box.addView(pass)

        AlertDialog.Builder(this)
            .setTitle("Gravar novo macro")
            .setMessage(
                "A tela vai ficar com uma borda vermelha e um painel REC em cima.\n\n" +
                    "Faça a sequência de toques na velocidade que quiser (as pausas são gravadas também) " +
                    "e depois toque em ✓ no painel pra salvar.\n\n" +
                    "Pra voltar à tela inicial DURANTE a sequência, use o 🏠 do painel " +
                    "(não arraste de baixo pra cima: na repetição esse arrasto não vira " +
                    "navegação, cai dentro do app).\n\n" +
                    "Pra atualizar a página do Chrome (o F5 do computador), use o 🔄 do painel: " +
                    "ele aperta o Atualizar do menu do Chrome na hora e de novo em cada repetição."
            )
            .setView(box)
            .setPositiveButton("Começar") { _, _ ->
                // Instância fresca na hora do clique.
                val s = ClickerService.instance ?: return@setPositiveButton needService()
                s.startRecording(name.text.toString().ifBlank { "Macro" }, pass.isChecked)
                goHome()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun configDialog(m: Macro) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), 0)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, pad() / 2, 0, 0)
        }

        fun field(value: String, decimal: Boolean = false) = EditText(this).apply {
            setText(value)
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
        }

        val name = EditText(this).apply { setText(m.name) }
        val loops = field(m.loops.toString())
        val loopDelay = field(m.loopDelayMs.toString())
        val fixed = field(m.fixedDelayMs.toString())
        val speed = field(m.speed.toString(), decimal = true)

        box.addView(label("Nome"))
        box.addView(name)
        box.addView(label("Repetições (0 = infinito)"))
        box.addView(loops)
        box.addView(label("Pausa entre repetições (ms)"))
        box.addView(loopDelay)
        box.addView(label("Forçar intervalo entre toques (ms, 0 = usar o que foi gravado)"))
        box.addView(fixed)
        box.addView(label("Velocidade (1 = normal, 2 = duas vezes mais rápido)"))
        box.addView(speed)

        val recover = CheckBox(this).apply {
            text = "Recuperação automática: se aparecer uma tela que não é a de " +
                "sempre, fecha as abas do navegador, volta pra tela inicial e " +
                "recomeça a passada"
            isChecked = m.autoRecover
            textSize = 13f
        }
        box.addView(recover)

        val learned = m.steps.count { it.app.isNotBlank() }
        val relearn = CheckBox(this).apply {
            text = "Reaprender a rota (esquece o app e o alvo de cada passo e " +
                "aprende de novo na próxima passada)"
            isChecked = false
            textSize = 13f
            // sem rota aprendida nao ha o que esquecer
            isEnabled = learned > 0
        }
        box.addView(relearn)

        val steps = m.steps.take(12)
            .mapIndexed { i, s ->
                val where = if (s.app.isBlank()) "?" else s.app.substringAfterLast('.')
                val alvo = if (s.anchor.isBlank()) "" else " → ${s.anchor}"
                "${i + 1}. ${s.label} · ${s.delayBeforeMs}ms · $where$alvo"
            }
            .joinToString("\n")
        box.addView(
            label(
                "\nPassos gravados (app esperado → elemento que ele toca):\n$steps" +
                    (if (m.steps.size > 12) "\n..." else "") +
                    "\n\nRota aprendida em $learned de ${m.steps.size} passos."
            )
        )

        // O conteudo passou a ser alto demais pra caber em tela pequena: sem o
        // scroll, o botao Salvar sai pra fora e o dialogo fica sem saida.
        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(scroll)
            .setPositiveButton("Salvar") { _, _ ->
                m.name = name.text.toString().ifBlank { m.name }
                m.loops = loops.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                m.loopDelayMs = loopDelay.text.toString().toLongOrNull()?.coerceIn(0, 600_000) ?: 800
                m.fixedDelayMs = fixed.text.toString().toLongOrNull()?.coerceIn(0, 600_000) ?: 0
                m.speed = speed.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
                m.autoRecover = recover.isChecked
                if (relearn.isChecked) m.steps.forEach { it.app = ""; it.anchor = "" }
                Store.update(this, m)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun helpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ajuda")
            .setMessage(
                "1) Botão de acessibilidade cinza (\"Configuração restrita\"):\n" +
                    "Configurações > Apps > Gerenciar apps > AutoClick > menu ⋮ no canto > " +
                    "Permitir configurações restritas. Depois volte e ligue o serviço.\n\n" +
                    "2) Pra HyperOS não matar o app:\n" +
                    "Configurações > Apps > AutoClick > Economia de bateria > Sem restrições.\n" +
                    "E em Autostart (Segurança > Permissões > Autostart), ligue o AutoClick.\n\n" +
                    "3) No card de cada macro, o ⋮ tem Ajustes, Exportar e Excluir.\n\n" +
                    "4) Passar macro pra outro celular: ⋮ > Exportar e mande o arquivo " +
                    "(WhatsApp, Drive...). No outro celular (com o AutoClick instalado), " +
                    "baixe o arquivo, toque em Importar e procure em Recentes ou Downloads. " +
                    "Se as telas forem de tamanhos diferentes, os toques são ajustados na " +
                    "proporção — rode uma vez pra conferir.\n\n" +
                    "5) Dica de auto clique simples: grave 1 toque só e em Ajustes coloque " +
                    "\"Forçar intervalo\" (ex: 500 ms) e repetições 0 (infinito).\n\n" +
                    "6) O macro guarda coordenadas da tela. Girou o celular ou mudou o " +
                    "tamanho da fonte: grave de novo.\n\n" +
                    "7) Apps de banco costumam recusar abrir com acessibilidade ligada. " +
                    "Desligue o serviço (toque na pílula de status) quando for usar o banco.\n\n" +
                    "8) Recuperação automática (⋮ > Ajustes): o macro anota em qual app " +
                    "cada passo acontece. Se na hora de repetir a tela estiver em outro " +
                    "app (chip banido, aba que abriu sozinha, popup), ele não toca no " +
                    "escuro: fecha as abas do navegador, volta pra tela inicial e " +
                    "recomeça a passada. A rota é aprendida sozinha na primeira passada.\n\n" +
                    "9) Servidor: o app fala com o servidor a cada poucos minutos. De lá " +
                    "vêm macros novos, configurações e a versão nova do app, que se " +
                    "instala sozinha. Pra isso, permita \"instalar apps desconhecidos\" " +
                    "pro AutoClick uma vez (botão no card Servidor)."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    // ------------------------------------------------------------------

    private fun needService() {
        AlertDialog.Builder(this)
            .setTitle("Serviço desligado")
            .setMessage("Ligue o AutoClick em Configurações > Acessibilidade > Apps baixados. Sem isso o app não pode tocar na tela.")
            .setPositiveButton("Abrir acessibilidade") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(home)
        } catch (_: Throwable) {
            moveTaskToBack(true)
        }
    }

    private fun pad(): Int = (16 * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
