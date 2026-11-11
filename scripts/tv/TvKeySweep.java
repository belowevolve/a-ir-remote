import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** Shell-only DPAD input; one process avoids adb startup between key presses. */
public final class TvKeySweep {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        String pattern = args[0];
        int interval = Integer.parseInt(args[1]);
        int rounds = Integer.parseInt(args[2]);
        long next = SystemClock.uptimeMillis();
        for (int round = 0; round < rounds; round++) {
            for (char key : pattern.toCharArray()) {
                int code = key == 'R' ? 22 : key == 'L' ? 21 : key == 'U' ? 19 : 20;
                long now = SystemClock.uptimeMillis();
                inject.invoke(manager, new KeyEvent(now, now, 0, code, 0, 0, -1, 0, 0,
                        InputDevice.SOURCE_DPAD), 1);
                inject.invoke(manager, new KeyEvent(now, now, 1, code, 0, 0, -1, 0, 0,
                        InputDevice.SOURCE_DPAD), 1);
                next += interval;
                SystemClock.sleep(Math.max(0, next - SystemClock.uptimeMillis()));
            }
        }
        SystemClock.sleep(500);
    }
}
