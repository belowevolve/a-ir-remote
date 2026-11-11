package belowevolve.airremote;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Standalone test-APK activity: no dependency on the target APK's Kotlin runtime. */
public class VoicePlaybackActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String requested = getIntent().getStringExtra("fixture");
        final String fixture = requested == null ? "air-on" : requested;
        if (!fixture.matches("air-[a-z0-9-]+")) {
            finish();
            return;
        }
        new Thread(() -> {
            SystemClock.sleep(4000);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = getAssets().open("voice/" + fixture + ".pcm")) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                }
                byte[] pcm = output.toByteArray();
                AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(16000, AudioTrack.getMinBufferSize(16000,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                try {
                    Log.i("AirRemoteVoiceTest", "Speaker playback started: " + fixture);
                    track.play();
                    int offset = 0;
                    while (offset < pcm.length) {
                        int written = track.write(pcm, offset, pcm.length - offset);
                        if (written <= 0) throw new IllegalStateException("AudioTrack write failed");
                        offset += written;
                    }
                    long deadline = SystemClock.elapsedRealtime() + 5000;
                    while (track.getPlaybackHeadPosition() < pcm.length / 2 && SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(20);
                    Log.i("AirRemoteVoiceTest", "Speaker playback completed: " + fixture);
                } finally { track.release(); }
            } catch (Exception error) { Log.e("AirRemoteVoiceTest", "Playback failed", error); }
            finally { runOnUiThread(this::finish); }
        }, "VoiceSpeakerFixture").start();
    }
}
