package mov.film.lifecycle.test;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;

/** Uses the installed player's own lifecycle with an unprepared Media3 player. */
public final class PlayerLifecycleRegression extends Instrumentation {
    private String fixtureUrl;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        fixtureUrl = args == null ? null : args.getString("fixtureUrl");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        final Activity[] activity = new Activity[1];
        final Object[] player = new Object[1];
        try {
            if (fixtureUrl == null) throw new IllegalArgumentException("Pass -e fixtureUrl http://HOST:PORT/delayed.mp4 (silent clip, delayed response)");
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> type = Class.forName("mov.film.PlayerActivity", true, loader);
            Class<?> api = Class.forName("androidx.media3.common.Player", true, loader);
            Class<?> exoApi = Class.forName("androidx.media3.exoplayer.ExoPlayer", true, loader);
            Intent intent = new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", fixtureUrl)
                .putExtra("title", "Lifecycle regression").putExtra("key", "");
            activity[0] = startActivitySync(intent);
            Field field = type.getDeclaredField("player"); field.setAccessible(true);
            Field prepared = type.getDeclaredField("prepared"); prepared.setAccessible(true);
            Field stopped = type.getDeclaredField("airStopped"); stopped.setAccessible(true);
            Method play = type.getDeclaredMethod("airPlay", api); play.setAccessible(true);
            final Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    player[0] = field.get(activity[0]);
                    if (player[0] == null) throw new AssertionError("Player not created");
                    field.set(activity[0], player[0]); prepared.setBoolean(activity[0], false);
                    api.getMethod("setPlayWhenReady", boolean.class).invoke(player[0], true);
                    if ((Boolean) api.getMethod("isPlaying").invoke(player[0])) throw new AssertionError("Player unexpectedly ready");
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw failure[0];
            getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!stopped.getBoolean(activity[0])) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Player did not stop");
                SystemClock.sleep(20);
            }
            // Let the actual delayed source finish preparing while HOME is visible.
            // This catches autoplay from real Media3 callbacks, beyond the manual late call below.
            final int[] playbackState = new int[1];
            deadline = SystemClock.elapsedRealtime() + 30_000;
            do {
                runOnMainSync(() -> {
                    try {
                        playbackState[0] = (Integer) api.getMethod("getPlaybackState").invoke(player[0]);
                        if ((Boolean) api.getMethod("getPlayWhenReady").invoke(player[0]))
                            throw new AssertionError("Background loading enabled autoplay");
                    } catch (Throwable error) { failure[0] = error; }
                });
                if (failure[0] != null) throw failure[0];
                if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Delayed fixture never reached STATE_READY");
                SystemClock.sleep(50);
            } while (playbackState[0] != 3); // Media3 Player.STATE_READY
            runOnMainSync(() -> {
                try {
                    if ((Boolean) api.getMethod("getPlayWhenReady").invoke(player[0])) throw new AssertionError("Buffering player retained autoplay in background");
                    play.invoke(activity[0], player[0]);
                    if ((Boolean) api.getMethod("getPlayWhenReady").invoke(player[0])) throw new AssertionError("Late callback started playback in background");
                    stopped.setBoolean(activity[0], false);
                    play.invoke(activity[0], player[0]);
                    if (!(Boolean) api.getMethod("getPlayWhenReady").invoke(player[0])) throw new AssertionError("Foreground play was blocked");
                    stopped.setBoolean(activity[0], true);
                    exoApi.getMethod("release").invoke(player[0]); field.set(activity[0], null);
                    activity[0].finish();
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw failure[0];
            result.putString("stream", "Stopped buffering player reaches READY without autoplay; late callback stays paused; foreground play allowed: PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
