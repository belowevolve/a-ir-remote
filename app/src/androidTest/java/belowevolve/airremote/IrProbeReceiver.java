package belowevolve.airremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.ConsumerIrManager;

/** One DUMP-protected, explicitly requested reference pulse; no target APK dependencies. */
public final class IrProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ConsumerIrManager ir = context.getSystemService(ConsumerIrManager.class);
        if (ir == null || !ir.hasIrEmitter()) { setResultData("No IR emitter"); return; }
        int[] pattern = new int[67]; int offset = 0;
        pattern[offset++] = 9000; pattern[offset++] = 4500;
        for (int b : new int[]{0x04, 0xfb, 0x08, 0xf7}) {
            for (int bit = 0; bit < 8; bit++) {
                pattern[offset++] = 560;
                pattern[offset++] = (b & (1 << bit)) == 0 ? 560 : 1690;
            }
        }
        pattern[offset] = 560;
        long before = System.currentTimeMillis();
        ir.transmit(38000, pattern);
        setResultCode(-1);
        setResultData("IR power before=" + before + " after=" + System.currentTimeMillis());
    }
}
