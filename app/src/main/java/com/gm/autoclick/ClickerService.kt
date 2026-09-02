package com.gm.autoclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Servico de acessibilidade: e ele que consegue tocar na tela por cima de outros apps
 * (dispatchGesture, Android 7+) e desenhar a bolha sem precisar da permissao
 * "sobrepor a outros apps" (TYPE_ACCESSIBILITY_OVERLAY).
 */
class ClickerService : AccessibilityService() {

    companion object {
        private var live: ClickerService? = null
        private const val TAG = "AutoClick"

        /**
         * Só devolve o serviço se ele ainda estiver vivo. Sem isso, uma instância
         * antiga (token de janela já morto) faz addView falhar sem explicação.
         */
        val instance: ClickerService?
            get() = live?.takeIf { !it.dead }

        // ---------- passo 🔄 (atualizar página) ----------
        // Id do botão ⋮ já sem o prefixo do pacote. Chrome usa menu_button
        // dentro de menu_button_wrapper; os outros são de navegadores comuns.
        private val MENU_IDS = setOf(
            "menu_button", "menu_button_wrapper", "toolbar_menu_button",
            "more_button", "btn_menu", "menu"
        )

        // Descrição do ⋮. "Personalizar e controlar o Google Chrome" é a do
        // Chrome em português: a v1.7 procurava "Mais opções" e por isso nunca
        // achava nada.
        private val MENU_LABELS = listOf(
            "Personalizar e controlar", "Customize and control",
            "Mais opções", "More options", "Menu"
        )

        private val RELOAD_LABELS = listOf("Atualizar", "Recarregar", "Refresh", "Reload")

        // ---------- guarda de rota (v1.9) ----------
        // De quanto em quanto tempo a guarda reconfere se o app certo apareceu.
        private const val GUARD_POLL_MS = 500L

        // Quanto tempo ela espera antes de declarar "saiu da rota". Precisa ser
        // folgado: o Chrome abrindo uma pagina pesada passa fácil de 5s, e um
        // falso alarme aqui derruba uma passada que ia dar certo.
        private const val GUARD_TIMEOUT_MS = 12_000L

        // No PRIMEIRO passo da passada nada esta carregando ainda: se a tela ja
        // nao e a esperada, e erro na hora, nao demora. Esperar 12s ali so faz
        // o app parecer travado (foi o que aconteceu no teste de 31/08).
        private const val GUARD_TIMEOUT_START_MS = 2_500L

        // Depois de se recuperar, espera isso e tenta a passada de novo. Só nas
        // duas primeiras seguidas; da terceira em diante volta pro intervalo
        // normal, pra não ficar martelando quando o chip está banido de vez.
        private const val RETRY_AFTER_RECOVER_MS = 4_000L
        private const val OFF_ROUTE_FAST_RETRIES = 2

        // Botao do seletor de abas do navegador (o quadradinho com o número).
        private val TAB_SWITCHER_IDS = setOf(
            "tab_switcher_button", "tab_switcher_toolbar_button", "tabs_button"
        )

        // Id do item no menu do seletor de abas. Confirmado por dump no Chrome
        // 150 do Redmi: e o alvo preferido porque nao depende de idioma.
        private val CLOSE_ALL_TABS_IDS = setOf("close_all_tabs_menu_id")

        private val CLOSE_ALL_TABS_LABELS = listOf(
            "Fechar todas as guias", "Fechar todas as abas", "Fechar tudo",
            "Close all tabs", "Close all"
        )

        // Botao de confirmar do dialogo "Fechar todas as guias?". O id e o alvo
        // certo: o texto real no Chrome 150 e "Fechar todos os grupos e guias",
        // que nao bate com nenhuma frase curta que se tente adivinhar.
        private val CONFIRM_CLOSE_IDS = setOf("positive_button", "button1")

        private val CONFIRM_CLOSE_LABELS = listOf(
            "Fechar todos os grupos e guias", "Fechar todas as guias",
            "Fechar todas", "Fechar tudo", "Fechar",
            "Close all tabs and groups", "Close all", "Close"
        )

        // ---------- pagina intermediaria do wa.me ----------
        // O "Abrir no WhatsApp" e um link wa.me. Quase sempre o Android manda
        // direto pro app, mas as vezes para na pagina do wa.me no navegador,
        // que exige um toque no "Continuar para o chat". E ela tambem que enche
        // o Chrome de abas quando ninguem clica.
        // FRASE INTEIRA de proposito: o "Continuar" solto e o botao de ACEITAR
        // o popup de notificacoes do Chrome ("As notificacoes do Chrome
        // facilitam tudo") — clicar nele por engano ativaria a promo em vez de
        // seguir pro WhatsApp. Ver DISMISS_LABELS, que faz o oposto.
        private val CONTINUE_CHAT_LABELS = listOf(
            "Continuar para o chat", "Continuar no bate-papo",
            "Continuar para o bate-papo", "Ir para o chat", "Abrir o WhatsApp",
            "Continue to Chat", "Continue to chat", "Open WhatsApp"
        )

        // Botao "Iniciar conversa" do dialogo de confianca do WhatsApp, que
        // aparece pra numero fora dos contatos ("Você confia nesta pessoa?").
        private val START_CHAT_LABELS = listOf(
            "Iniciar conversa", "Começar conversa", "Iniciar bate-papo",
            "Continuar conversa", "Continuar", "Iniciar", "Sim, confio", "Confiar",
            "Start chat", "Start conversation", "Continue", "Trust", "Yes"
        )

        // Nunca clicar nestes no dialogo do WhatsApp, mesmo que o id bata: sao
        // os botoes de desistir/bloquear/denunciar. Trava de seguranca.
        private val WA_DANGER_WORDS = listOf(
            "cancel", "bloque", "block", "denunc", "report", "exclu", "delet",
            "apagar", "remov", "sair", "leave", "não", "nao", "not now", "no "
        )

        // Palavras que um botao de SEGUIR ADIANTE contem (portugues/ingles).
        // Usadas junto do id primary_button quando o texto exato mudou de
        // versao pra versao do WhatsApp.
        private val WA_GO_WORDS = listOf(
            "convers", "chat", "continu", "inici", "confi", "start", "ok", "sim", "yes"
        )

        // ---------- popups do proprio navegador ----------
        // Botoes que DISPENSAM um popup do Chrome (promo de notificacoes, login,
        // "tornar padrao") SEM aceitar nada. Esses popups (id modal_dialog_view)
        // cobrem a pagina do AllWin e travam o macro — foi o que quebrou o 2o
        // Redmi em 31/08. Frase exata.
        private val DISMISS_LABELS = listOf(
            "Agora não", "Não, obrigado", "Não agora", "Talvez mais tarde",
            "Dispensar", "Pular", "Fechar", "Ignorar",
            "No thanks", "Not now", "Skip", "Maybe later", "Dismiss", "Close"
        )

        // Nunca clicar nestes por engano ao dispensar (sao os de ACEITAR).
        private val ACCEPT_WORDS = listOf(
            "continuar", "continue", "ativar", "permitir", "aceitar", "sim",
            "ok", "entendi", "usar", "entrar", "sign in", "turn on", "allow",
            "yes", "enable", "got it"
        )

        // Container do dialogo do Chrome: so mexemos em dispensar quando ele esta
        // na tela, pra nunca clicar "Fechar/Ignorar" fora de um popup.
        private const val CHROME_DIALOG_ID = "modal_dialog_view"

        // Teto de cliques de destravamento por passo: se clicar e a tela nao
        // andar, e outra coisa, e insistir a cada meio segundo nao ajuda. 5
        // porque uma passada pode ter dois (wa.me + "Iniciar conversa") mais
        // margem pra transicao de tela.
        private const val MAX_UNBLOCK_TRIES = 5

        // Espera antes de retomar sozinho: logo apos o boot o launcher ainda
        // esta se montando e a passada comecaria na tela errada.
        private const val RESUME_DELAY_MS = 20_000L

        // ---------- vigia (watchdog) ----------
        // De quanto em quanto tempo o vigia confere se o laco esta vivo.
        private const val WATCHDOG_MS = 30_000L

        // Folga: so age se o proximo passo ja devia ter disparado ha mais que
        // isso. Um passo gravado pode ter ate 120s de espera, entao a folga fica
        // em cima do horario esperado (nextRunAt), nunca de um tempo fixo.
        private const val WATCHDOG_GRACE_MS = 30_000L

        // Recuperacao que passa disso esta empacada (algum passo da faxina nao
        // voltou): desiste dela e recomeca a passada do zero.
        private const val RECOVER_STALL_MS = 60_000L
    }

    private var dead = false

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    // ---------- bolha flutuante ----------
    private var bubbleRoot: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleLabel: TextView? = null
    private var bubblePlay: TextView? = null
    private var bubbleChooser: LinearLayout? = null
    private var chooserOpen = false

    // ---------- gravacao ----------
    private var recordRoot: FrameLayout? = null
    private var recordView: RecordOverlayView? = null
    private var recordParams: WindowManager.LayoutParams? = null
    private var panelRoot: LinearLayout? = null
    private var panelCounter: TextView? = null
    private var draft: Macro? = null
    private var passThrough = true
    private var injecting = false
    // quando o último passo entrou no draft: o delay do passo 🏠 vem daqui
    // (os passos de toque calculam o próprio delay dentro da RecordOverlayView)
    private var lastStepUptime = 0L
    var recording = false
        private set

    // ---------- execucao ----------
    var playing = false
        private set
    private var paused = false
    private var current: Macro? = null
    private var stepIndex = 0
    private var loopDone = 0
    private var failures = 0

    // ---------- modo intervalo (escolhido na hora do play, nao fica no macro) ----------
    private var intervalMs = 0L            // 0 = modo normal (loop imediato)
    private var waitingGap = false         // esta na espera entre passadas?
    private var waitDeadline = 0L          // uptimeMillis da proxima passada
    private var waitRemainingOnPause = 0L  // restante congelado pela pausa
    private var quietEnabled = false       // modo Especial: dorme das 21h às 9h
    private var quietSleeping = false      // está dormindo na janela agora?

