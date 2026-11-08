package belowevolve.tvhome

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belowevolve.shared.compose.AirButton

class ThemeActivity : QuietActivity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    Ui.fullscreen(this)
    setContent { HomeTheme { ThemeScreen() } }
  }

  @Composable
  private fun ThemeScreen() {
    var revision by remember { mutableIntStateOf(0) }
    fun set(key: String, value: Int) {
      getSharedPreferences("appearance", 0).edit().putInt(key, value).apply()
      revision++
    }
    val layout = remember(revision) { HomeLayout(this) }
    val style = remember(revision) { BackdropStyle(this) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.fillMaxSize().padding(32.dp, 24.dp)) {
        TvHeader("Тема", { finish() })
        Row(
          Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
          HomePreview(Modifier.weight(1f).aspectRatio(16 / 9f), revision = revision)
          LazyColumn(
            Modifier.width(340.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
          ) {
            item {
              FocusFirst {
                TvAction(
                  "Компоновка",
                  it.fillMaxWidth(),
                  trailingText = HomeLayout.NAMES[layout.mode],
                ) {
                  set("layout", 1 - layout.mode)
                }
              }
            }
            if (layout.mode == 1)
              item {
                ThemeSlider(
                  "Отступ снизу",
                  "bottom_inset",
                  layout.bottom,
                  24,
                  160,
                  4,
                  ::set,
                )
              }
            item {
              TvAction(
                "Подпись",
                Modifier.fillMaxWidth(),
                trailingText = HomeLayout.CAPTIONS[layout.captionIndex()],
              ) {
                set(
                  "caption_style",
                  HomeLayout.CAPTION_IDS[(layout.captionIndex() + 1) % HomeLayout.CAPTIONS.size],
                )
              }
            }
            item {
              TvAction(
                "Анимация",
                Modifier.fillMaxWidth(),
                trailingText = HomeLayout.MOTIONS[layout.motion],
              ) {
                set("card_motion", (layout.motion + 1) % HomeLayout.MOTIONS.size)
              }
            }
            item {
              TvAction(
                "Обработка фона",
                Modifier.fillMaxWidth(),
                trailingText = BackdropStyle.NAMES[style.mode],
              ) {
                set("mode", (style.mode + 1) % BackdropStyle.NAMES.size)
              }
            }
            when (style.mode) {
              0 ->
                item {
                  ThemeSlider("Затемнение", "dim", style.dim, 0, 90, 5, ::set)
                }
              1 -> {
                item {
                  ThemeSlider(
                    "Высота от нижнего края",
                    "reach",
                    style.reach,
                    5,
                    100,
                    5,
                    ::set,
                  )
                }
                item {
                  ThemeSlider(
                    "Затемнение внизу",
                    "gradient_dim",
                    style.gradientDim,
                    0,
                    100,
                    5,
                    ::set,
                  )
                }
              }
              2 -> {
                item {
                  TvAction(
                    "Тень: ${listOf("Мягкая","Широкая","По постеру")[style.mat]}",
                    Modifier.fillMaxWidth(),
                  ) {
                    set("mat", (style.mat + 1) % 3)
                  }
                }
                item {
                  ThemeSlider(
                    "Сила тени",
                    "mat_opacity",
                    style.matOpacity,
                    20,
                    100,
                    5,
                    ::set,
                  )
                }
                item {
                  ThemeSlider(
                    "Затемнение фотографии",
                    "mat_dim",
                    style.matDim,
                    0,
                    90,
                    5,
                    ::set,
                  )
                }
              }
              3 -> {
                item {
                  ThemeSlider("Размытие", "blur", style.blur, 0, 100, 5, ::set)
                }
                item {
                  ThemeSlider(
                    "Затемнение полосы",
                    "blur_dim",
                    style.blurDim,
                    0,
                    90,
                    5,
                    ::set,
                  )
                }
                item {
                  ThemeSlider(
                    "Высота полосы",
                    "blur_height",
                    style.blurHeight,
                    10,
                    80,
                    5,
                    ::set,
                  )
                }
                item {
                  ThemeSlider(
                    "Мягкость краёв",
                    "feather",
                    style.feather,
                    0,
                    50,
                    5,
                    ::set,
                  )
                }
              }
            }
            item {
              TvAction("Сбросить этот режим", Modifier.fillMaxWidth()) {
                val keys =
                  listOf(
                    listOf("dim"),
                    listOf("reach", "gradient_dim"),
                    listOf("mat", "mat_dim", "mat_opacity"),
                    listOf("blur", "blur_dim", "blur_height", "feather"),
                  )
                val edit = getSharedPreferences("appearance", 0).edit()
                keys[style.mode].forEach { edit.remove(it) }
                edit.apply()
                revision++
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ThemeSlider(
  label: String,
  key: String,
  value: Int,
  min: Int,
  max: Int,
  step: Int,
  set: (String, Int) -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  AirButton(
    onClick = {},
    modifier =
      Modifier.fillMaxWidth().height(96.dp).onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) false
        else {
          if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN)
            set(
              key,
              (value + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(min, max),
            )
          true
        }
      },
    onFocus = { focused = it },
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Column(Modifier.fillMaxWidth()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        Text(value.toString(), fontSize = 16.sp)
      }
      val tint =
        if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
      Slider(
        value.toFloat(),
        { set(key, (min + ((it - min) / step).toInt() * step).coerceIn(min, max)) },
        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step - 1).coerceAtLeast(0),
        colors =
          SliderDefaults.colors(
            thumbColor = tint,
            activeTrackColor = tint,
            inactiveTrackColor = tint.copy(alpha = .25f),
          ),
      )
    }
  }
}
