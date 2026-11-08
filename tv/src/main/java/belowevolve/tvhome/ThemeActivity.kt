package belowevolve.tvhome

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                                    "Компоновка: ${HomeLayout.NAMES[layout.mode]}",
                                    it.fillMaxWidth(),
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
                                "Подпись: ${HomeLayout.CAPTIONS[layout.captionIndex()]}",
                                Modifier.fillMaxWidth(),
                            ) {
                                set(
                                    "caption_style",
                                    HomeLayout.CAPTION_IDS[
                                            (layout.captionIndex() + 1) % HomeLayout.CAPTIONS.size],
                                )
                            }
                        }
                        item {
                            TvAction(
                                "Анимация: ${HomeLayout.MOTIONS[layout.motion]}",
                                Modifier.fillMaxWidth(),
                            ) {
                                set("card_motion", (layout.motion + 1) % HomeLayout.MOTIONS.size)
                            }
                        }
                        items(2) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (column in 0..1) {
                                    val mode = row * 2 + column
                                    TvAction(
                                        "${mode+1} · ${BackdropStyle.NAMES[mode]}",
                                        Modifier.weight(1f),
                                        selected = style.mode == mode,
                                    ) {
                                        set("mode", mode)
                                    }
                                }
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
    Column(Modifier.fillMaxWidth()) {
        Text("$label: $value", fontSize = 15.sp)
        Slider(
            value.toFloat(),
            { set(key, (min + ((it - min) / step).toInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).coerceAtLeast(0),
        )
    }
}