    // ---------- guarda de rota (v1.9) ----------
    private var guardWaitedMs = 0L         // ha quanto tempo espera o app certo
    private var offRouteStreak = 0         // saidas de rota seguidas (zera ao completar)
    private var recovering = false         // esta no meio da faxina/volta pro inicio
    private var recoveries = 0             // quantas vezes se recuperou nesta execucao
    private var waitIsRetry = false        // a espera atual e pos-recuperacao?
    private var unblockTries = 0           // cliques no "Continuar para o chat" neste passo

    // ---------- vigia (watchdog) ----------
    private var nextRunAt = 0L             // uptime em que o proximo tick devia disparar
    private var recoverStartUptime = 0L    // quando a recuperacao atual comecou
    private var dispatching = false        // um gesto esta em voo (nao cutucar)
    private var recoverGen = 0             // geracao da recuperacao (mata callback velho)

    override fun onServiceConnected() {
        super.onServiceConnected()
        dead = false
        live = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "onServiceConnected")
        // Blindagem: qualquer tropeço aqui derrubava o serviço inteiro, e o
        // Android marcava "Este serviço está com problemas" sem dizer por quê.
        // Registrado como conectado antes de desenhar, então mesmo que a bolha
        // falhe o app já consegue gravar e tocar.
        // O vigia e a retomada vem PRIMEIRO e cada um no seu try: sao eles que
        // seguram o macro de pe num aparelho deixado dias sozinho, entao nao
        // podem depender da bolha (que e a parte que as vezes tropeca).
        try {
            startWatchdog()
            resumeIfWasRunning()
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao armar vigia/retomada", t)
        }
        // Sincronizacao com o servidor (macros, config, atualizacao do app).
        // Separado de proposito: sem rede o macro tem que rodar do mesmo jeito.
        try {
            Sync.onServiceConnected(this)
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao ligar a sincronizacao", t)
        }
        try {
            Store.seedDefaultIfNeeded(this)
            showBubbleNow()
            rescueDraft()
            toast("AutoClick ligado")
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao preparar a bolha", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // nao usamos o conteudo da tela pra nada
    }

    override fun onInterrupt() {
        // Quem mandou parar foi o sistema, nao o Gabriel: guarda o estado pra
        // retomar quando o servico voltar.
        stopPlayback(null, userAsked = false)
    }

    /**
     * Freio pelo volume: DESLIGADO na v1.1. Pedir filtro de teclas
     * (canRequestFilterKeyEvents) é o suspeito de o serviço não subir no Redmi,
     * então o pedido saiu do accessibility_service_config.xml. Sem o pedido este
     * callback nunca é chamado — o freio é o ■ da bolha. Se um dia o log provar
     * que o problema era outro, é só devolver a flag no XML.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = false

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        retire()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        retire()
        super.onDestroy()
    }

    /** Marca esta instância como morta sem derrubar uma nova que já tenha assumido. */
    private fun retire() {
        dead = true
        cleanup()
        if (live === this) live = null
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        playing = false
        recording = false
        removeView(recordRoot)
        recordRoot = null
        recordView = null
        panelRoot = null
        removeView(bubbleRoot)
        bubbleRoot = null
    }

    // =====================================================================
    // BOLHA
    // =====================================================================

    fun showBubbleNow() {
        if (bubbleRoot != null) {
            updateBubble()
            return
        }
        val manager = wm ?: return

        // Linha 1 = controles; linha 2 = seletor de intervalo (escondido). Tudo na
        // MESMA janela: segunda janela de overlay ja deu briga de ordem na v1.0.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(0xE6101828.toInt(), 22)
            setPadding(dp(6), dp(4), dp(10), dp(4))
        }
        val rowMain = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val grip = glyph("⠿", 20) {}
        val play = glyph("▶", 22) { onPlayPressed() }
        val stop = glyph("■", 22) {
            if (recording) cancelRecording() else stopPlayback("Parado")
        }
        val close = glyph("✕", 16) { hideBubble() }
        // O texto tambem para o loop: aumenta a area de toque do freio.
        val label = TextView(this).apply {
            setTextColor(0xFFBFD3F2.toInt())
            textSize = 12f
            setPadding(dp(6), dp(8), dp(6), dp(8))
            isClickable = true
            setOnClickListener {
                if (playing) stopPlayback("Parado") else if (recording) saveRecording()
            }
        }

        rowMain.addView(grip)
        rowMain.addView(play)
        rowMain.addView(stop)
        rowMain.addView(label)
        rowMain.addView(close)

        val chooser = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        // gapMs em milissegundos, nao em minutos: o "30 s" de teste nao caberia
        // numa conta de minutos inteiros.
        fun option(text: String, gapMs: Long, color: Int) = glyph(text, 13f) {
            chooserOpen = false
            updateBubble()
            val m = Store.selected(this) ?: return@glyph toast("Grave um macro primeiro")
            // Vai pra tela inicial ANTES de começar: senão, se a bolha for
            // apertada com o Chrome (ou outro app) na frente, o passo 1 aprende
            // esse app em vez da tela inicial e o macro trava fora de rota.
            goHomeNow()
            start(m, 3000, gapMs)
        }.apply { setTextColor(color) }
        chooser.addView(option("já", 0L, 0xFF7CD67C.toInt()))
        // 30 s: opção curta pra testar sem esperar 5 minutos por passada
        chooser.addView(option("30 s", 30_000L, 0xFF8FD3FF.toInt()))
        chooser.addView(option("5 min", 5 * 60_000L, Color.WHITE))
        chooser.addView(option("10 min", 10 * 60_000L, Color.WHITE))
        // Especial: 5 min, mas dorme das 21h às 9h
        chooser.addView(glyph("Especial", 13f) {
            chooserOpen = false
            updateBubble()
            val m = Store.selected(this) ?: return@glyph toast("Grave um macro primeiro")
            goHomeNow()
            start(m, 3000, 5 * 60_000L, quietWindow = true)
        }.apply { setTextColor(0xFFFFC107.toInt()) })
        chooser.addView(glyph("✕", 13f) {
            chooserOpen = false
            updateBubble()
        }.apply { setTextColor(0xFF8FA3C0.toInt()) })

        root.addView(rowMain)
        root.addView(chooser)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        grip.setOnTouchListener(dragger(p) { manager.updateViewLayout(root, p) })

