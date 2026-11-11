package mov.film.lifecycle.test;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import java.lang.reflect.*;

/** Silent local fixture: hold the stopped real player while shell measures HOME presents. */
public final class PlayerFramePacingRegression extends Instrumentation {
    private String fixtureUrl;
    private Activity activity;
    private Object player;
    private Class<?> api;
    private Throwable failure;
    private long holdMillis;
    private long playMillis;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); fixtureUrl = args.getString("fixtureUrl");
        holdMillis = Long.parseLong(args.getString("holdMillis", "20000"));
        playMillis = Long.parseLong(args.getString("playMillis", "5000"));
        start();
    }
    private Object call(String name) throws Exception { return api.getMethod(name).invoke(player); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            if (fixtureUrl == null || !fixtureUrl.startsWith("http://127.0.0.1:"))
                throw new IllegalArgumentException("Use a silent fixture through adb reverse");
            if (holdMillis < 1000 || holdMillis > 60000) throw new IllegalArgumentException("Hold 1..60 seconds");
            if (playMillis < 200 || playMillis > 45000) throw new IllegalArgumentException("Play 0.2..45 seconds");
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> type = Class.forName("mov.film.PlayerActivity", true, loader);
            api = Class.forName("androidx.media3.common.Player", true, loader);
            Field field = type.getDeclaredField("player"); field.setAccessible(true);
            Field stopped = type.getDeclaredField("airStopped"); stopped.setAccessible(true);
            activity = startActivitySync(new Intent(getTargetContext(), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url", fixtureUrl)
                .putExtra("title", "Silent playback fixture").putExtra("key", ""));
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            final boolean[] playing = new boolean[1];
            do {
                runOnMainSync(() -> { try {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    player = field.get(activity);
                    if (player != null) {
                        // Explicit test playback; do not depend on the user's autoplay preference.
                        if (!(Boolean) call("getPlayWhenReady")) api.getMethod("play").invoke(player);
                        playing[0] = (Boolean) call("isPlaying");
                    }
                } catch (Throwable error) { failure = error; } });
                if (failure != null) throw failure;
                if (SystemClock.elapsedRealtime() >= deadline) {
                    final String[] details = new String[1];
                    runOnMainSync(() -> { try {
                        details[0] = "state=" + call("getPlaybackState") + ", playWhenReady=" + call("getPlayWhenReady");
                    } catch (Throwable error) { failure = error; } });
                    throw new AssertionError("Player not playing: " + details[0]);
                }
                SystemClock.sleep(50);
            } while (!playing[0]);
            SystemClock.sleep(playMillis);
            getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            deadline = SystemClock.elapsedRealtime() + 5_000;
            while (!stopped.getBoolean(activity)) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError("Player did not stop");
                SystemClock.sleep(25);
            }
            runOnMainSync(() -> { try {
                if ((Boolean) call("getPlayWhenReady")) throw new AssertionError(
                    "Stopped player retained autoplay: pip=" + activity.isInPictureInPictureMode()
                    + ", state=" + call("getPlaybackState") + ", playing=" + call("isPlaying")
                    + ", finishing=" + activity.isFinishing());
            } catch (Throwable error) { failure = error; } });
            if (failure != null) throw failure;
            Bundle status = new Bundle(); status.putString("stream", "HOME_READY: real player stopped; background activity retained for measurement\n");
            sendStatus(0, status);
            SystemClock.sleep(holdMillis);
            result.putString("stream", "Local clip played; HOME visible; background player paused: PASS\n");
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
        finish(code, result);
    }
}
