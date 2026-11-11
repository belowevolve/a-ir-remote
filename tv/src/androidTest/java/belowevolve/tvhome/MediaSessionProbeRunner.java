package belowevolve.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** SDK-only inspection/control of the actual selected media session. Does not launch another app. */
public final class MediaSessionProbeRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            MediaSessionManager manager = getTargetContext().getSystemService(MediaSessionManager.class);
            MediaController selected = null;
            for (MediaController c : manager.getActiveSessions(new ComponentName("belowevolve.tvhome", "belowevolve.tvhome.MediaAccessService")))
                if (c.getPackageName().equals(args.getString("package"))) { selected = c; break; }
            if (selected == null) throw new AssertionError("No active session for " + args.getString("package"));
            String mode = args.getString("mode", "snapshot");
            if (mode.equals("pause")) { selected.getTransportControls().pause(); SystemClock.sleep(400); }
            if (mode.equals("play")) { selected.getTransportControls().play(); SystemClock.sleep(400); }
            if (mode.equals("seek")) { selected.getTransportControls().seekTo(Long.parseLong(args.getString("positionMs"))); SystemClock.sleep(800); }
            PlaybackState state = selected.getPlaybackState();
            MediaMetadata metadata = selected.getMetadata();
            JSONObject json = new JSONObject().put("package", selected.getPackageName()).put("state", state.getState())
                .put("positionMs", state.getPosition()).put("updatedAt", state.getLastPositionUpdateTime())
                .put("now", SystemClock.elapsedRealtime()).put("speed", state.getPlaybackSpeed()).put("actions", state.getActions())
                .put("durationMs", metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            result.putString("stream", json.toString() + "\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