        if (!addOverlay(root, p, "bolha")) {
            toast("Não consegui desenhar a bolha. Desligue e ligue o AutoClick em Acessibilidade.")
            return
        }
        bubbleRoot = root
        bubbleParams = p
        bubbleLabel = label
        bubblePlay = play
        bubbleChooser = chooser
        updateBubble()
    }

    private fun hideBubble() {
        removeView(bubbleRoot)
        bubbleRoot = null
        bubbleParams = null
        bubbleLabel = null
        bubblePlay = null
        bubbleChooser = null
        chooserOpen = false
    }

    private fun onPlayPressed() {
        if (recording) {
            saveRecording()
            return
        }
        if (playing) {
            paused = !paused
            if (paused) {
                if (waitingGap) {
                    // congela o tempo que falta pra proxima passada
                    waitRemainingOnPause =
                        max(0L, waitDeadline - SystemClock.uptimeMillis())
                    handler.removeCallbacks(waitTicker)
                }
            } else {
                if (waitingGap) {
                    // No sono do modo Especial o alvo e um HORARIO (9h), nao um
                    // tempo restante: recalcula pela janela, senao pausar de noite
                    // e retomar de manha mandaria dormir mais um dia inteiro.
                    waitDeadline = if (quietSleeping) {
                        SystemClock.uptimeMillis() + msUntilQuietEnd()
                    } else {
                        SystemClock.uptimeMillis() + waitRemainingOnPause
                    }
                    handler.post(waitTicker)
                } else {
                    scheduleRunner(0L)
                }
            }
            updateBubble()
            return
        }
        if (Store.selected(this) == null) {
            toast("Grave um macro primeiro")
            return
        }
        // parado: o ▶ abre o seletor (ja / 5 / 10 / 30 min)
        chooserOpen = !chooserOpen
        updateBubble()
    }

    private fun updateBubble() {
        val label = bubbleLabel ?: return
        val m = current ?: Store.selected(this)
        val name = m?.name ?: "nenhum macro"
        // quantas vezes se recuperou nesta execucao, pra dar sinal de vida sem
        // precisar do log: se esse numero so cresce, alguma coisa esta errada
        val resets = if (recoveries > 0) " · ${recoveries} resets" else ""
        label.text = when {
            recording -> "gravando: ${draft?.steps?.size ?: 0} passos"
            recovering -> "fora da rota · limpando e voltando pro início"
            playing && paused && waitingGap -> "pausado · toque pra parar"
            playing && paused -> "pausado · toque pra parar"
            playing && quietSleeping ->
                "dormindo até ${Store.quietEnd(this)}h · feito ${loopDone}x$resets · toque pra PARAR"
            playing && waitingGap && waitIsRetry ->
                "recuperado · recomeça em ${mmss(waitDeadline - SystemClock.uptimeMillis())}"
            playing && waitingGap ->
                "feito ${loopDone}x$resets · próxima em ${mmss(waitDeadline - SystemClock.uptimeMillis())}"
            playing && guardWaitedMs > 0 -> "esperando a tela certa abrir…"
            playing -> {
                val total = m?.let { if (it.loops <= 0) "∞" else it.loops.toString() } ?: "?"
                "${loopDone + 1}/$total · toque pra PARAR"
            }
            chooserOpen -> "rodar já ou a cada quanto?"
            else -> name
        }

        if (playing || recording) chooserOpen = false
        bubbleChooser?.visibility = if (chooserOpen) View.VISIBLE else View.GONE
        bubblePlay?.text = if (playing && !paused) "⏸" else "▶"
        bubblePlay?.setTextColor(if (playing && !paused) 0xFFFFC107.toInt() else 0xFF7CD67C.toInt())

        // tela nao pode apagar durante o loop
        val p = bubbleParams ?: return
        val on = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val wanted = if (playing && !paused) p.flags or on else p.flags and on.inv()
        if (wanted != p.flags) {
            p.flags = wanted
            try {
                wm?.updateViewLayout(bubbleRoot, p)
            } catch (_: Throwable) {
            }
        }
    }

    // =====================================================================
    // GRAVACAO
    // =====================================================================

    fun startRecording(name: String, passThroughTouches: Boolean) {
        Log.i(TAG, "startRecording name=$name pass=$passThroughTouches recording=$recording wm=${wm != null}")
        if (recording) return
        if (playing) stopPlayback(null)
        // gravar e uma escolha de parar de rodar: apaga o estado, senao o vigia
        // ressuscitaria o macro antigo assim que a gravacao terminasse
        Store.clearRunState(this)
        if (wm == null) return

        passThrough = passThroughTouches
        draft = Macro(name)
        recording = true
        lastStepUptime = SystemClock.uptimeMillis()
        Store.saveDraft(this, draft)

        // O painel REC entra como FILHO da camada, nao como janela separada:
        // ao recolocar a camada depois de reinjetar um toque, ela voltava por cima
        // do painel e engolia o botao de salvar.
        val root = FrameLayout(this)
        val canvas = RecordOverlayView(this)
        canvas.onStep = { step -> onStepRecorded(step) }
        root.addView(
            canvas,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val panel = buildPanel()
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
                topMargin = dp(48)
            }
        )

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (!addOverlay(root, p, "camada de gravacao")) {
            recording = false
            draft = null
            Store.saveDraft(this, null)
            toast("Não consegui abrir a gravação. Desligue e ligue o AutoClick em Acessibilidade.")
            return
        }
        recordRoot = root
        recordView = canvas
        recordParams = p
        panelRoot = panel
        updatePanel()
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xF2B3261E.toInt(), 20)
            setPadding(dp(6), dp(4), dp(8), dp(4))
            isClickable = true // engole toques na propria area, nao viram passo
        }
        val grip = glyph("⠿", 18) {}
        val counter = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(4), 0, dp(6), 0)
        }
        panel.addView(grip)
        panel.addView(counter)
        panel.addView(glyph("🏠", 16) { recordHomeStep() })
        panel.addView(glyph("🔄", 16) { recordRefreshStep() })
        panel.addView(glyph("↩", 20) { undoStep() })
        panel.addView(glyph("✓", 22) { saveRecording() })
        panel.addView(glyph("✕", 18) { cancelRecording() })
        panelCounter = counter

        var startX = 0f
        var startY = 0f
        var originX = 0f
        var originY = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    originX = panel.translationX
                    originY = panel.translationY
                }

                MotionEvent.ACTION_MOVE -> {
                    panel.translationX = originX + (e.rawX - startX)
                    panel.translationY = originY + (e.rawY - startY)
                }
            }
            true
        }
        return panel
    }

    private fun updatePanel() {
        panelCounter?.text = "REC ${draft?.steps?.size ?: 0}"
        updateBubble()
    }

    /**
     * Retoma o macro que estava rodando quando o servico caiu. Sem isto, o
     * HyperOS matando o app (ou um reinicio do celular) deixava tudo com CARA
     * de normal — servico ligado, bolha na tela — mas parado desde a madrugada,
     * e so dava pra perceber olhando o log. Era a pior falha do app.
     *
     * A espera existe porque logo depois do boot o launcher ainda esta se
     * montando, e a passada 1 comecaria na tela errada.
     */
    private var resumeScheduled = false

    private fun resumeIfWasRunning() = scheduleResume(RESUME_DELAY_MS)

    /**
     * Retoma o macro que estava rodando quando o servico caiu. Sem isto, o
     * HyperOS matando o app (ou um reinicio do celular, ou um onInterrupt do
     * sistema) deixava tudo com CARA de normal — servico ligado, bolha na tela
     * — mas parado desde a madrugada, e so dava pra perceber olhando o log. Era
     * a pior falha do app.
     *
     * So um PARAR de verdade (bolha ■, comando, fim das repeticoes) apaga o
     * estado gravado; entao, se ele existe, e porque o macro devia estar vivo.
     * delayMs da folga pro launcher se montar (essencial logo apos o boot).
     */
    private fun scheduleResume(delayMs: Long) {
        if (playing || recording || resumeScheduled) return
        val st = Store.loadRunState(this) ?: return
        val m = Store.byId(this, st.macroId) ?: run {
            Store.clearRunState(this) // macro excluido: nao ha o que retomar
            return
        }
        resumeScheduled = true
        Log.i(TAG, "vou retomar \"${m.name}\" em ${delayMs / 1000}s (intervalo=${st.gapMs / 1000}s especial=${st.quiet})")
        handler.postDelayed({
            resumeScheduled = false
            // RECONFERE o disco: se o Gabriel tocou ■ dentro desses segundos, o
            // estado foi apagado e NAO e pra ressuscitar. Sem isto, um parar
            // feito na janela de espera era desfeito sozinho.
            if (playing || recording) return@postDelayed
            val fresh = Store.loadRunState(this) ?: return@postDelayed
            val m2 = Store.byId(this, fresh.macroId) ?: return@postDelayed
            start(m2, 3_000, fresh.gapMs, fresh.quiet)
        }, delayMs)
    }

    /** Se o servico morreu no meio de uma gravacao, salva o que deu tempo. */
    private fun rescueDraft() {
        val d = Store.loadDraft(this) ?: return
        Store.saveDraft(this, null)
        if (d.steps.isEmpty()) return
        d.steps[0].delayBeforeMs = 0
        d.name = "${d.name} (recuperado)"
        Store.add(this, d)
        Store.setSelected(this, d.id)
        Log.i(TAG, "gravacao recuperada com ${d.steps.size} passos")
        toast("A gravação foi interrompida. Salvei \"${d.name}\" com ${d.steps.size} passos.")
    }

    private fun onStepRecorded(step: Step) {
        // Anota em qual app o toque aconteceu: e o que a guarda de rota compara
        // na reproducao. A nossa camada de gravacao e overlay de acessibilidade,
        // entao o que aparece aqui e o app de baixo, que e o que interessa.
        step.app = foregroundPackage()
        draft?.steps?.add(step)
        lastStepUptime = SystemClock.uptimeMillis()
        Store.saveDraft(this, draft)
        updatePanel()
        if (passThrough) replayForPassThrough(step)
    }

    /**
     * Botão 🏠 do painel: grava um passo "tela inicial" e JÁ vai pra ela, pra
     * gravação continuar de lá. É performGlobalAction, não gesto: arrasto de
     * baixo pra cima injetado não vira navegação — cai no app da frente (no
     * WhatsApp puxava o teclado e saía digitando).
     */
    private fun recordHomeStep() {
        val d = draft ?: return
        val now = SystemClock.uptimeMillis()
        val delay = (now - lastStepUptime).coerceIn(0L, 120_000L)
        d.steps.add(Step(Step.HOME, emptyList(), 0L, delay, foregroundPackage()))
        lastStepUptime = now
        Store.saveDraft(this, d)
        updatePanel()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Botão 🔄 do painel: grava um passo "atualizar página" e JÁ atualiza o
     * Chrome, pro resto da gravação acontecer na página recém-carregada.
     */
    private fun recordRefreshStep() {
        val d = draft ?: return
        val now = SystemClock.uptimeMillis()
        val delay = (now - lastStepUptime).coerceIn(0L, 120_000L)
        d.steps.add(Step(Step.REFRESH, emptyList(), 0L, delay, foregroundPackage()))
        lastStepUptime = now
        Store.saveDraft(this, d)
        updatePanel()
        // Falhou? O próprio refreshPage já explica o motivo num aviso; o passo
        // fica gravado do mesmo jeito e o ↩ desfaz.
        refreshPage { }
    }

    /**
     * Tira a camada do ar, reinjeta o gesto no app de baixo e recoloca a camada.
     * Trocar a flag NOT_TOUCHABLE nao serve: o evento injetado chega antes da flag
     * valer e o mesmo toque acaba gravado duas vezes.
     */
    private fun replayForPassThrough(step: Step) {
        val p = recordParams ?: return
        val root = recordRoot ?: return
        val manager = wm ?: return
        if (injecting) return

        injecting = true
        try {
            manager.removeViewImmediate(root)
        } catch (t: Throwable) {
            Log.e(TAG, "nao consegui tirar a camada pra reinjetar", t)
            injecting = false
            return
        }

        // O sistema leva alguns milissegundos pra tirar a janela da lista do
        // dispatcher de input. Injetar antes disso perde o toque.
        handler.postDelayed({
            dispatchStep(step, 1f) { ok ->
                Log.i(TAG, "repasse ${if (ok) "ok" else "recusado"}")
                handler.post {
                    injecting = false
                    if (!recording || recordRoot !== root) return@post
                    if (!addOverlay(root, p, "camada de gravacao")) {
                        toast("A camada de gravação caiu, salvei o que você já gravou.")
                        recordRoot = null
                        saveRecording()
                    }
                }
            }
        }, 150)
    }

    private fun undoStep() {
        val d = draft ?: return
        val removed = if (d.steps.isNotEmpty()) d.steps.removeAt(d.steps.size - 1) else null
        Store.saveDraft(this, d)
        // passo 🏠 não tem marca desenhada: desfazer a marca de um toque
        // anterior dessincronizaria o desenho da gravação
        if (removed != null && removed.pts.isNotEmpty()) recordView?.undo()
        updatePanel()
    }

    private fun saveRecording() {
        val d = draft
        stopRecording()
        Store.saveDraft(this, null)
        if (d == null || d.steps.isEmpty()) {
            toast("Nada foi gravado")
            return
        }
        d.steps[0].delayBeforeMs = 0
        Store.add(this, d)
        Store.setSelected(this, d.id)
        current = d
        updateBubble()
        toast("\"${d.name}\" salvo: ${d.steps.size} passos. Toque ▶ na bolha pra rodar.")
    }

    private fun cancelRecording() {
        stopRecording()
        Store.saveDraft(this, null)
        toast("Gravação descartada")
    }

    private fun stopRecording() {
        recording = false
        injecting = false
        draft = null
        removeView(recordRoot)
        recordRoot = null
        recordView = null
        panelRoot = null
        recordParams = null
        panelCounter = null
        showBubbleNow()
        updateBubble()
    }

    // =====================================================================
    // EXECUCAO EM LOOP
    // =====================================================================

    /**
     * intervalBetweenRunsMs > 0 = modo intervalo: cada ciclo roda os passos UMA vez,
     * espera esse tempo e repete pra sempre (as "Repetições" do macro são ignoradas,
     * senão um macro com repetição infinita nunca chegaria na espera).
     */
    fun start(
        macro: Macro,
        delayMs: Long,
        intervalBetweenRunsMs: Long = 0L,
        quietWindow: Boolean = false
    ) {
        if (recording) stopRecording()
        if (macro.steps.isEmpty()) {
            toast("Esse macro esta vazio")
            return
        }
        current = macro
        stepIndex = 0
        loopDone = 0
        failures = 0
        playing = true
        paused = false
        chooserOpen = false
        intervalMs = intervalBetweenRunsMs
        waitingGap = false
        waitRemainingOnPause = 0L
        quietEnabled = quietWindow
        quietSleeping = false
        guardWaitedMs = 0L
        offRouteStreak = 0
        recovering = false
        // uma faxina em voo da execucao anterior nao pode mexer nesta
        recoverGen++
        recoveries = 0
        waitIsRetry = false
        unblockTries = 0
        // grava pra conseguir retomar se o sistema derrubar o servico
        Store.saveRunState(this, macro.id, intervalBetweenRunsMs, quietWindow)
        Log.i(TAG, "start macro=${macro.name} passos=${macro.steps.size} loops=${macro.loops} fixo=${macro.fixedDelayMs} delay=$delayMs intervalo=${intervalBetweenRunsMs / 1000}s especial=$quietWindow")
        showBubbleNow()
        updateBubble()
        handler.removeCallbacks(runner)
        handler.removeCallbacks(waitTicker)
        scheduleRunner(max(delayMs, 50L))
        startWatchdog()
        if (quietWindow) {
            toast(
                "Modo Especial: ${gapLabel(intervalBetweenRunsMs)} (varia) e dorme das " +
                    "${Store.quietStart(this)}h às ${Store.quietEnd(this)}h. ■ para."
            )
        } else if (intervalBetweenRunsMs > 0) {
            toast("Vai rodar 1x e repetir perto de ${gapLabel(intervalBetweenRunsMs)}. ■ para.")
        }
    }

    /**
     * Janela de silêncio do modo Especial (hora local). Padrão 21h até 9h; o
     * config.json do servidor pode mudar (quietHours). Início igual ao fim =
     * sem janela.
     */
    private fun inQuietHours(): Boolean {
        if (!quietEnabled) return false
        val start = Store.quietStart(this)
        val end = Store.quietEnd(this)
        if (start == end) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start > end) (h >= start || h < end) else (h >= start && h < end)
    }

    /** Milissegundos daqui até o fim da janela de silêncio (hoje ou amanhã). */
    private fun msUntilQuietEnd(): Long {
        val now = java.util.Calendar.getInstance()
        val end = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, Store.quietEnd(this@ClickerService))
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (end.timeInMillis <= now.timeInMillis) {
            end.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return (end.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
    }

    /**
     * userAsked=false quando quem mandou parar foi o SISTEMA (onInterrupt). Nesse
     * caso o estado gravado fica de pe, pra retomar quando o servico voltar; so
     * um parar de verdade (bolha ■, comando, fim das repeticoes) e definitivo.
     */
    fun stopPlayback(message: String?, userAsked: Boolean = true) {
        if (playing) Log.i(TAG, "parando: ${message ?: "sem aviso"} (loops feitos=$loopDone, pedido=$userAsked)")
        if (userAsked) Store.clearRunState(this)
        handler.removeCallbacks(runner)
        handler.removeCallbacks(waitTicker)
        playing = false
        paused = false
        stepIndex = 0
        intervalMs = 0L
        waitingGap = false
        waitRemainingOnPause = 0L
        quietEnabled = false
        quietSleeping = false
        guardWaitedMs = 0L
        offRouteStreak = 0
        recovering = false
        waitIsRetry = false
        updateBubble()
        if (message != null) toast(message)
    }

    private val runner = Runnable { tick() }

    /**
     * Agenda o proximo tick E anota quando ele DEVIA disparar. O vigia usa esse
     * horario pra saber se o laco morreu: se passou muito do previsto e nada
     * rodou, ele ressuscita. Todo agendamento do laco passa por aqui.
     */
    private fun scheduleRunner(delayMs: Long) {
        nextRunAt = SystemClock.uptimeMillis() + delayMs
        handler.removeCallbacks(runner)
        handler.postDelayed(runner, delayMs)
    }

    // ---------- vigia (watchdog): o laco pode morrer sem derrubar o servico ----------
    private val watchdog = Runnable { watchTick() }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /**
     * Confere de tempos em tempos se a execucao esta viva. Um tick que estoura
     * uma excecao, ou uma recuperacao que empaca, deixaria o macro parado com
     * CARA de rodando (servico ligado, bolha na tela) — a falha mais traicoeira
     * possivel num aparelho deixado 3 dias sozinho. Aqui ele conserta.
     */
    private fun watchTick() {
        try {
            if (dead) return
            val now = SystemClock.uptimeMillis()
            when {
                recording -> {
                    // gravando: nao e hora de rodar nem retomar
                }
                paused -> {
                    // o Gabriel pausou de proposito: respeita
                }
                !playing -> {
                    // parado mas o estado gravado diz que devia rodar (ex:
                    // onInterrupt do sistema sem reconexao): retoma sozinho
                    scheduleResume(3_000L)
                }
                // bolha sumiu (addOverlay falhou) mas o macro segue: redesenha
                bubbleRoot == null -> {
                    Log.i(TAG, "vigia: bolha sumiu com o macro rodando, redesenhando")
                    showBubbleNow()
                }
                recovering -> {
                    if (now - recoverStartUptime > RECOVER_STALL_MS) {
                        Log.i(TAG, "vigia: recuperacao empacou, recomecando a passada")
                        recoverGen++ // invalida o callback da faxina em voo
                        recovering = false
                        stepIndex = 0
                        scheduleRunner(2_000L)
                    }
                }
                waitingGap -> {
                    // a espera devia ter acabado ha muito e o waitTicker sumiu
                    if (now - waitDeadline > WATCHDOG_GRACE_MS) {
                        Log.i(TAG, "vigia: espera travada, rearmando")
                        handler.removeCallbacks(waitTicker)
                        handler.post(waitTicker)
                    }
                }
                // um gesto em voo nao e laco morto: espera ele voltar
                dispatching -> {
                    // nada a fazer
                }
                // caso principal: um tick devia ter disparado e nao disparou
                nextRunAt > 0 && now - nextRunAt > WATCHDOG_GRACE_MS -> {
                    Log.i(TAG, "vigia: laco parado (${(now - nextRunAt) / 1000}s de atraso), ressuscitando")
                    scheduleRunner(500L)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "vigia tropecou", t)
        } finally {
            // o vigia NUNCA para enquanto o servico vive: reagenda sempre
            if (!dead) handler.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    // ---------- espera entre passadas (modo intervalo) ----------

    private val waitTicker = Runnable { waitTick() }

    private fun waitTick() {
        if (!playing || paused || !waitingGap) return
        val left = waitDeadline - SystemClock.uptimeMillis()
        if (left <= 0) {
            waitingGap = false
            Log.i(TAG, "espera acabou, passada ${loopDone + 1}")
            updateBubble()
            scheduleRunner(0L)
        } else {
            updateBubble()
            handler.postDelayed(waitTicker, if (left < 1000) left else 1000)
        }
    }

    /** "30 s" ou "5 min": o intervalo curto de teste nao cabe em minutos inteiros. */
    private fun gapLabel(ms: Long): String =
        if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"

    private fun mmss(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * Sorteia a espera dentro de ±1 minuto do intervalo escolhido, pra não cair
     * sempre no mesmo tempo redondo. Ex: 5 min vira algo entre 4:00 e 6:00 a cada
     * ciclo (4:13, 5:30, ...). Resolução de 1s. Nunca deixa menor que 1s.
     */
    private fun randomizedGap(baseMs: Long): Long {
        // 1 min de variacao nos intervalos de minutos. Num intervalo curto (o
        // "30 s" de teste) isso sortearia de 1 a 90 s, entao ali a variacao cai
        // pra metade da base: 30 s viram algo entre 15 e 45 s.
        val jitter = min(60_000L, baseMs / 2)
        val low = (baseMs - jitter).coerceAtLeast(1_000L)
        val high = baseMs + jitter
        if (high <= low) return baseMs
        // sorteia em passos de 1 segundo dentro de [low, high]
        val steps = ((high - low) / 1000L).toInt() + 1
        return low + Random.nextInt(steps) * 1000L
    }

    private fun tick() {
        if (!playing || paused) return
        val m = current ?: run {
            // userAsked=false: e um erro interno transitorio, nao um parar do
            // Gabriel — preserva o estado pro auto-resume nao ser perdido
            stopPlayback(null, userAsked = false)
            return
        }

        // Modo Especial: antes de começar uma passada, se estiver na janela
        // 21h-9h, dorme até as 9h em vez de rodar. Como toda passada começa aqui
        // com stepIndex==0, isso cobre tanto a 1ª passada quanto as seguintes.
        if (quietEnabled && stepIndex == 0 && !waitingGap && inQuietHours()) {
            waitingGap = true
            waitIsRetry = false
            quietSleeping = true
            val until = msUntilQuietEnd()
            waitDeadline = SystemClock.uptimeMillis() + until
            Log.i(TAG, "Especial: dormindo ${until / 1000}s até as 9h")
            updateBubble()
            handler.removeCallbacks(waitTicker)
            handler.post(waitTicker)
            return
        }
        quietSleeping = false

        if (stepIndex >= m.steps.size) {
            loopDone++
            stepIndex = 0
            // Passada inteira sem sair da rota: zera o contador, senao uma falha
            // isolada de horas atras ainda estaria encurtando as tentativas.
            offRouteStreak = 0
            guardWaitedMs = 0L
            // Modo intervalo: uma passada feita, agenda a proxima e mostra a contagem.
            if (intervalMs > 0) {
                waitingGap = true
                waitIsRetry = false
                val gap = randomizedGap(intervalMs)
                waitDeadline = SystemClock.uptimeMillis() + gap
                Log.i(TAG, "passada $loopDone concluída, próxima em ${gap / 1000}s (base ${intervalMs / 1000}s)")
                updateBubble()
                handler.removeCallbacks(waitTicker)
                handler.post(waitTicker)
                return
            }
            updateBubble()
            if (m.loops in 1..loopDone) {
                stopPlayback("Fim: ${loopDone}x")
                return
            }
            // "Forçar intervalo" tambem vale entre uma repeticao e outra, senao um macro
            // de 1 toque ignoraria o campo e o rotulo estaria mentindo.
            val gap = if (m.fixedDelayMs > 0) m.fixedDelayMs else m.loopDelayMs
            scheduleRunner(scaled(gap, m.speed))
            return
        }

        val step = m.steps[stepIndex]

        // ---- guarda de rota: so toca se estiver no app certo ----
        // Sem isso, uma tela inesperada (chip banido, popup, aba que o wa.me
        // abriu no Chrome) faz o toque cair por coordenada no lugar errado e a
        // bagunca so cresce a cada passada.
        if (m.autoRecover && !recovering) {
            val front = foregroundPackage()

            // ---- 0) popup do proprio Chrome cobrindo a pagina? dispensa e espera.
            // Vem antes de tudo pra nunca APRENDER uma ancora (nem tocar) com um
            // dialogo por cima — foi o que corrompeu a rota no 2o Redmi.
            if (dismissBrowserPromo(front)) {
                scheduleRunner(GUARD_POLL_MS)
                return
            }

            // ---- 0b) tela intermediaria no caminho ate a conversa (wa.me
            // "Continuar para o chat" ou WhatsApp "Iniciar conversa")? Avanca ANTES
            // de aprender/tocar, senao a coordenada do passo cai em cima do dialogo
            // (aprendeu "secondary_button" = Cancelar e clicou nele no 2o Redmi).
            if (advanceIntermediary(front)) {
                scheduleRunner(GUARD_POLL_MS)
                return
            }

            // ---- 1) o app da frente e o mesmo de quando o passo foi gravado?
            if (step.app.isBlank()) {
                // Macro gravado antes da v1.9 (ou passo novo): aprende a rota na
                // primeira passada em vez de exigir regravacao.
                if (front.isNotBlank()) {
                    step.app = front
                    Store.update(this, m)
                    Log.i(TAG, "rota aprendida: passo ${stepIndex + 1} app=$front")
                }
            } else if (front.isNotBlank() && front != step.app) {
                // (telas intermediarias ja tratadas no passo 0b)
                if (keepWaiting("app: esperava ${step.app}, achei $front")) return
                onOffRoute(step, front)
                return
            }

            // ---- 2) o elemento que o toque acerta ainda esta na tela?
            // Esta e a checagem que pega o que a de app nao pega: com o chip
            // banido o WhatsApp ABRE (o app bate), mas a tela nao tem o botao de
            // enviar, e sem isso o macro tocaria no vazio.
            if (step.pts.isNotEmpty()) {
                if (step.anchor.isBlank()) {
                    val id = nodeIdAt(step.pts[0].x, step.pts[0].y)
                    if (id.isNotBlank()) {
                        step.anchor = id
                        Store.update(this, m)
                        Log.i(TAG, "rota aprendida: passo ${stepIndex + 1} alvo=$id")
                    } else if (front.contains("whatsapp", true)) {
                        // No WhatsApp TODO botao tem id. Nada embaixo do toque =
                        // a conversa ainda nao abriu, ou tem um dialogo por cima
                        // que nao reconhecemos. Tocar no escuro aqui "completa"
                        // a passada sem enviar nada (visto em 02/09): espera, e
                        // se nao aparecer, recupera. O log mostra o que havia na
                        // tela pra gente aprender o dialogo novo.
                        if (keepWaiting("whatsapp sem alvo embaixo do toque")) return
                        logVisibleNodes("whatsapp/sem-alvo")
                        onOffRoute(step, "$front sem alvo embaixo do toque")
                        return
                    }
                } else if (!hasNodeWithId(step.anchor)) {
                    // (a tela intermediaria "Iniciar conversa" ja foi tratada no
                    // passo 0b; se chegou aqui, o alvo sumiu de verdade)
                    if (keepWaiting("alvo \"${step.anchor}\" nao esta na tela")) return
                    onOffRoute(step, "$front sem \"${step.anchor}\"")
                    return
                }
            }

            guardWaitedMs = 0L
            unblockTries = 0
        }

        Log.i(TAG, "executando loop ${loopDone + 1} passo ${stepIndex + 1}/${m.steps.size}")
        // enquanto o gesto nao volta, o vigia nao deve achar que o laco morreu
        dispatching = true
        dispatchStep(step, m.speed) { ok ->
            dispatching = false
            Log.i(TAG, "gesto ${if (ok) "ok" else "recusado"}")
            if (!playing) return@dispatchStep
            if (ok) {
                failures = 0
                stepIndex++
            } else {
                failures++
                if (failures >= 3) {
                    // ANTES isto encerrava de vez, e ninguem religava: bastavam
                    // 3 recusas (tela apagando, sistema ocupado) pra o macro
                    // morrer calado no meio da madrugada. Agora e so mais um
                    // caso de recuperacao, com o mesmo freio dos outros.
                    Log.i(TAG, "3 gestos recusados seguidos; tratando como fora de rota")
                    failures = 0
                    onOffRoute(m.steps[stepIndex], "gestos recusados")
                    return@dispatchStep
                }
            }
            val next = if (stepIndex < m.steps.size) {
                val raw = if (m.fixedDelayMs > 0) m.fixedDelayMs else m.steps[stepIndex].delayBeforeMs
                scaled(raw, m.speed)
            } else {
                0L
            }
            scheduleRunner(max(next, 20L))
        }
    }

    private fun scaled(ms: Long, speed: Float): Long =
        if (speed <= 0.05f) ms else (ms / speed).toLong()

    /** Monta o Path e manda o gesto pro sistema. */
    private fun dispatchStep(step: Step, speed: Float, done: (Boolean) -> Unit) {
        // Passo "tela inicial": ação do sistema, sem gesto. Precisa vir antes
        // do guard de pts vazio (ele não tem ponto nenhum).
        if (step.type == Step.HOME) {
            done(performGlobalAction(GLOBAL_ACTION_HOME))
            return
        }
        // Passo "atualizar página": também sem gesto, mesmo motivo.
        if (step.type == Step.REFRESH) {
            refreshPage(done)
            return
        }

        val size = displaySize()
        val pts = step.pts.map {
            Pt(
                it.x.coerceIn(0f, size.x - 1f),
                it.y.coerceIn(0f, size.y - 1f)
            )
        }
        if (pts.isEmpty()) {
            done(false)
            return
        }

        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) {
            // Path precisa ter comprimento, senao o sistema recusa
            var y2 = pts[0].y + 1f
            if (y2 > size.y - 1f) y2 = pts[0].y - 1f
            path.lineTo(pts[0].x, y2)
        } else {
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        }

        val dur = scaled(step.durationMs, speed).coerceIn(20L, 30_000L)
        var called = false
        fun finish(ok: Boolean) {
            if (!called) {
                called = true
                done(ok)
            }
        }

        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
        } catch (t: Throwable) {
            finish(false)
            return
        }

        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = finish(true)
                override fun onCancelled(g: GestureDescription?) = finish(false)
            }, handler)
        } catch (t: Throwable) {
            false
        }
        if (!accepted) finish(false)
    }

    // =====================================================================
    // ATUALIZAR PAGINA DO NAVEGADOR (F5)
    // =====================================================================

    /**
     * Atualiza a página do navegador que está na frente, como um F5: abre o
     * menu ⋮ e clica no "Atualizar" PELO NÓ de acessibilidade, nunca por
     * coordenada, então funciona com o menu em qualquer posição. Puxar a página
     * pra baixo não serve (só atualiza com a página no topo; no resto vira
     * rolagem) e injetar F5/Ctrl+R não existe pra acessibilidade.
     *
     * v1.7 falhava SEMPRE por dois motivos independentes: faltava a flag
     * flagReportViewIds (sem ela getViewIdResourceName é sempre null e toda
     * busca por id morre) e a descrição procurada era "Mais opções", enquanto o
     * Chrome em português se descreve como "Personalizar e controlar o Google
     * Chrome". Também exigia pacote com "chrome" no nome, o que descartava
     * qualquer outro navegador.
     */
    private fun refreshPage(done: (Boolean) -> Unit) {
        val roots = screenRoots()
        if (roots.isEmpty()) {
            Log.i(TAG, "refresh: nenhuma janela legível")
            toast("Sem acesso à tela: desligue e ligue o AutoClick em Acessibilidade.")
            done(false)
            return
        }
        val app = roots[0].packageName?.toString() ?: "?"

        // Alguns navegadores (modo desktop, tela grande) mostram o Atualizar
        // direto na barra: resolve sem abrir menu nenhum.
        findFirst(roots) { isReloadNode(it, allowLabel = false) }?.let { direct ->
            if (clickNode(direct)) {
                Log.i(TAG, "refresh: atualizei pelo botão da própria barra")
                done(true)
                return
            }
        }

        // Guarda as janelas de ANTES: o "Atualizar" será procurado só nas
        // janelas novas, ou seja, dentro do menu que acabamos de abrir. Sem
        // isso, qualquer coisa escrita "Atualizar" na página roubaria o clique.
        val before = windowIds()
        val menu = findFirst(roots, ::isBrowserMenuButton)
            ?: findFirst(roots, ::isBrowserMenuButtonLoose)
        if (menu == null) {
            Log.i(TAG, "refresh: não achei o menu em $app")
            toast("Não achei o menu do navegador (app: $app)")
            done(false)
            return
        }
        if (!clickNode(menu)) {
            Log.i(TAG, "refresh: o menu não aceitou o clique em $app")
            toast("O menu do navegador não aceitou o clique (app: $app)")
            done(false)
            return
        }

        var tries = 0
        lateinit var poll: Runnable
        poll = Runnable {
            val fresh = screenRoots(exceptWindowIds = before)
            // Menu em janela própria: pode confiar no rótulo. Menu desenhado
            // dentro da mesma janela: só aceita id, pra não pegar a página.
            val inMenu = fresh.isNotEmpty()
            val where = if (inMenu) fresh else screenRoots()
            val reload = findFirst(where) { isReloadNode(it, allowLabel = inMenu) }
            when {
                reload != null -> {
                    val ok = clickNode(reload)
                    Log.i(TAG, "refresh: cliquei no Atualizar (janela nova=$inMenu) ok=$ok")
                    if (!ok) toast("Achei o Atualizar, mas o clique não passou (app: $app)")
                    done(ok)
                }

                ++tries >= 10 -> {
                    Log.i(TAG, "refresh: menu abriu mas não achei o Atualizar em $app")
                    performGlobalAction(GLOBAL_ACTION_BACK) // fecha o menu que abrimos
                    toast("O menu abriu, mas não achei o Atualizar (app: $app)")
                    done(false)
                }

                else -> handler.postDelayed(poll, 150)
            }
        }
        handler.postDelayed(poll, 200)
    }

    /**
     * Botão ⋮ do navegador. Id conhecido é prova direta. Sem id conhecido, exige
     * que o nó TENHA algum id, o que já separa componente do app de conteúdo da
     * página (nó de página web não expõe id), e aí aceita pela descrição.
     */
    private fun isBrowserMenuButton(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
        if (id in MENU_IDS) return true
        if (id.isEmpty()) return false
        val desc = n.contentDescription?.toString()?.trim() ?: return false
        return MENU_LABELS.any { desc.contains(it, true) }
    }

    /**
     * Último recurso, caso os ids não venham: aceita só pelas frases longas que
     * o próprio navegador usa e que nenhum site escreveria num botão.
     */
    private fun isBrowserMenuButtonLoose(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val desc = n.contentDescription?.toString() ?: return false
        return desc.contains("Personalizar e controlar", true) ||
            desc.contains("Customize and control", true) ||
            desc.contains("Mais opções", true) ||
            desc.contains("More options", true)
    }

    /**
     * Botão "Atualizar". Id com reload/refresh vale em qualquer lugar. Rótulo
     * (descrição OU texto, porque cada navegador usa um) só vale dentro do menu
     * recém-aberto, e curto, pra não confundir com conteúdo da página.
     */
    private fun isReloadNode(n: AccessibilityNodeInfo, allowLabel: Boolean): Boolean {
        if (!n.isVisibleToUser) return false
        val id = n.viewIdResourceName ?: ""
        if (id.contains("reload", true) || id.contains("refresh", true)) return true
        if (!allowLabel) return false
        val label = (n.contentDescription ?: n.text ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 24) return false
        return RELOAD_LABELS.any { label.equals(it, true) || label.startsWith("$it ", true) }
    }

    /**
     * Raízes das janelas legíveis, ignorando as nossas próprias camadas (bolha e
     * camada de gravação estão na frente e não interessam).
     */
    private fun screenRoots(exceptWindowIds: Set<Int> = emptySet()): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (w.id in exceptWindowIds) continue
                val r = w.root ?: continue
                if (r.packageName == packageName) continue
                out.add(r)
            }
        } catch (_: Throwable) {
        }
        // rootInActiveWindow é o plano B de quem não devolve a lista de janelas;
        // no modo "só janelas novas" ele não serve, porque pode ser uma antiga.
        if (exceptWindowIds.isEmpty() && out.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName != packageName) out.add(it) }
        }
        return out
    }

    private fun windowIds(): Set<Int> = try {
        windows.map { it.id }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** Busca em largura nas raízes dadas, com teto de nós pra página web gigante. */
    private fun findFirst(
        roots: List<AccessibilityNodeInfo>,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < 1500) {
                val n = queue.removeFirst()
                seen++
                try {
                    if (match(n)) return n
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    /** Clica no nó ou no ancestral clicável mais próximo. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = try {
                n.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        return false
    }

    // =====================================================================
    // GUARDA DE ROTA E RECUPERACAO (v1.9)
    // =====================================================================

    /**
     * Pacote do app que esta na frente agora, ignorando as NOSSAS camadas (a
     * bolha e a camada de gravacao sao TYPE_ACCESSIBILITY_OVERLAY, nao contam).
     * Devolve "" quando nao da pra saber: nesse caso a guarda deixa passar, que
     * travar a execucao por duvida seria pior que o problema que ela resolve.
     */
    private fun foregroundPackage(): String {
        try {
            var fallback = ""
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val pkg = w.root?.packageName?.toString() ?: continue
                if (pkg == packageName) continue
                if (w.isActive || w.isFocused) return pkg
                if (fallback.isEmpty()) fallback = pkg
            }
            if (fallback.isNotEmpty()) return fallback
        } catch (_: Throwable) {
        }
        return try {
            val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
            if (pkg == packageName) "" else pkg
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * So a janela da FRENTE, nao todas. Essencial pras checagens da guarda: o
     * Android mantem a janela anterior viva por tras da nova, e procurar em
     * todas fazia o botao "send" de uma conversa ja coberta ainda ser
     * encontrado — a guarda dava tudo certo com a tela errada na frente
     * (visto no teste de 31/08).
     */
    private fun activeRoots(): List<AccessibilityNodeInfo> {
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (!w.isActive && !w.isFocused) continue
                val r = w.root ?: continue
                if (r.packageName == packageName) continue
                return listOf(r)
            }
        } catch (_: Throwable) {
        }
        return try {
            val r = rootInActiveWindow
            if (r != null && r.packageName != packageName) listOf(r) else emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun isBrowser(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.contains("chrome", true) || pkg.contains("browser", true) ||
            pkg.contains("firefox", true) || pkg.contains("opera", true) ||
            pkg.contains("brave", true) || pkg.contains("edge", true)
    }

    /**
     * Espera mais um pouco antes de dar o alarme: a tela pode so estar
     * carregando. Devolve true enquanto vale a pena esperar, false quando
     * estourou o limite e e hora de tratar como fora de rota.
     */
    private fun keepWaiting(porque: String): Boolean {
        guardWaitedMs += GUARD_POLL_MS
        // No primeiro passo da passada nada esta carregando: se ja nao e a tela
        // esperada, e erro na hora.
        val limite = if (stepIndex == 0) GUARD_TIMEOUT_START_MS else GUARD_TIMEOUT_MS
        if (guardWaitedMs > limite) return false
        if (guardWaitedMs % 3_000L == 0L) {
            Log.i(TAG, "aguardando ${guardWaitedMs}ms no passo ${stepIndex + 1}: $porque")
        }
        updateBubble()
        scheduleRunner(GUARD_POLL_MS)
        return true
    }

    /**
     * Id do menor elemento que cobre o ponto do toque. E a "assinatura" daquilo
     * que o passo acerta: no botao de enviar do WhatsApp da "send". Devolve ""
     * quando nada ali tem id — e o caso de pagina web, que nao expoe id nenhum,
     * e ai o passo simplesmente nao ganha ancora.
     */
    private fun nodeIdAt(x: Float, y: Float): String {
        val px = x.toInt()
        val py = y.toInt()
        val rect = Rect()
        var melhor = ""
        var menorArea = Int.MAX_VALUE
        for (root in activeRoots()) {
            val fila = ArrayDeque<AccessibilityNodeInfo>()
            fila.add(root)
            var vistos = 0
            while (fila.isNotEmpty() && vistos < 1500) {
                val n = fila.removeFirst()
                vistos++
                try {
                    n.getBoundsInScreen(rect)
                    if (rect.contains(px, py)) {
                        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
                        val area = rect.width() * rect.height()
                        // o MENOR que cobre o ponto e o mais especifico: o botao,
                        // nao o painel inteiro que tambem cobre
                        if (id.isNotBlank() && area in 1 until menorArea) {
                            melhor = id
                            menorArea = area
                        }
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        return melhor
    }

    /** Esse elemento esta visivel na tela da FRENTE agora? */
    private fun hasNodeWithId(id: String): Boolean = findFirst(activeRoots()) { n ->
        n.isVisibleToUser && (n.viewIdResourceName ?: "").substringAfterLast('/') == id
    } != null

    /**
     * Telas intermediarias conhecidas no caminho ate a conversa, que so pedem UM
     * toque pra seguir. Duas hoje:
     *  - pagina do wa.me no navegador ("Continuar para o chat"), quando o link
     *    nao abre o WhatsApp direto;
     *  - dialogo do WhatsApp "Você confia nesta pessoa? / Iniciar conversa", que
     *    aparece pra numero que NAO esta nos contatos (o caso do 2o Redmi).
     *
     * So age quando o passo esperava o WhatsApp: clicar "Continuar"/"Iniciar"
     * fora desse caminho seria pior que o problema. Devolve true se clicou.
     */
    private fun advanceIntermediary(front: String): Boolean {
        if (unblockTries >= MAX_UNBLOCK_TRIES) return false
        // Baseado no app DA FRENTE, nao no passo aprendido: num relearn o passo
        // ainda esta em branco quando o dialogo aparece, e o gate por passo nao
        // pegava. Os botoes sao frases especificas (isContinueToChatNode /
        // isStartChatNode), entao so casam nas telas certas mesmo sem o gate.
        val node = when {
            isBrowser(front) -> findFirst(activeRoots(), ::isContinueToChatNode)
            front.contains("whatsapp", true) -> findFirst(activeRoots(), ::isStartChatNode)
            else -> null
        } ?: return false
        val ok = clickNode(node)
        unblockTries++
        Log.i(TAG, "avancei tela intermediaria (tentativa $unblockTries) ok=$ok ${describe(node)}")
        return ok
    }

    /** Botao "Continuar para o chat" da pagina do wa.me. */
    private fun isContinueToChatNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val label = (n.text ?: n.contentDescription ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 40) return false
        // equals com a FRASE inteira, nunca contains nem palavra solta: um texto
        // da pagina que cite "chat", ou o "Continuar" de aceitar uma promo do
        // Chrome, nao podem virar clique
        return CONTINUE_CHAT_LABELS.any { label.equals(it, true) }
    }

    /**
     * Botao "Iniciar conversa" do dialogo de confianca do WhatsApp (numero fora
     * dos contatos). NUNCA o "Cancelar conversa" que fica ao lado.
     */
    private fun isStartChatNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val label = (n.text ?: n.contentDescription ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 30) return false
        val low = label.lowercase()
        // trava: nunca cancelar / bloquear / denunciar, nem que o id bata
        if (WA_DANGER_WORDS.any { low.contains(it) }) return false
        if (START_CHAT_LABELS.any { label.equals(it, true) }) return true
        // Plano B, independente do texto: o botao de seguir do dialogo
        // "Você confia nesta pessoa?" e o primary_button (confirmado por dump).
        // O WhatsApp muda o texto entre versoes (atualiza pela Play Store), e
        // um texto que nao bate deixava o macro tocando no escuro. Exige que o
        // texto pareca "seguir adiante", pra nao clicar o primary de outro
        // dialogo qualquer (ex: "Fazer backup").
        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
        return id == "primary_button" && WA_GO_WORDS.any { low.contains(it) }
    }

    /**
     * Dispensa um popup do proprio Chrome (promo de notificacoes, login, tornar
     * padrao) que esteja cobrindo a pagina. So age com o dialogo de fato na tela
     * e clicando num botao de DISPENSAR — nunca num de aceitar. Devolve true se
     * dispensou algo. E o que faltava no 2o Redmi: a promo "As notificacoes do
     * Chrome facilitam tudo" travava a passada inteira.
     */
    private fun dismissBrowserPromo(front: String): Boolean {
        if (!isBrowser(front)) return false
        val hasDialog = findFirst(activeRoots()) { n ->
            n.isVisibleToUser &&
                (n.viewIdResourceName ?: "").substringAfterLast('/') == CHROME_DIALOG_ID
        } != null
        if (!hasDialog) return false
        val btn = findFirst(activeRoots(), ::isDismissNode) ?: return false
        val ok = clickNode(btn)
        Log.i(TAG, "dispensei popup do Chrome ${describe(btn)} ok=$ok")
        return ok
    }

    private fun isDismissNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val label = (n.text ?: n.contentDescription ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 30) return false
        val low = label.lowercase()
        // segunda trava: mesmo batendo um rotulo de dispensar, se a frase tiver
        // palavra de aceitar, nao clica (ex: "Sim, dispensar")
        if (ACCEPT_WORDS.any { low.contains(it) }) return false
        return DISMISS_LABELS.any { label.equals(it, true) }
    }

    /**
     * Saiu da rota: o passo esperava um app e a tela esta em outro. Faz a faxina
     * e recomeca a passada do zero, em vez de seguir tocando por coordenada numa
     * tela que nao e a de sempre.
     */
    private fun onOffRoute(step: Step, found: String) {
        offRouteStreak++
        recoveries++
        guardWaitedMs = 0L
        Log.i(
            TAG,
            "FORA DE ROTA no passo ${stepIndex + 1}: esperava ${step.app}, achei " +
                "${found.ifBlank { "nada" }} (seguidas=$offRouteStreak total=$recoveries)"
        )
        // Dentro do WhatsApp, anota o que estava na tela: e assim que se descobre
        // o texto de um dialogo novo sem precisar do cabo.
        if (found.contains("whatsapp", true)) logVisibleNodes("whatsapp/fora-de-rota")
        toast("Saí da rota. Limpando o navegador e voltando pro início.")
        recovering = true
        recoverStartUptime = SystemClock.uptimeMillis()
        val gen = ++recoverGen // marca esta recuperacao
        updateBubble()
        recoverToStart {
            // Se o vigia desistiu desta recuperacao (empacou >60s) e ja recomecou
            // a passada, este callback velho chegou atrasado: ignora, senao teria
            // dois motores tocando o macro ao mesmo tempo.
            if (gen != recoverGen) {
                Log.i(TAG, "recuperacao antiga (gen $gen) chegou tarde, descartada")
                return@recoverToStart
            }
            recovering = false
            if (!playing) return@recoverToStart
            stepIndex = 0
            guardWaitedMs = 0L
            // Nas primeiras seguidas tenta de novo rapido; depois volta pro ritmo
            // normal. Sem esse freio, um chip banido de vez viraria uma faxina a
            // cada poucos segundos, o dia inteiro.
            val gap = if (offRouteStreak <= OFF_ROUTE_FAST_RETRIES || intervalMs <= 0) {
                RETRY_AFTER_RECOVER_MS
            } else {
                randomizedGap(intervalMs)
            }
            // Passa pelo waitTicker (e nao por um postDelayed seco) pra bolha
            // mostrar a contagem: parado sem contador parece travado, e no teste
            // de 31/08 o Gabriel apertou ■ achando que tinha morrido.
            waitingGap = true
            waitIsRetry = true
            waitDeadline = SystemClock.uptimeMillis() + gap
            Log.i(TAG, "recuperado; proxima tentativa em ${gap / 1000}s")
            handler.removeCallbacks(waitTicker)
            handler.post(waitTicker)
            updateBubble()
        }
    }

    /**
     * A faxina: fecha as abas do navegador e volta pra tela inicial.
     * NUNCA encosta no proprio AutoClick — matar o nosso processo desligaria o
     * servico de acessibilidade junto (a Activity divide processo com ele) e o
     * macro morreria em vez de se recuperar.
     */
    private fun recoverToStart(done: () -> Unit) {
        // O painel de notificacoes NAO fecha com HOME. Sem fechar ele primeiro,
        // o macro fica preso num ciclo de "fora de rota -> HOME -> painel ainda
        // por cima -> fora de rota" (foi o que o teste de 31/08 mostrou).
        dismissShade()
        handler.postDelayed({
            closeAllBrowserTabs {
                performGlobalAction(GLOBAL_ACTION_HOME)
                // o launcher precisa de um respiro antes da proxima passada
                handler.postDelayed({ done() }, 1_500)
            }
        }, 400)
    }

    /** Fecha o painel de notificacoes / ajustes rapidos (Android 12+). */
    private fun dismissShade() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Fecha todas as abas do navegador pelos botoes de verdade (seletor de abas
     * -> menu -> "Fechar todas as guias" -> confirmar), nunca por coordenada.
     * E melhor esforco: se qualquer etapa falhar, sai pelo BACK e segue pra tela
     * inicial. Aba que sobrou incomoda; travar aqui seria pior.
     */
    private fun closeAllBrowserTabs(done: () -> Unit) {
        if (!isBrowser(foregroundPackage())) {
            done()
            return
        }
        // Sobrou um dialogo de confirmacao de uma faxina anterior que nao se
        // completou? Ele trava o navegador INTEIRO, entao resolve antes de tudo.
        // Sem isto o macro fica preso pra sempre numa caixa que ele mesmo abriu.
        val pendente = findFirst(activeRoots(), ::isConfirmCloseNode)
        if (pendente != null) {
            Log.i(TAG, "faxina: confirmacao pendente na tela, confirmando ${describe(pendente)}")
            clickNode(pendente)
            handler.postDelayed({ done() }, 800)
            return
        }

        // id primeiro, texto so como plano B: ver isTabSwitcherButton
        val switcher = findFirst(screenRoots(), ::isTabSwitcherButton)
            ?: findFirst(screenRoots(), ::isTabSwitcherButtonLoose)
        if (switcher == null) {
            Log.i(TAG, "faxina: nao achei o seletor de abas")
            logVisibleNodes("faxina/sem-seletor")
            // Alguma coisa esta cobrindo a barra (dialogo, aviso). Um BACK tira
            // a maioria; se nao tirar, o HOME que vem em seguida resolve.
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ done() }, 500)
            return
        }
        Log.i(TAG, "faxina: seletor de abas ${describe(switcher)}")
        // So limpa quando ha mais de uma aba: com so a do AllWin aberta nao ha
        // bagunca pra tirar, e fechar a boa a toa so da trabalho pro macro.
        val tabs = tabCount(switcher)
        if (tabs == 1) {
            Log.i(TAG, "faxina: so 1 aba aberta, nada a fechar")
            done()
            return
        }
        if (!clickNode(switcher)) {
            Log.i(TAG, "faxina: o seletor de abas nao aceitou o clique")
            done()
            return
        }
        // O seletor abre com animacao. SEM essa pausa, o ⋮ que a busca encontra
        // ainda e o da PAGINA, e o menu que abre nao tem "Fechar todas as
        // guias" — foi exatamente o que falhou no teste de 31/08.
        handler.postDelayed({ openTabSwitcherMenu(done) }, 900)
    }

    /** Segunda etapa: o ⋮ que ja e o do seletor de abas, nao o da pagina. */
    private fun openTabSwitcherMenu(done: () -> Unit) {
        // Cada etapa e uma funcao separada de proposito: aninhar os pollForNode
        // deixava dois "return@pollForNode" no mesmo escopo, e o de dentro podia
        // estar voltando pro lambda errado.
        pollForNode(10, { isBrowserMenuButton(it) || isBrowserMenuButtonLoose(it) }) { menu ->
            if (menu == null || !clickNode(menu)) {
                Log.i(TAG, "faxina: nao achei o menu do seletor de abas")
                logVisibleNodes("faxina/sem-menu")
                backOut(done)
            } else {
                Log.i(TAG, "faxina: cliquei no menu ${describe(menu)}")
                clickCloseAllTabs(done)
            }
        }
    }

    /** Um no em uma linha, pra log de diagnostico. */
    private fun describe(n: AccessibilityNodeInfo): String {
        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
        val d = n.contentDescription?.toString() ?: ""
        val t = n.text?.toString() ?: ""
        return "[id=$id desc=$d text=$t]"
    }

    /**
     * Diagnostico: lista o que esta visivel na tela da frente. So roda quando a
     * faxina falha, entao o custo nao pesa no uso normal.
     */
    private fun logVisibleNodes(tag: String) {
        val achados = ArrayList<String>()
        for (root in activeRoots()) {
            val fila = ArrayDeque<AccessibilityNodeInfo>()
            fila.add(root)
            var vistos = 0
            while (fila.isNotEmpty() && vistos < 400) {
                val n = fila.removeFirst()
                vistos++
                try {
                    if (n.isVisibleToUser) {
                        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
                        val d = n.contentDescription?.toString() ?: ""
                        val t = n.text?.toString() ?: ""
                        if (id.isNotBlank() || d.isNotBlank() || t.isNotBlank()) {
                            achados.add("[$id|$d|$t]")
                        }
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        Log.i(TAG, "$tag (${achados.size} nos): ${achados.take(45).joinToString(" ")}")
    }

    /**
     * Quantas abas o navegador diz ter, lendo o proprio botao do seletor (ele
     * mostra o numero dentro do quadradinho). -1 quando nao da pra saber, e ai
     * a faxina segue: na duvida, limpar e mais seguro que deixar sujo.
     */
    private fun tabCount(switcher: AccessibilityNodeInfo): Int {
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(switcher)
        var seen = 0
        while (queue.isNotEmpty() && seen < 12) {
            val n = queue.removeFirst()
            seen++
            try {
                n.text?.let { sb.append(it).append(' ') }
                n.contentDescription?.let { sb.append(it).append(' ') }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            } catch (_: Throwable) {
            }
        }
        return Regex("\\d+").find(sb)?.value?.toIntOrNull() ?: -1
    }

    /** Segunda etapa da faxina: o item "Fechar todas as guias" do menu. */
    private fun clickCloseAllTabs(done: () -> Unit) {
        pollForNode(10, ::isCloseAllTabsNode) { closeAll ->
            if (closeAll == null || !clickNode(closeAll)) {
                Log.i(TAG, "faxina: nao achei o \"Fechar todas as guias\"")
                logVisibleNodes("faxina/sem-fechar-todas")
                backOut(done)
            } else {
                confirmCloseAllTabs(done)
            }
        }
    }

    /** Ultima etapa: o dialogo "Fechar todas as N guias?" nem sempre aparece. */
    private fun confirmCloseAllTabs(done: () -> Unit) {
        pollForNode(8, ::isConfirmCloseNode) { confirm ->
            when {
                confirm == null ->
                    // Sem dialogo: o Chrome fecha direto quando ha poucas abas.
                    Log.i(TAG, "faxina: abas fechadas (sem confirmacao)")
                clickNode(confirm) ->
                    Log.i(TAG, "faxina: abas fechadas, confirmei em ${describe(confirm)}")
                else -> {
                    // O dialogo ficou aberto travando o Chrome: sai dele.
                    Log.i(TAG, "faxina: o confirmar nao aceitou o clique")
                    logVisibleNodes("faxina/confirmar-falhou")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            handler.postDelayed({ done() }, 600)
        }
    }

    /** Sai de qualquer menu aberto antes de seguir pra tela inicial. */
    private fun backOut(done: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ done() }, 400)
        }, 400)
    }

    /**
     * Procura um no por algumas rodadas antes de desistir: menu de navegador
     * abre com animacao e na primeira olhada quase nunca esta pronto.
     */
    private fun pollForNode(
        tries: Int,
        match: (AccessibilityNodeInfo) -> Boolean,
        onDone: (AccessibilityNodeInfo?) -> Unit
    ) {
        var left = tries
        lateinit var r: Runnable
        r = Runnable {
            val n = findFirst(screenRoots(), match)
            when {
                n != null -> onDone(n)
                --left <= 0 -> onDone(null)
                else -> handler.postDelayed(r, 200)
            }
        }
        handler.postDelayed(r, 200)
    }

    /**
     * Botao do seletor de abas PELO ID. E a busca preferida, tentada antes da
     * por texto: na barra do Chrome existem dois botoes cuja descricao fala em
     * "guia" — o seletor ("Ver 5 guias") e o "+" ("Nova guia") — e a busca
     * frouxa achava o "+" primeiro. Resultado: em vez de abrir o seletor, a
     * faxina ABRIA UMA ABA NOVA e depois caia no menu da pagina, que nao tem
     * "Fechar todas as guias". Ela piorava o problema que devia resolver.
     */
    private fun isTabSwitcherButton(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        return (n.viewIdResourceName ?: "").substringAfterLast('/') in TAB_SWITCHER_IDS
    }

    /**
     * Plano B, so se o id nao vier: exige um NUMERO na descricao ("Ver 5
     * guias"), que e o que separa o seletor do botao "Nova guia".
     */
    private fun isTabSwitcherButtonLoose(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        // exigir que tenha id ja separa componente do navegador de conteudo de
        // pagina, que nunca expoe id
        if ((n.viewIdResourceName ?: "").isEmpty()) return false
        val desc = n.contentDescription?.toString() ?: return false
        if (desc.contains("nova", true) || desc.contains("new", true)) return false
        if (!Regex("\\d").containsMatchIn(desc)) return false
        return desc.contains("guia", true) || desc.contains("aba", true) ||
            desc.contains("tab", true)
    }

    /**
     * Item "Fechar todas as guias" do menu do seletor de abas. O id vem primeiro
     * porque nao muda com o idioma do celular; o texto e o plano B pra navegador
     * que nao exponha id.
     */
    private fun isCloseAllTabsNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
        if (id in CLOSE_ALL_TABS_IDS) return true
        val label = (n.text ?: n.contentDescription ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 32) return false
        // equals, nao contains: "Fechar" cru pegaria botao de qualquer dialogo
        return CLOSE_ALL_TABS_LABELS.any { label.equals(it, true) }
    }

    /**
     * Botao de confirmar do dialogo "Fechar todas as guias?". Pelo id primeiro:
     * o "negative_button" (Cancelar) fica ao lado, entao errar aqui significaria
     * cancelar a limpeza sem perceber.
     */
    private fun isConfirmCloseNode(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
        if (id in CONFIRM_CLOSE_IDS) return true
        // nunca o Cancelar, mesmo que o texto dele um dia parecesse bater
        if (id == "negative_button" || id == "button2") return false
        val label = (n.text ?: n.contentDescription ?: return false).toString().trim()
        if (label.isEmpty() || label.length > 40) return false
        return CONFIRM_CLOSE_LABELS.any { label.equals(it, true) }
    }

    // =====================================================================
    // PONTE COM O SERVIDOR (Sync / Updater)
    // =====================================================================

    val currentMacroId: String? get() = current?.id

    /**
     * Momento seguro pra instalar uma atualizacao (o processo morre): parado,
     * pausado, ou na espera entre passadas com 1 min de folga. No meio de uma
     * passada, de um gesto ou de uma recuperacao, nao.
     */
    fun safeToUpdate(): Boolean {
        if (recording) return false
        if (!playing || paused) return true
        if (recovering || dispatching) return false
        if (waitingGap) return waitDeadline - SystemClock.uptimeMillis() > 60_000L
        return false
    }

    /** Estado resumido pro relatorio que vai pro servidor. */
    fun stateJson(): JSONObject = JSONObject()
        .put("playing", playing)
        .put("paused", paused)
        .put("recording", recording)
        .put("macro", current?.name ?: "")
        .put("macroId", current?.id ?: "")
        .put("step", stepIndex)
        .put("steps", current?.steps?.size ?: 0)
        .put("loops", loopDone)
        .put("resets", recoveries)
        .put("waiting", waitingGap)
        .put(
            "nextInS",
            if (waitingGap) ((waitDeadline - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0L) else 0L
        )
        .put("recovering", recovering)
        .put("quietSleeping", quietSleeping)
        .put("gapMs", intervalMs)
        .put("quiet", quietEnabled)

    /** O servidor mandou rodar (autorun do config.json): tela inicial e comeca em 3 s. */
    fun applyAutorun(m: Macro, gapMs: Long, quiet: Boolean) {
        goHomeNow()
        start(m, 3_000, gapMs, quietWindow = quiet)
    }

    /**
     * O conteudo do macro em execucao mudou no servidor: recomeca com o novo,
     * no mesmo modo (intervalo/Especial). Parado, so troca a referencia.
     */
    fun restartWith(fresh: Macro) {
        val cur = current ?: return
        if (cur.id != fresh.id) return
        if (!playing) {
            current = fresh
            updateBubble()
            return
        }
        val gap = intervalMs
        val quiet = quietEnabled
        Log.i(TAG, "macro \"${fresh.name}\" mudou no servidor: recomecando")
        goHomeNow()
        start(fresh, 3_000, gap, quietWindow = quiet)
    }

    /** Comando "restart": recomeca o que esta rodando (ou o estado gravado). */
    fun restartCurrent() {
        val cur = current
        if (playing && cur != null) {
            val gap = intervalMs
            val quiet = quietEnabled
            goHomeNow()
            start(cur, 3_000, gap, quietWindow = quiet)
            return
        }
        val st = Store.loadRunState(this) ?: return
        val m = Store.byId(this, st.macroId) ?: return
        goHomeNow()
        start(m, 3_000, st.gapMs, quietWindow = st.quiet)
    }

    /** Comando "relearn": esquece app+alvo dos passos do macro atual e recomeca. */
    fun relearnAndRestart() {
        val m = current ?: Store.selected(this) ?: return
        m.steps.forEach {
            it.app = ""
            it.anchor = ""
        }
        Store.update(this, m)
        if (playing) {
            val gap = intervalMs
            val quiet = quietEnabled
            goHomeNow()
            start(m, 3_000, gap, quietWindow = quiet)
        }
    }

    /** Fecha o painel de notificacoes e vai pra tela inicial. */
    fun goHomeNow() {
        dismissShade()
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Throwable) {
        }
    }

    // =====================================================================
    // UTILIDADES
    // =====================================================================

    private fun displaySize(): Point {
        val dm = resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun glyph(text: String, size: Float, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun glyph(text: String, size: Int, onClick: () -> Unit): TextView =
        glyph(text, size.toFloat(), onClick)

    /** Arrasta a janela pelo "punho" sem confundir com clique. */
    private fun dragger(
        p: WindowManager.LayoutParams,
        apply: () -> Unit
    ): View.OnTouchListener {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    originX = p.x
                    originY = p.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (abs(dx) + abs(dy) > 4f) {
                        p.x = originX + dx.toInt()
                        p.y = originY + dy.toInt()
                        try {
                            apply()
                        } catch (_: Throwable) {
                        }
                    }
                    true
                }

                else -> true
            }
        }
    }

    /**
     * addView de overlay pode falhar com token invalido depois do servico reiniciar.
     * Tenta de novo com um WindowManager recem pegado antes de desistir.
     */
    private fun addOverlay(view: View, p: WindowManager.LayoutParams, tag: String): Boolean {
        try {
            wm?.addView(view, p)
            Log.i(TAG, "$tag adicionada")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "addView de $tag falhou, tentando de novo", t)
        }
        return try {
            val fresh = getSystemService(WindowManager::class.java)
            wm = fresh
            fresh.addView(view, p)
            Log.i(TAG, "$tag adicionada na segunda tentativa")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "addView de $tag falhou de vez", t)
            false
        }
    }

    private fun removeView(v: View?) {
        if (v == null) return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
