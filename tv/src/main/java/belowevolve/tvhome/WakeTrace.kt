package belowevolve.tvhome

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.io.PrintWriter
import org.json.JSONArray
import org.json.JSONObject

/* Bounded persistent event trail. No sampling timer, wake lock, network or extra service. */
object WakeTrace {
    private fun store() =
        if (android.app.Application.getProcessName().endsWith(":keys")) "wake_trace_keys"
        else "wake_trace"

    @Synchronized
    fun record(context: Context, event: String) {
        try {
            val p: android.content.SharedPreferences = context.getSharedPreferences(store(), 0)
            val old: JSONArray = JSONArray(p.getString("events", "[]"))
            val next: JSONArray = JSONArray()
            for (i: Int in Math.max(0, old.length() - 31) until old.length()) next.put(old.get(i))
            next.put(
                JSONObject()
                    .put("event", event)
                    .put("time", System.currentTimeMillis())
                    .put("elapsed", SystemClock.elapsedRealtime())
                    .put(
                        "interactive",
                        context
                            .getSystemService<PowerManager>(PowerManager::class.java)
                            .isInteractive(),
                    )
            )
            p.edit().putString("events", next.toString()).apply()
        } catch (unavailable: Exception) {
            android.util.Log.w("AirWake", "Trace unavailable")
        }
    }

    fun dump(c: Context, prefix: String, out: PrintWriter) {
        out.println(
            (prefix +
                "AIRHOME_WAKE " +
                c.getSharedPreferences(store(), 0).getString("events", "[]"))
        )
    }
}
