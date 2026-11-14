package mov.film.airvideo;

import android.app.Activity;
import android.view.SurfaceHolder;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resume native video only on the live display surface, never on a placeholder. */
public final class SurfaceRestore implements SurfaceHolder.Callback {
    private final WeakReference<Activity> owner;
    public SurfaceRestore(Activity activity) { owner = new WeakReference<>(activity); }
    public static boolean supported() {
        return android.os.Build.VERSION.SDK_INT == 34 && android.os.Build.HARDWARE.equals("m7632")
            && android.os.Build.DEVICE.equals("daan") && android.os.Build.ID.equals("UKNV.260410.001")
            && android.os.Build.VERSION.INCREMENTAL.equals("15184903");
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { restore(holder); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { restore(holder); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}
    private void restore(SurfaceHolder holder) {
        Activity activity = owner.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !holder.getSurface().isValid()) return;
        try {
            Field stopped = activity.getClass().getDeclaredField("airStopped"); stopped.setAccessible(true);
            if (stopped.getBoolean(activity)) return;
            Method restore = activity.getClass().getDeclaredMethod("airRestoreVideo"); restore.setAccessible(true);
            restore.invoke(activity);
        } catch (ReflectiveOperationException error) {
            android.util.Log.e("FilmVideoLifecycle", "Cannot restore display surface", error);
        }
    }
}
