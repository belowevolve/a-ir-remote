package belowevolve.tvhome

import android.content.*
import android.media.tv.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import belowevolve.shared.design.RemoteIcons
import belowevolve.shared.design.RemotePalette
import kotlinx.coroutines.*

class SettingsActivity : QuietActivity() {
  private var page by mutableStateOf("main")
  private var colorCode by mutableIntStateOf(183)

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    Ui.fullscreen(this)
    route()
    setContent { HomeTheme { SettingsScreen() } }
  }

  override fun onNewIntent(value: Intent) {
    super.onNewIntent(value)
    intent = value
    route()
  }

  private fun route() {
    page =
      intent.getStringExtra("section")?.takeIf {
        it in listOf("inputs", "apps", "wallpapers")
      } ?: "main"
  }

  override fun onRemoteBack() {
    if (page.startsWith("key:")) page = "keys" else if (page != "main") page = "main" else finish()
  }

  override fun dispatchKeyEvent(e: KeyEvent): Boolean {
    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Actions.color(this, e.keyCode))
      return true
    return super.dispatchKeyEvent(e)
  }

  private fun label(action: String): String =
    when (action) {
      "hdmi-toggle" -> "Переключить HDMI / Главная"
      "next-wallpaper" -> "Следующий фон"
      "inputs" -> "Выбрать HDMI"
      "mov.film" -> "film.mov"
      Actions.VIDEO_APP -> "TizenTube"
      "settings" -> "Настройки Air Home"
      "none" -> "Без действия"
      else ->
        runCatching {
            packageManager
              .getApplicationLabel(packageManager.getApplicationInfo(action, 0))
              .toString()
          }
          .getOrDefault(action)
    }

  @Composable
  private fun SettingsScreen() {
    var apps by remember { mutableStateOf(emptyList<AppCatalog.App>()) }
    LaunchedEffect(page) {
      if (page == "apps" || page.startsWith("key:"))
        apps = withContext(Dispatchers.IO) { AppCatalog.load(this@SettingsActivity) }
    }
    val inputs = if (page == "inputs") rememberHdmiChoices() else emptyList()
    val preferredInput = remember(page) { inputs.getOrNull(preferredHdmiIndex(inputs))?.id }
    val names = listOf("Красная", "Зелёная", "Жёлтая", "Синяя")
    val title =
      when (page) {
        "main" -> "Настройки"
        "inputs" -> "Входы HDMI"
        "apps" -> "Приложения"
        "keys" -> "Кнопки пульта"
        "wallpapers" -> "Фоны"
        else -> names[colorCode - 183]
      }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        if (page == "wallpapers")
          WallpaperPicker({ onRemoteBack() }) {
            startActivity(
              Actions.homeIntent(this@SettingsActivity).putExtra("wallpaper_changed", true)
            )
            finish()
          }
        else
          Column(
            Modifier.widthIn(max = 640.dp)
              .fillMaxHeight()
              .align(Alignment.TopCenter)
              .padding(top = 24.dp)
          ) {
            TvHeader(title, { onRemoteBack() })
            Spacer(Modifier.height(8.dp))
            key(page) {
              LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
              ) {
                when (page) {
                  "main" -> {
                    val entries =
                      listOf(
                        Triple("Входы HDMI", R.drawable.ic_hdmi, "inputs"),
                        Triple(
                          "Кнопки пульта",
                          R.drawable.ic_remote,
                          "keys",
                        ),
                        Triple(
                          "Фоны",
                          R.drawable.ic_wallpaper,
                          "wallpapers",
                        ),
                        Triple("Тема", R.drawable.ic_palette, "theme"),
                        Triple("Приложения", R.drawable.ic_apps, "apps"),
                        Triple(
                          "Настройки Android TV",
                          RemoteIcons.Settings,
                          "android",
                        ),
                      )
                    items(entries) { (text, icon, target) ->
                      @Composable
                      fun action(modifier: Modifier) {
                        TvAction(text, modifier.fillMaxWidth(), icon) {
                          when (target) {
                            "theme" ->
                              startActivity(
                                Intent(
                                  this@SettingsActivity,
                                  ThemeActivity::class.java,
                                )
                              )
                            "android" -> runCatching {
                                startActivity(Intent("android.settings.SETTINGS"))
                              }
                                .onFailure {
                                  Ui.toast(
                                    this@SettingsActivity,
                                    "Настройки недоступны",
                                  )
                                }
                            else -> page = target
                          }
                        }
                      }
                      if (target == "inputs") FocusFirst { action(it) } else action(Modifier)
                    }
                  }
                  "inputs" -> {
                    items(inputs, key = { it.id }) { input ->
                      @Composable
                      fun action(modifier: Modifier) {
                        TvAction(
                          input.label,
                          modifier.fillMaxWidth(),
                          trailingText = if (input.last) "Последний" else null,
                          leading = {
                            Box(Modifier.size(24.dp)) {
                              Icon(
                                androidx.compose.ui.res.painterResource(R.drawable.ic_hdmi),
                                if (input.connected) "Есть сигнал" else "Нет сигнала",
                                Modifier.fillMaxSize(),
                              )
                              Box(
                                Modifier.align(Alignment.BottomEnd)
                                  .size(8.dp)
                                  .background(
                                    if (input.connected) Color(RemotePalette.Green)
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    CircleShape,
                                  )
                                  .border(1.dp, LocalContentColor.current, CircleShape)
                              )
                            }
                          },
                        ) {
                          startActivity(HdmiActivity.intent(this@SettingsActivity, input.id))
                        }
                      }
                      if (input.id == preferredInput) FocusFirst { action(it) }
                      else action(Modifier)
                    }
                  }
                  "keys" ->
                    items(names) { name ->
                      val code = 183 + names.indexOf(name)
                      @Composable
                      fun action(modifier: Modifier) {
                        TvAction(
                          label(Actions.binding(this@SettingsActivity, code)),
                          modifier.fillMaxWidth(),
                          R.drawable.ic_palette,
                          trailingText = name,
                          iconTint =
                            Color(
                              listOf(
                                RemotePalette.Red,
                                RemotePalette.Green,
                                RemotePalette.Yellow,
                                RemotePalette.Blue,
                              )[code - 183]
                            ),
                        ) {
                          colorCode = code
                          page = "key:$code"
                        }
                      }
                      if (code == 183) FocusFirst { action(it) } else action(Modifier)
                    }
                  else -> {
                    if (page.startsWith("key:")) {
                      val actions =
                        listOf(
                          "hdmi-toggle",
                          "inputs",
                          "next-wallpaper",
                          "settings",
                          "none",
                        )
                      items(actions) { action ->
                        @Composable
                        fun entry(modifier: Modifier) {
                          TvAction(
                            label(action),
                            modifier.fillMaxWidth(),
                            RemoteIcons.Tune,
                            selected =
                              Actions.binding(
                                this@SettingsActivity,
                                colorCode,
                              ) == action,
                          ) {
                            getSharedPreferences("home", 0)
                              .edit()
                              .putString("key_$colorCode", action)
                              .apply()
                            page = "keys"
                          }
                        }
                        if (action == actions.first()) FocusFirst { entry(it) } else entry(Modifier)
                      }
                    }
                    items(apps, key = { it.packageName }) { app ->
                      @Composable
                      fun entry(modifier: Modifier) {
                        TvAction(
                          app.label,
                          modifier = modifier.fillMaxWidth(),
                          leading = { AppIcon(app.icon, Modifier.size(24.dp)) },
                          onClick = {
                            if (page.startsWith("key:")) {
                              getSharedPreferences("home", 0)
                                .edit()
                                .putString(
                                  "key_$colorCode",
                                  app.packageName,
                                )
                                .apply()
                              page = "keys"
                            } else
                              Actions.run(
                                this@SettingsActivity,
                                app.packageName,
                              )
                          },
                        )
                      }
                      if (page == "apps" && app == apps.firstOrNull()) FocusFirst { entry(it) }
                      else entry(Modifier)
                    }
                  }
                }
              }
            }
          }
      }
    }
  }

  companion object {
    fun vpnOn(c: Context): Boolean = runCatching {
      val manager = c.getSystemService(ConnectivityManager::class.java)
      manager.allNetworks.any {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
      }
    }
      .getOrDefault(false)
  }
}
