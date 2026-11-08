package belowevolve.tvhome

import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal data class HdmiChoice(
  val id: String,
  val label: String,
  val connected: Boolean,
  val last: Boolean,
)

internal fun preferredHdmiIndex(inputs: List<HdmiChoice>): Int =
  inputs.indexOfFirst { it.last && it.connected }.takeIf { it >= 0 }
    ?: inputs.indexOfFirst { it.connected }.takeIf { it >= 0 }
    ?: inputs.indexOfFirst { it.last }.takeIf { it >= 0 }
    ?: 0

@Composable
internal fun rememberHdmiChoices(): List<HdmiChoice> {
  val context = LocalContext.current
  val manager = remember { context.getSystemService(TvInputManager::class.java) }
  val preferences = remember { context.getSharedPreferences("home", 0) }
  var revision by remember { mutableIntStateOf(0) }
  DisposableEffect(manager, preferences) {
    val callback =
      object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(id: String, state: Int) {
          revision++
        }

        override fun onInputAdded(id: String) {
          revision++
        }

        override fun onInputRemoved(id: String) {
          revision++
        }

        override fun onInputUpdated(id: String) {
          revision++
        }
      }
    val listener =
      android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last_hdmi") revision++
      }
    manager.registerCallback(callback, Handler(Looper.getMainLooper()))
    preferences.registerOnSharedPreferenceChangeListener(listener)
    onDispose {
      manager.unregisterCallback(callback)
      preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
  }
  return remember(revision) {
    val last = preferences.getString("last_hdmi", null)
    manager.tvInputList
      .filter { it.type == TvInputInfo.TYPE_HDMI }
      .sortedBy { it.id }
      .map {
        HdmiChoice(
          it.id,
          (it.loadCustomLabel(context) ?: it.loadLabel(context)).toString(),
          runCatching { manager.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED }
            .getOrDefault(false),
          it.id == last,
        )
      }
  }
}
