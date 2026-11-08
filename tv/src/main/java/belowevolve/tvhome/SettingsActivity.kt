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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belowevolve.shared.compose.AirButton
import belowevolve.shared.design.RemoteIcons
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
        if (page.startsWith("key:")) page = "keys"
        else if (page != "main") page = "main" else finish()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (
            e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Actions.color(this, e.keyCode)
        )
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
        val scope = rememberCoroutineScope()
        var apps by remember { mutableStateOf(emptyList<AppCatalog.App>()) }
        LaunchedEffect(page) {
            if (page == "apps" || page.startsWith("key:"))
                apps = withContext(Dispatchers.IO) { AppCatalog.load(this@SettingsActivity) }
        }
        var revision by remember { mutableIntStateOf(0) }
        var photos by remember { mutableStateOf(emptyList<WallpaperStore.Photo>()) }
        var selectedPhoto by remember { mutableIntStateOf(0) }
        var refreshing by remember { mutableStateOf(false) }
        var photoMessage by remember { mutableStateOf("") }
        fun refresh(force: Boolean) {
            if (refreshing) return
            refreshing = true
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        val store = WallpaperStore(this@SettingsActivity)
                        val ok = store.refresh(force)
                        Triple(store.photos().toList(), ok, store.failureMessage())
                    }
                photos = result.first
                selectedPhoto = 0
                refreshing = false
                revision++
                photoMessage = if (result.second) "" else result.third
            }
        }
        LaunchedEffect(page) {
            if (page == "wallpapers") {
                photos =
                    withContext(Dispatchers.IO) { WallpaperStore(this@SettingsActivity).photos() }
                if (photos.isEmpty()) refresh(false)
            }
        }
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
                    Column(
                        Modifier.fillMaxSize().padding(32.dp, 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TvHeader(title, { onRemoteBack() }, Modifier.weight(1f))
                            TvAction(
                                if (refreshing) "Подбираю…" else "Другая подборка",
                                Modifier.width(208.dp),
                                enabled = !refreshing,
                            ) {
                                refresh(true)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HomePreview(
                            Modifier.width(512.dp).aspectRatio(16 / 9f),
                            photos.getOrNull(selectedPhoto)?.file,
                            revision,
                        )
                        Text(
                            photos.getOrNull(selectedPhoto)?.caption
                                ?: photoMessage.ifEmpty {
                                    if (refreshing) "Загрузка…" else "Нет фотографий"
                                },
                            Modifier.padding(8.dp).heightIn(min = 28.dp),
                            fontSize = 12.sp,
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(photos, key = { it.file.path }) { photo ->
                                val index = photos.indexOf(photo)
                                val focusModifier = Modifier.size(128.dp, 72.dp)
                                @Composable
                                fun card(modifier: Modifier) {
                                    AirButton(
                                        onClick = {
                                            scope.launch {
                                                val chosen =
                                                    withContext(Dispatchers.IO) {
                                                        WallpaperStore(this@SettingsActivity)
                                                            .choose(photo.file)
                                                    }
                                                if (chosen) {
                                                    startActivity(
                                                        Actions.homeIntent(this@SettingsActivity)
                                                            .putExtra("wallpaper_changed", true)
                                                    )
                                                    finish()
                                                } else refresh(false)
                                            }
                                        },
                                        modifier = modifier,
                                        contentPadding = PaddingValues(0.dp),
                                        onFocus = { if (it) selectedPhoto = index },
                                    ) {
                                        PhotoThumbnail(photo.file, Modifier.fillMaxSize())
                                    }
                                }
                                if (index == 0) FocusFirst(focusModifier) { card(it) }
                                else card(focusModifier)
                            }
                        }
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
                                                        "android" ->
                                                            runCatching {
                                                                    startActivity(
                                                                        Intent(
                                                                            "android.settings.SETTINGS"
                                                                        )
                                                                    )
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
                                            if (target == "inputs") FocusFirst { action(it) }
                                            else action(Modifier)
                                        }
                                    }
                                    "inputs" -> {
                                        val manager = getSystemService(TvInputManager::class.java)
                                        val last =
                                            getSharedPreferences("home", 0)
                                                .getString("last_hdmi", null)
                                        val inputs =
                                            manager.tvInputList
                                                .filter { it.type == TvInputInfo.TYPE_HDMI }
                                                .sortedBy { it.id }
                                        items(inputs, key = { it.id }) { input ->
                                            @Composable
                                            fun action(modifier: Modifier) {
                                                TvAction(
                                                    input
                                                        .loadCustomLabel(this@SettingsActivity)
                                                        ?.toString()
                                                        ?: input
                                                            .loadLabel(this@SettingsActivity)
                                                            .toString(),
                                                    modifier.fillMaxWidth(),
                                                    R.drawable.ic_hdmi,
                                                    (if (input.id == last) "Последний · " else "") +
                                                        (if (
                                                            manager.getInputState(input.id) ==
                                                                TvInputManager.INPUT_STATE_CONNECTED
                                                        )
                                                            "Есть сигнал"
                                                        else "Нет сигнала"),
                                                ) {
                                                    startActivity(
                                                        HdmiActivity.intent(
                                                            this@SettingsActivity,
                                                            input.id,
                                                        )
                                                    )
                                                }
                                            }
                                            if (input == inputs.firstOrNull())
                                                FocusFirst { action(it) }
                                            else action(Modifier)
                                        }
                                    }
                                    "keys" ->
                                        items(names) { name ->
                                            val code = 183 + names.indexOf(name)
                                            @Composable
                                            fun action(modifier: Modifier) {
                                                TvAction(
                                                    name,
                                                    modifier.fillMaxWidth(),
                                                    R.drawable.ic_palette,
                                                    label(
                                                        Actions.binding(this@SettingsActivity, code)
                                                    ),
                                                ) {
                                                    colorCode = code
                                                    page = "key:$code"
                                                }
                                            }
                                            if (code == 183) FocusFirst { action(it) }
                                            else action(Modifier)
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
                                                if (action == actions.first())
                                                    FocusFirst { entry(it) }
                                                else entry(Modifier)
                                            }
                                        }
                                        items(apps, key = { it.packageName }) { app ->
                                            @Composable
                                            fun entry(modifier: Modifier) {
                                                AirButton(
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
                                                    modifier = modifier.fillMaxWidth(),
                                                ) {
                                                    AppIcon(app.icon, Modifier.size(32.dp))
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(app.label, fontSize = 18.sp)
                                                }
                                            }
                                            if (page == "apps" && app == apps.firstOrNull())
                                                FocusFirst { entry(it) }
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
                manager
                    .getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }
            .getOrDefault(false)
    }
}

@Composable
private fun PhotoThumbnail(file: java.io.File, modifier: Modifier) {
    var image by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(file) {
        image =
            withContext(Dispatchers.IO) {
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                while (
                    bounds.outWidth / (options.inSampleSize * 2) >= 256 &&
                        bounds.outHeight / (options.inSampleSize * 2) >= 144
                ) options.inSampleSize *= 2
                android.graphics.BitmapFactory.decodeFile(file.path, options)
            }
    }
    image?.let {
        androidx.compose.foundation.Image(
            it.asImageBitmap(),
            null,
            modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}
