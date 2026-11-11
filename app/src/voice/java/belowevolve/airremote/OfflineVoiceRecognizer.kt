package belowevolve.airremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/** No system recognizer, network requests or recorded audio files. Owned by the capture thread. */
internal class OfflineVoiceRecognizer(
    context: Context,
    private val resultObserver: ((String) -> Unit)? = null,
    private val trailingSilenceSeconds: Float = 0.25f,
) : AutoCloseable {
    private val model = Model(unpack(context).absolutePath)
    private var numericPhrase = false
    private fun createRecognizer(): Recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), JSONArray(
            LocalVoiceCommand.recognitionPhrases + LocalVoiceCommand.rejectedPhrases,
        ).toString()).apply {
            setWords(true)
            setEndpointerDelays(5f, trailingSilenceSeconds, 8f)
        }
    private var recognizer = try { createRecognizer() }
        catch (error: Throwable) { model.close(); throw error }

    fun accept(samples: ShortArray, count: Int): VoiceCommandRequest? {
        if (recognizer.acceptWaveForm(samples, count)) {
            return try { command(recognizer.result) }
            finally {
                if (numericPhrase) recognizer.setEndpointerDelays(5f, trailingSilenceSeconds, 8f)
                numericPhrase = false
            }
        }
        if (!numericPhrase) {
            val json = recognizer.partialResult
            if (json.contains("громкость") || json.contains("громче") || json.contains("тише") || json.contains("перемотай") || json.contains("отмотай")) {
                val partial = JSONObject(json).optString("partial")
                val seek = partial.startsWith("эйр перемотай") || partial.startsWith("эйр отмотай")
                if (partial.startsWith("эйр громкость") || partial.startsWith("эйр громче") || partial.startsWith("эйр тише") || seek) {
                    // Keep the original pause for numbers: "сорок ... пять" must stay one value.
                    numericPhrase = true
                    val pause = if (seek) 0.5f else 0.35f
                    recognizer.setEndpointerDelays(5f, maxOf(pause, trailingSilenceSeconds), 8f)
                }
            }
        }
        return null
    }

    internal fun reset() {
        // A new decoder also resets word timestamps, unlike Vosk's decoder reset().
        val replacement = createRecognizer()
        recognizer.close()
        recognizer = replacement
        numericPhrase = false
    }

    private fun command(json: String): VoiceCommandRequest? {
        resultObserver?.invoke(json)
        val result = JSONObject(json)
        val command = VoiceCommandRequest.parse(result.optString("text")) ?: return null
        val words = result.optJSONArray("result") ?: return null
        if (words.length() < 2) return null
        val confidence = (0 until words.length()).map { words.getJSONObject(it).optDouble("conf", 0.0) }
        // A constrained grammar can otherwise force unrelated speech into one of the phrases.
        if (confidence.any { it < 0.80 } || confidence.first() < 0.90 || confidence.average() < 0.90) return null
        return command
    }

    override fun close() {
        recognizer.close()
        model.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 640 // 40ms capture frames, without shortening the speech-end pause.
        private const val MODEL_NAME = "vosk-model-small-ru-0.22"

        @Synchronized
        private fun unpack(context: Context): File {
            val root = File(context.noBackupFilesDir, "voice")
            val model = File(root, MODEL_NAME)
            if (File(model, ".ready").isFile) return model
            root.mkdirs()
            val staging = File(root, "unpacking").apply { deleteRecursively(); mkdirs() }
            try {
                ZipInputStream(context.assets.open("voice/ru-small-0.22.zip")).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = File(staging, entry.name)
                        check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use(zip::copyTo)
                        }
                        zip.closeEntry()
                    }
                }
                val unpacked = File(staging, MODEL_NAME)
                check(File(unpacked, "am/final.mdl").isFile) { "Не удалось распаковать русскую модель" }
                model.deleteRecursively()
                check(unpacked.renameTo(model)) { "Не удалось сохранить русскую модель" }
                File(model, ".ready").writeText("0.22")
                return model
            } finally { staging.deleteRecursively() }
        }
    }
}
