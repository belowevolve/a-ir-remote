package belowevolve.airremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** SDK-only runner: safe to load against an R8-minified production APK. */
public final class ScreenPreviewRunner extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            for (String key : new String[] {
                    "clipboardFixture", "tvHost", "tvSubmit", "tvRawKey", "tvRawLaunchURI", "tvRawDelayMs" }) {
                if (arguments.containsKey(key)) {
                    throw new IllegalArgumentException("Screen previews cannot be combined with functional probes");
                }
            }
            new ScreenPreviewCapture(this).capture(arguments.getString("screenPreview"), result);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            // No view-tree dumps, clipboard contents, or application state in diagnostic output.
            result.putString("stream", "SCREEN_PREVIEW failed: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
