package belowevolve.airremote

import android.content.Context

/** Explicit per-TV opt-in: never assume that a paired Android TV has our launcher. */
internal object VoiceTvFeatures {
    fun airHome(context: Context, host: String): Boolean =
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).getBoolean("air_home:$host", false)

    fun setAirHome(context: Context, host: String, enabled: Boolean) {
        require(host.isNotBlank())
        context.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putBoolean("air_home:$host", enabled).apply()
    }
}
