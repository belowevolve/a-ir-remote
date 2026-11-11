package belowevolve.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Captures actual injected media keys in an isolated focused activity, without changing playlists. */
public class RemoteMediaProbeRunner extends Instrumentation {
    private List<Integer> expected = Arrays.asList(127, 126, 87, 88);
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null && "23".equals(args.getString("codes"))) expected = Arrays.asList(23);
        start();
    }
    @Override public void onStart() {
        List<Integer> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(expected.size());
        Bundle result = new Bundle();
        try {
            ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (code == 0) ready.countDown();
                    else { events.add(code); latch.countDown(); }
                }
            };
            getContext().startActivity(new Intent().setComponent(new ComponentName(
                "belowevolve.tvhome.test", "belowevolve.tvhome.RemoteKeyProbeActivity"))
                .putExtra("receiver", receiver).putExtra("expectedCount", expected.size()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Key fixture did not become ready");
            Bundle status = new Bundle();
            status.putString("stream", "MEDIA_PROBE_READY\n");
            sendStatus(0, status);
            if (!latch.await(45, TimeUnit.SECONDS)) throw new AssertionError("Missing keys: " + events);
            if (!events.equals(expected)) throw new AssertionError("Wrong keys: " + events);
            result.putString("stream", "Android TV received keys: " + events + ": PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
