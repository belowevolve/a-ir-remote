package belowevolve.tvhome

import android.graphics.*
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WallpaperPicker(onBack: () -> Unit, onChosen: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val store = remember { WallpaperStore(context) }
  val density = LocalDensity.current
  val thumbnails =
    remember(density) {
      WallpaperThumbnails(with(density) { 136.dp.roundToPx() }, with(density) { 76.dp.roundToPx() })
    }
  var photos by remember { mutableStateOf(emptyList<WallpaperStore.Photo>()) }
  var focusedIndex by remember { mutableIntStateOf(0) }
  var chosenPath by remember { mutableStateOf<String?>(null) }
  var initialFocusPath by remember { mutableStateOf<String?>(null) }
  var previewPhoto by remember { mutableStateOf<File?>(null) }
  val photoRow = rememberLazyListState()
  val firstPhoto = remember { FocusRequester() }
  val inputMode = LocalInputModeManager.current
  var refreshing by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf("") }
  var revision by remember { mutableIntStateOf(0) }
  fun refresh(force: Boolean) {
    if (refreshing) return
    refreshing = true
    scope.launch {
      val result =
        withContext(Dispatchers.IO) {
          val ok = store.refresh(force)
          Triple(store.photos().toList(), ok, store.failureMessage())
        }
      photos = result.first
      if (result.second) {
        focusedIndex = 0
        initialFocusPath = photos.firstOrNull()?.file?.path
      }
      refreshing = false
      revision++
      message = if (result.second) "" else result.third
    }
  }
  LaunchedEffect(Unit) {
    val loaded =
      withContext(Dispatchers.IO) {
        store.photos().toList() to store.selected(System.currentTimeMillis() / 3600000)?.path
      }
    photos = loaded.first
    chosenPath = loaded.second
    focusedIndex = photos.indexOfFirst { it.file.path == chosenPath }.coerceAtLeast(0)
    initialFocusPath = photos.getOrNull(focusedIndex)?.file?.path
    if (photos.isEmpty()) refresh(false)
  }
  LaunchedEffect(initialFocusPath) {
    val index = photos.indexOfFirst { it.file.path == initialFocusPath }
    if (index >= 0) {
      inputMode.requestInputMode(InputMode.Keyboard)
      photoRow.scrollToItem(index)
      withFrameNanos {}
      firstPhoto.requestFocus()
    }
  }
  LaunchedEffect(photos) {
    snapshotFlow { focusedIndex to photoRow.isScrollInProgress }
      .filter { !it.second }
      .collect { (index, _) -> previewPhoto = photos.getOrNull(index)?.file }
  }
  Column(
    Modifier.fillMaxSize().padding(vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      Modifier.padding(horizontal = 32.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      TvHeader("Фоны", onBack, Modifier.weight(1f))
      TvAction(
        "Другая подборка",
        Modifier.width(240.dp),
        leading = {
          if (refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
          else Icon(painterResource(R.drawable.ic_wallpaper), null, Modifier.size(24.dp))
        },
      ) {
        refresh(true)
      }
    }
    BoxWithConstraints(
      Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
      contentAlignment = Alignment.Center,
    ) {
      val width = minOf(maxWidth, maxHeight * (16 / 9f), 640.dp)
      HomePreview(
        Modifier.width(width).aspectRatio(16 / 9f),
        previewPhoto,
        revision,
      )
    }
    Text(
      message.ifEmpty {
        photos.getOrNull(focusedIndex)?.caption?.removePrefix("Google Backdrop\n")
          ?: if (refreshing) "Загрузка…" else "Нет фотографий"
      },
      Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(24.dp),
      fontSize = 13.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    LazyRow(
      Modifier.fillMaxWidth().height(100.dp).focusGroup(),
      state = photoRow,
      contentPadding = PaddingValues(horizontal = 32.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      itemsIndexed(photos, key = { _, photo -> photo.file.path }) { index, photo ->
        @Composable
        fun card(modifier: Modifier) {
          WallpaperThumbnail(
            photo.file,
            thumbnails,
            chosenPath == photo.file.path,
            modifier.focusProperties {
              down = FocusRequester.Cancel
              if (index == 0) left = FocusRequester.Cancel
              if (index == photos.lastIndex) right = FocusRequester.Cancel
            },
            onFocus = {
              focusedIndex = index
            },
          ) {
            scope.launch {
              if (withContext(Dispatchers.IO) { store.choose(photo.file) }) onChosen()
              else refresh(false)
            }
          }
        }
        card(
          if (photo.file.path == initialFocusPath) Modifier.focusRequester(firstPhoto) else Modifier
        )
      }
    }
  }
}

@Composable
private fun WallpaperThumbnail(
  file: File,
  thumbnails: WallpaperThumbnails,
  selected: Boolean,
  modifier: Modifier,
  onFocus: () -> Unit,
  onClick: () -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  var image by remember(file, thumbnails) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(file, thumbnails) {
    image = withContext(thumbnails.dispatcher) { thumbnails.load(file) }
  }
  val shape = RoundedCornerShape(8.dp)
  val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
  Box(
    modifier
      .size(136.dp, 76.dp)
      .border(
        if (focused) 3.dp else 1.dp,
        if (focused) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline.copy(alpha = .4f),
        shape,
      )
      .onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) onFocus()
      }
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .clickable(
        interactionSource = interaction,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .semantics { contentDescription = if (selected) "Выбранный фон" else "Фон" }
  ) {
    Box(Modifier.fillMaxSize()) {
      image?.let {
        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      }
      if (selected)
        Surface(
          Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp),
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp)
          }
        }
    }
  }
}

/** One bounded decode queue and small thumbnails survive items leaving the lazy viewport. */
private class WallpaperThumbnails(private val width: Int, private val height: Int) {
  val dispatcher = Dispatchers.IO.limitedParallelism(1)
  private val cache = LruCache<String, Bitmap>(24)

  fun load(file: File): Bitmap? {
    val key = file.path + ":" + file.lastModified()
    cache.get(key)?.let {
      return it
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (
      bounds.outWidth / (options.inSampleSize * 2) >= width &&
        bounds.outHeight / (options.inSampleSize * 2) >= height
    ) options.inSampleSize *= 2
    val source = BitmapFactory.decodeFile(file.path, options) ?: return null
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
      val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
      val x = (width - source.width * scale) / 2
      val y = (height - source.height * scale) / 2
      Canvas(image)
        .drawBitmap(
          source,
          null,
          RectF(x, y, width - x, height - y),
          Paint(Paint.FILTER_BITMAP_FLAG),
        )
    } finally {
      source.recycle()
    }
    cache.put(key, image)
    return image
  }
}
