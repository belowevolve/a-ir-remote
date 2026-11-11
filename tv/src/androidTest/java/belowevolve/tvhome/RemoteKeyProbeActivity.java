package belowevolve.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.KeyEvent;
import android.widget.TextView;

/** Test APK only. The real player's media keys are left untouched. */
public class RemoteKeyProbeActivity extends Activity {
    private ResultReceiver receiver;
    private int received;
    private int expectedCount;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        receiver = getIntent().getParcelableExtra("receiver");
        expectedCount = getIntent().getIntExtra("expectedCount", 4);
        TextView text = new TextView(this);
        text.setText("Проверка медиакнопок Air Remote");
        text.setTextSize(26);
        text.setGravity(android.view.Gravity.CENTER);
        setContentView(text);
        text.post(() -> { if (receiver != null) receiver.send(0, Bundle.EMPTY); });
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 60_000);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == 23 || code == 127 || code == 126 || code == 87 || code == 88) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (receiver != null) receiver.send(code, Bundle.EMPTY);
                if (++received == expectedCount) finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
