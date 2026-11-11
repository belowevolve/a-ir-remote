package mov.film.lifecycle.test;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;

/** Actual installed Media3 player, real network source, real phone -> Air Home -> player seek. */
public final class VoiceSeekRegression extends Instrumentation {
    private String fixtureUrl;
    private Activity activity;
    private Object player;
    private Class<?> api;
    private Field playerField;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); fixtureUrl = args.getString("fixtureUrl"); start();
    }
    private Object call(String name) throws Exception { return api.getMethod(name).invoke(player); }
    private long position() throws Exception {
        final long[] value = new long[1]; final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { value[0] = (Long) call("getCurrentPosition"); }
            catch (Throwable t) { error[0] = t; } });
        if (error[0] != null) throw new AssertionError(error[0]);
        return value[0];
    }
    private void awaitPosition(long expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        while (Math.abs(position() - expected) > 1000) {
            if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Expected " + expected + " got " + position());
            SystemClock.sleep(50);
        }
    }
    private void route(int seconds) {
        getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("airhome://remote/seek?seconds=" + seconds))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> type = Class.forName("mov.film.PlayerActivity", true, loader);
            api = Class.forName("androidx.media3.common.Player", true, loader);
            playerField = type.getDeclaredField("player"); playerField.setAccessible(true);
            activity = startActivitySync(new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", fixtureUrl).putExtra("title", "Air voice seek fixture").putExtra("key", ""));
            runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            final Throwable[] failure = new Throwable[1];
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            final boolean[] ready = new boolean[1];
            do {
                runOnMainSync(() -> { try {
                    player = playerField.get(activity);
                    ready[0] = player != null && (Integer) call("getPlaybackState") == 3;
                } catch (Throwable t) { failure[0] = t; } });
                if (failure[0] != null) throw failure[0];
                if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Player not ready");
                SystemClock.sleep(50);
            } while (!ready[0]);
            runOnMainSync(() -> { try {
                api.getMethod("pause").invoke(player);
                api.getMethod("seekTo", long.class).invoke(player, 300_000L);
            } catch (Throwable t) { failure[0] = t; } });
            if (failure[0] != null) throw failure[0];
            Bundle status = new Bundle(); status.putString("stream", "VOICE_SEEK_READY: send SEEK +900, then SEEK -300\n"); sendStatus(0, status);
            awaitPosition(1_200_000);
            status.putString("stream", "VOICE_SEEK_FORWARD_PASS\n"); sendStatus(0, status);
            awaitPosition(900_000);
            runOnMainSync(() -> { try {
                if ((Boolean) call("getPlayWhenReady")) throw new AssertionError("Seek resumed paused player");
            } catch (Throwable t) { failure[0] = t; } });
            if (failure[0] != null) throw failure[0];
            route(-6000); awaitPosition(0);
            route(6000); awaitPosition(1_800_000);
            // onStop releases MediaSession: a late request cannot seek a stopped player.
            getTargetContext().startActivity(new Intent().setComponent(new ComponentName("belowevolve.tvhome", "belowevolve.tvhome.SettingsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field stopped = type.getDeclaredField("airStopped"); stopped.setAccessible(true);
            deadline = SystemClock.elapsedRealtime() + 10_000;
            while (!stopped.getBoolean(activity)) {
                if (SystemClock.elapsedRealtime() > deadline) throw new AssertionError("Player never stopped");
                SystemClock.sleep(50);
            }
            long before = position(); route(-300); SystemClock.sleep(500);
            if (Math.abs(before - position()) > 1000) throw new AssertionError("Background player was seeked");
            result.putString("stream", "Phone +15 min, -5 min, pause preserved, clamps at 0/duration, background seek rejected: PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
    }
}
