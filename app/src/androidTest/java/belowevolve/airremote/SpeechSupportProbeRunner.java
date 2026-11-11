package belowevolve.airremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Capability query only: never starts audio capture, downloads models or changes the provider. */
public final class SpeechSupportProbeRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        final SpeechRecognizer[] recognizer = new SpeechRecognizer[1];
        AtomicBoolean available = new AtomicBoolean();
        AtomicReference<RecognitionSupport> support = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger error = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        int resultCode = Activity.RESULT_OK;
        try {
            if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("Support query requires Android 13+");
            runOnMainSync(() -> {
                try {
                    available.set(SpeechRecognizer.isOnDeviceRecognitionAvailable(getTargetContext()));
                    if (!available.get()) { done.countDown(); return; }
                    recognizer[0] = SpeechRecognizer.createOnDeviceSpeechRecognizer(getTargetContext());
                    recognizer[0].setRecognitionListener(new RecognitionListener() {
                        public void onReadyForSpeech(Bundle b) {}
                        public void onBeginningOfSpeech() {}
                        public void onRmsChanged(float v) {}
                        public void onBufferReceived(byte[] b) {}
                        public void onEndOfSpeech() {}
                        public void onError(int code) { error.set(code); done.countDown(); }
                        public void onResults(Bundle b) {}
                        public void onPartialResults(Bundle b) {}
                        public void onEvent(int type, Bundle b) {}
                    });
                    Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                    recognizer[0].checkRecognitionSupport(request, getTargetContext().getMainExecutor(), new RecognitionSupportCallback() {
                        public void onSupportResult(RecognitionSupport value) { support.set(value); done.countDown(); }
                        public void onError(int code) { error.set(code); done.countDown(); }
                    });
                } catch (Throwable e) { failure.set(e); done.countDown(); }
            });
            boolean completed = done.await(15, TimeUnit.SECONDS);
            StringBuilder report = new StringBuilder("On-device service available: ").append(available.get()).append('\n');
            RecognitionSupport value = support.get();
            if (value != null) {
                report.append("Installed on device: ").append(value.getInstalledOnDeviceLanguages()).append('\n');
                report.append("Supported, needs download: ").append(value.getSupportedOnDeviceLanguages()).append('\n');
                report.append("Pending download: ").append(value.getPendingOnDeviceLanguages()).append('\n');
                report.append("Online languages: ").append(value.getOnlineLanguages()).append('\n');
            }
            if (!completed) report.append("Support query timed out\n");
            if (error.get() != -1) report.append("Support query error: ").append(error.get()).append('\n');
            if (failure.get() != null) report.append("Support query exception: ").append(failure.get()).append('\n');
            report.append("No listening or model download requested\n");
            result.putString("stream", report.toString());
            if (failure.get() != null) resultCode = Activity.RESULT_CANCELED;
        } catch (Throwable e) {
            result.putString("stream", e.toString()); resultCode = Activity.RESULT_CANCELED;
        } finally {
            runOnMainSync(() -> { if (recognizer[0] != null) recognizer[0].destroy(); });
        }
        finish(resultCode, result);
    }
}
