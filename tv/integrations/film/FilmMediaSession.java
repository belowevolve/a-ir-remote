package mov.film.airvoice;

import android.app.Activity;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;

/** Publishes the real foreground Media3 player through Android's standard MediaSession API. */
public final class FilmMediaSession implements InvocationHandler {
    private static FilmMediaSession active;
    private final WeakReference<Activity> owner;
    private final Class<?> api;
    private final Class<?> listenerApi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private Object player;
    private Object listener;

    public static void start(Activity activity) {
        if (active != null && active.owner.get() == activity) { active.refresh(); return; }
        if (active != null) active.release();
        try {
            active = new FilmMediaSession(activity);
            active.refresh();
        } catch (ReflectiveOperationException error) {
            active = null;
            android.util.Log.e("FilmMediaSession", "Media3 contract changed", error);
        }
    }
    public static void sync(Activity activity) {
        if (active != null && active.owner.get() == activity) active.refresh();
    }
    public static void stop(Activity activity) {
        if (active != null && active.owner.get() == activity) { active.release(); active = null; }
    }

    private FilmMediaSession(Activity activity) throws ReflectiveOperationException {
        owner = new WeakReference<>(activity);
        api = Class.forName("androidx.media3.common.Player");
        listenerApi = Class.forName("androidx.media3.common.Player$Listener");
        session = new MediaSession(activity, "film.mov");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { action("play"); }
            @Override public void onPause() { action("pause"); }
            @Override public void onSeekTo(long position) { seek(position); }
            @Override public void onFastForward() { action("seekForward"); }
            @Override public void onRewind() { action("seekBack"); }
            @Override public void onSkipToNext() { skip(KeyEvent.KEYCODE_MEDIA_NEXT); }
            @Override public void onSkipToPrevious() { skip(KeyEvent.KEYCODE_MEDIA_PREVIOUS); }
        }, main);
        session.setActive(true);
    }

    private boolean current() {
        Activity activity = owner.get();
        return active == this && session != null && activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
    private Object call(String name) throws ReflectiveOperationException { return api.getMethod(name).invoke(player); }
    private void detach() {
        if (player != null && listener != null) {
            try { api.getMethod("removeListener", listenerApi).invoke(player, listener); }
            catch (ReflectiveOperationException ignored) { }
        }
        listener = null; player = null;
    }
    private void refresh() {
        if (!current()) return;
        try {
            Activity activity = owner.get();
            Field field = activity.getClass().getDeclaredField("player"); field.setAccessible(true);
            Object next = field.get(activity);
            if (next != player) {
                detach(); player = next;
                if (player != null) {
                    listener = Proxy.newProxyInstance(listenerApi.getClassLoader(), new Class<?>[]{listenerApi}, this);
                    api.getMethod("addListener", listenerApi).invoke(player, listener);
                }
            }
            publish();
        } catch (ReflectiveOperationException error) {
            android.util.Log.w("FilmMediaSession", "Cannot publish player", error);
        }
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
        if (method.getName().equals("toString")) return "FilmMediaSession.Listener";
        if (method.getName().equals("onEvents")) refresh();
        return null;
    }
    private void publish() throws ReflectiveOperationException {
        if (!current()) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
            PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        int state = PlaybackState.STATE_NONE; long position = 0, duration = 0; float speed = 0f;
        if (player != null) {
            int engine = (Integer) call("getPlaybackState");
            boolean playing = (Boolean) call("isPlaying");
            state = engine == 2 ? PlaybackState.STATE_BUFFERING : engine == 4 ? PlaybackState.STATE_STOPPED :
                engine == 3 ? (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED) : PlaybackState.STATE_NONE;
            position = Math.max(0, (Long) call("getCurrentPosition"));
            duration = Math.max(0, (Long) call("getDuration"));
            if ((Boolean) call("isCurrentMediaItemSeekable"))
                actions |= PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
            if (playing) {
                Object parameters = call("getPlaybackParameters");
                speed = parameters.getClass().getField("speed").getFloat(parameters);
            }
        }
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
            .setState(state, position, speed, SystemClock.elapsedRealtime()).build());
        Activity activity = owner.get();
        String title = activity.getIntent().getStringExtra("title");
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,
            title == null || title.isEmpty() ? "film.mov" : title).putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
    }
    private void action(String name) {
        if (!current()) return;
        refresh(); if (player == null) return;
        try { api.getMethod(name).invoke(player); refresh(); }
        catch (ReflectiveOperationException error) { android.util.Log.w("FilmMediaSession", name, error); }
    }
    private void seek(long requested) {
        if (!current()) return;
        refresh(); if (player == null) return;
        try {
            if (!(Boolean) call("isCurrentMediaItemSeekable")) return;
            long duration = (Long) call("getDuration");
            long target = Math.max(0, requested);
            if (duration > 0) target = Math.min(target, duration);
            api.getMethod("seekTo", long.class).invoke(player, target);
            refresh();
            android.util.Log.i("FilmMediaSession", "SEEK position=" + target);
        } catch (ReflectiveOperationException error) { android.util.Log.w("FilmMediaSession", "seekTo", error); }
    }
    private void skip(int code) {
        if (!current()) return;
        Activity activity = owner.get(); long now = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        main.post(this::refresh);
    }
    private void release() {
        detach(); main.removeCallbacksAndMessages(null);
        if (session != null) { session.setActive(false); session.release(); session = null; }
    }
}
