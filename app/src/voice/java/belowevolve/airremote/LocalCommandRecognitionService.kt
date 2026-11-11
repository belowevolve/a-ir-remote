package belowevolve.airremote

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The assistant metadata requires a recognition provider. It exposes our local command vocabulary. */
class LocalCommandRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    @Volatile private var recorder: AudioRecord? = null

    @android.annotation.SuppressLint("MissingPermission")
    override fun onStartListening(intent: Intent?, listener: Callback) {
        if (session?.isActive == true || VoiceControlService.status.value.active || MicrophoneService.status.value.active) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        session = scope.launch {
            try {
                OfflineVoiceRecognizer(this@LocalCommandRecognitionService).use { recognizer ->
                    val rate = OfflineVoiceRecognizer.SAMPLE_RATE
                    val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(rate * 2, AudioRecord.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)).build()
                    try {
                        recorder = input
                        if (!isActive) return@launch
                        input.startRecording()
                        listener.readyForSpeech(Bundle())
                        val samples = ShortArray(rate / 10)
                        val deadline = SystemClock.elapsedRealtime() + 8_000
                        while (isActive && SystemClock.elapsedRealtime() < deadline) {
                            val count = input.read(samples, 0, samples.size)
                            if (!isActive) return@launch
                            check(count > 0)
                            val command = recognizer.accept(samples, count) ?: continue
                            listener.results(Bundle().apply {
                                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(
                                    command.transcript,
                                ))
                            })
                            return@launch
                        }
                        if (isActive) listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    } finally {
                        recorder = null
                        runCatching { input.stop() }
                        input.release()
                    }
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (isActive) runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) } }
        }
    }

    override fun onCancel(listener: Callback) {
        session?.cancel()
        runCatching { recorder?.stop() }
    }

    override fun onStopListening(listener: Callback) {
        onCancel(listener)
        listener.error(SpeechRecognizer.ERROR_NO_MATCH)
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { recorder?.stop() }
        super.onDestroy()
    }
}
