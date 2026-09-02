package com.gm.autoclick

import android.content.Context
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs

/**
 * Exporta e importa macros como arquivo .json com envelope versionado:
 * {"app":"autoclick","ver":1,"screen":{"w":1080,"h":2340},"macro":{...}}
 *
 * O envelope carrega a resolucao da tela de ORIGEM porque as coordenadas dos
 * passos sao pixels absolutos (rawX/rawY da tela fisica). No import em tela
 * diferente, os pontos sao reescalados na proporcao e o usuario e avisado.
 */
object Porter {

    const val VER = 1
    private const val MAX_BYTES = 1_000_000 // macros tem KBs; 1 MB ja e folga absurda
    private const val MAX_STEPS = 20_000    // sanidade contra arquivo forjado

    /** Erro de import com mensagem pronta pra mostrar ao usuario. */
    class ImportError(message: String) : Exception(message)

    /** Resultado do parse: macro ja reescalado; id/nome ainda os originais. */
    class Imported(
        val macro: Macro,
        val origW: Int,
        val origH: Int,
        val rescaled: Boolean,
        val aspectDiffers: Boolean
    )

    /**
     * Tamanho REAL do display (com status bar e navbar): os pontos gravados
     * vem de rawX/rawY num overlay sem limites, entao e essa a referencia.
     * resources.displayMetrics exclui a navbar em varias versoes — nao usar.
     */
    fun screenSize(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
            ?: return Point(0, 0)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    /** Escreve o arquivo em cacheDir/exports e devolve a Uri do FileProvider. */
    fun export(ctx: Context, macro: Macro): Uri {
        val dir = File(ctx.cacheDir, "exports")
        dir.mkdirs()
        // nao acumula lixo de exports anteriores
        dir.listFiles()?.forEach { it.delete() }

        val size = screenSize(ctx)
        val envelope = JSONObject()
            .put("app", "autoclick")
            .put("ver", VER)
            .put(
                "screen",
                JSONObject().put("w", size.x).put("h", size.y)
            )
            .put("macro", macro.toJson())

        val file = File(dir, "autoclick-${sanitizeFileName(macro.name)}.json")
        file.writeText(envelope.toString())
        // packageName, nao BuildConfig: o AGP 8 nao gera BuildConfig por padrao
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }

    /** Nome seguro pra arquivo (compat ate com Windows); acentos ficam. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(40)
            .trim(' ', '.')
        return cleaned.ifBlank { "macro" }
    }

    /** Le, valida e reescala. Joga ImportError com mensagem pt-BR. */
    fun parseImport(stream: InputStream?, dest: Point): Imported {
        if (stream == null) throw ImportError("Não deu pra abrir o arquivo.")

        // leitura com teto: nunca confiar em available()
        val buf = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) {
                    throw ImportError("Arquivo grande demais para ser um macro do AutoClick.")
                }
            }
        }

        val root = try {
            JSONObject(out.toString("UTF-8"))
        } catch (_: Throwable) {
            throw ImportError("Este arquivo não é um macro do AutoClick.")
        }

        if (root.optString("app") != "autoclick") {
            throw ImportError("Este arquivo não é um macro do AutoClick.")
        }
        val ver = root.optInt("ver", 0)
        if (ver < 1) throw ImportError("Este arquivo não é um macro do AutoClick.")
        if (ver > VER) {
            throw ImportError("Este arquivo veio de uma versão mais nova do AutoClick. Atualize o app neste celular.")
        }

        val macroJson = root.optJSONObject("macro")
            ?: throw ImportError("Este arquivo não é um macro do AutoClick.")
        val macro = Macro.fromJson(macroJson)
        if (macro.steps.isEmpty()) throw ImportError("Esse macro está vazio.")
        if (macro.steps.size > MAX_STEPS) {
            throw ImportError("Este arquivo não é um macro do AutoClick.")
        }

        val screen = root.optJSONObject("screen")
        val ow = screen?.optInt("w", 0) ?: 0
        val oh = screen?.optInt("h", 0) ?: 0

        // origem desconhecida: importa como esta e o caller avisa
        if (ow <= 0 || oh <= 0) {
            return Imported(macro, 0, 0, rescaled = false, aspectDiffers = false)
        }
        // mesma tela: bypass completo, floats intactos (round-trip bit a bit)
        if (ow == dest.x && oh == dest.y) {
            return Imported(macro, ow, oh, rescaled = false, aspectDiffers = false)
        }

        val sx = dest.x.toFloat() / ow
        val sy = dest.y.toFloat() / oh
        val rescaledSteps = macro.steps.map { step ->
            // map em lista vazia devolve vazia: passo HOME atravessa intacto
            Step(
                step.type,
                step.pts.map {
                    Pt(
                        (it.x * sx).coerceIn(0f, dest.x - 1f),
                        (it.y * sy).coerceIn(0f, dest.y - 1f)
                    )
                },
                step.durationMs,
                step.delayBeforeMs,
                // app e ancora nao mudam com o tamanho da tela: WhatsApp e
                // WhatsApp e o id do botao e o mesmo em qualquer celular
                step.app,
                step.anchor
            )
        }
        macro.steps.clear()
        macro.steps.addAll(rescaledSteps)

        val destAspect = dest.x.toFloat() / dest.y
        val origAspect = ow.toFloat() / oh
        val aspectDiffers = abs(origAspect - destAspect) / destAspect > 0.02f

        return Imported(macro, ow, oh, rescaled = true, aspectDiffers = aspectDiffers)
    }
}
