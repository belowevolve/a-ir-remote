package belowevolve.tvhome

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
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
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import belowevolve.shared.compose.AirButton
import belowevolve.shared.design.PosterBadgeSpec
import belowevolve.shared.design.RemoteIcons
import java.io.File
import kotlinx.coroutines.*

internal data class PosterArt(
  val program: ProgramRepository.Program,
  val bitmap: Bitmap? = null,
  val clean: Boolean = false,
  val accent: Int = -0x655e56,
)

@Composable
internal fun HomeTheme(theme: WallpaperTheme? = null, content: @Composable () -> Unit) {
  val colors =
    theme?.colors
      ?: intArrayOf(Ui.BG, Ui.TEXT, Ui.MUTED, Ui.PANEL, Ui.PRIMARY, Ui.ON_PRIMARY, Ui.PRESSED)
  val badgeColor = remember(colors[4]) { WallpaperTheme.badgeColor(colors[4]) }
  MaterialTheme(
    colorScheme =
      darkColorScheme(
        background = Color(colors[0]),
        onBackground = Color(colors[1]),
        surface = Color(colors[0]),
        onSurface = Color(colors[1]),
        onSurfaceVariant = Color(colors[2]),
        surfaceContainer = Color(colors[3]),
        surfaceContainerHigh = Color(colors[3]),
        primary = Color(colors[4]),
        onPrimary = Color(colors[5]),
        primaryContainer = Color(badgeColor),
        onPrimaryContainer = Color.White,
        outline = Color(colors[2]),
      )
  ) {
    CompositionLocalProvider(LocalContentColor provides Color(colors[1]), content = content)
  }
}

@Composable
internal fun TvAction(
  label: String,
  modifier: Modifier = Modifier,
  icon: Int? = null,
  trailingText: String? = null,
  selected: Boolean = false,
  enabled: Boolean = true,
  onFocus: (Boolean) -> Unit = {},
  iconTint: Color = Color.Unspecified,
  leading: (@Composable () -> Unit)? = null,
  onClick: () -> Unit,
) {
  AirButton(
    onClick,
    modifier.height(56.dp),
    enabled = enabled,
    selected = selected,
    onFocus = onFocus,
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
  ) {
    if (leading != null || icon != null) {
      if (leading != null) leading()
      else
        Icon(
          painterResource(icon!!),
          null,
          Modifier.size(24.dp),
          tint = if (iconTint == Color.Unspecified) LocalContentColor.current else iconTint,
        )
      Spacer(Modifier.width(12.dp))
    }
    Text(
      label,
      Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      fontSize = 18.sp,
    )
    if (trailingText != null) {
      Spacer(Modifier.width(12.dp))
      Text(
        trailingText,
        Modifier.widthIn(max = 180.dp),
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
internal fun TvHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
    AirButton(
      onBack,
      Modifier.size(44.dp),
      shape = CircleShape,
      contentPadding = PaddingValues(10.dp),
    ) {
      Icon(painterResource(RemoteIcons.Back), "Назад", Modifier.size(20.dp))
    }
    Text(
      title,
      Modifier.padding(start = 16.dp),
      fontSize = 24.sp,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
internal fun FocusFirst(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
  val focus = remember { FocusRequester() }
  val inputMode = LocalInputModeManager.current
  LaunchedEffect(Unit) {
    inputMode.requestInputMode(InputMode.Keyboard)
    withFrameNanos {}
    focus.requestFocus()
  }
  content(modifier.focusRequester(focus))
}

@Composable
internal fun PosterBadgesContent(image: String, modifier: Modifier = Modifier) {
  val metadata = remember(image) { PosterBadges.metadata(image) } ?: return
  val episode = PosterBadgeSpec.episode(metadata.optString("season"), metadata.optString("mark"))
  val news = metadata.optString("news")
  val badgeStyle =
    TextStyle(
      fontSize = PosterBadgeSpec.TEXT_SP.sp,
      lineHeight = PosterBadgeSpec.LINE_SP.sp,
      letterSpacing = .44.sp,
      fontWeight = FontWeight.Medium,
      platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
  @Composable
  fun chip(text: String) {
    Text(
      text,
      Modifier.background(
          MaterialTheme.colorScheme.primaryContainer,
          RoundedCornerShape(PosterBadgeSpec.CORNER_DP.dp),
        )
        .padding(PosterBadgeSpec.PADDING_X_DP.dp, PosterBadgeSpec.PADDING_Y_DP.dp),
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      style = badgeStyle,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  Box(modifier.padding(5.dp)) {
    if (episode.isNotEmpty()) Box(Modifier.align(Alignment.TopStart)) { chip(episode) }
    if (news.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd)) { chip(news) }
  }
}

@Composable
internal fun PosterCard(
  art: PosterArt,
  style: BackdropStyle,
  modifier: Modifier = Modifier,
  motion: Int = 0,
  preview: Boolean = false,
  previewFocused: Boolean = false,
  pivot: Float = .5f,
  onFocus: () -> Unit = {},
  onClick: () -> Unit = {},
) {
  var focused by remember { mutableStateOf(false) }
  val active = if (preview) previewFocused else focused
  val scale by
    animateFloatAsState(
      if (active) CardMotion.scale(motion) else 1f,
      tween(
        CardMotion.duration(motion, active),
        easing = Easing { CardMotion.interpolator(motion, active).getInterpolation(it) },
      ),
      label = "poster focus",
    )
  val shape = RoundedCornerShape(12.dp)
  val image = art.bitmap
  val accent = Color(art.accent)
  val elevation =
    if (active) 24.dp else if (style.mode == 2) (if (style.mat == 1) 32.dp else 20.dp) else 0.dp
  val interaction = remember {
    androidx.compose.foundation.interaction.MutableInteractionSource()
  }
  Box(
    modifier
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(pivot, 1f)
        shadowElevation = elevation.toPx()
        this.shape = shape
        ambientShadowColor = accent
        spotShadowColor = accent
      }
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .then(
        if (preview) Modifier
        else
          Modifier.onFocusChanged {
              focused = it.isFocused
              if (it.isFocused) onFocus()
            }
            .clickable(
              interactionSource = interaction,
              indication = null,
              onClick = onClick,
            )
      )
      .semantics { contentDescription = art.program.title }
  ) {
    if (image != null && !image.isRecycled)
      Image(
        image.asImageBitmap(),
        null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        colorFilter = if (active) null else ColorFilter.tint(Color(0xffe1e1e1), BlendMode.Modulate),
      )
    if (art.clean) PosterBadgesContent(art.program.image, Modifier.fillMaxSize())
  }
}

@Composable
internal fun HomeContent(
  wallpaper: Bitmap?,
  posters: List<PosterArt>,
  clock: String,
  vpn: Boolean,
  modifier: Modifier = Modifier,
  preview: Boolean = false,
  appearance: Int = 0,
  focusRequest: Int = 0,
  onPosterFocus: (Int) -> Unit = {},
) {
  val context = LocalContext.current
  val layout = remember(context, appearance) { HomeLayout(context) }
  val style = remember(context, appearance) { BackdropStyle(context) }
  var selected by remember { mutableIntStateOf(0) }
  val row = rememberLazyListState()
  val first = remember { FocusRequester() }
  val settings = remember { FocusRequester() }
  val inputMode = LocalInputModeManager.current
  LaunchedEffect(focusRequest, posters.size) {
    if (!preview) {
      withFrameNanos {}
      inputMode.requestInputMode(InputMode.Keyboard)
      if (posters.isNotEmpty()) {
        selected = selected.coerceAtMost(posters.lastIndex)
        row.scrollToItem(selected)
        withFrameNanos {}
        first.requestFocus()
      } else settings.requestFocus()
    }
  }
  Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (wallpaper != null && !wallpaper.isRecycled)
      Image(
        wallpaper.asImageBitmap(),
        null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    Row(
      Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 32.dp).height(48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      @Composable
      fun action(
        icon: Int,
        description: String,
        extra: Modifier = Modifier,
        active: Boolean = false,
        click: () -> Unit,
      ) {
        var focused by remember { mutableStateOf(false) }
        @Composable
        fun iconContent() {
          Icon(
            painterResource(icon),
            description,
            Modifier.size(22.dp),
            tint =
              if (active && !focused) MaterialTheme.colorScheme.primary
              else LocalContentColor.current,
          )
        }
        if (preview)
          Surface(
            Modifier.size(40.dp),
            shape = CircleShape,
            color = Color.Transparent,
          ) {
            Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
              iconContent()
            }
          }
        else
          AirButton(
            click,
            Modifier.size(40.dp).then(extra).focusProperties {
              down = if (posters.isEmpty()) FocusRequester.Cancel else first
            },
            shape = CircleShape,
            onFocus = { focused = it },
            color = Color.Transparent,
            contentPadding = PaddingValues(9.dp),
          ) {
            iconContent()
          }
      }
      action(
        R.drawable.ic_vpn_key,
        if (vpn) "VPN включён" else "VPN выключен",
        active = vpn,
      ) {
        Actions.toggleVpn(context)
      }
      action(R.drawable.ic_apps, "Приложения") {
        context.startActivity(
          android.content.Intent(context, SettingsActivity::class.java).putExtra("section", "apps")
        )
      }
      action(RemoteIcons.Settings, "Настройки", Modifier.focusRequester(settings)) {
        Actions.run(context, "settings")
      }
      Text(clock, Modifier.padding(start = 4.dp), fontSize = 25.sp)
    }
    LazyRow(
      state = row,
      modifier =
        Modifier.fillMaxWidth()
          .offset(y = layout.rowTop.dp)
          .height((HomeLayout.PosterHeight + 32).dp),
      contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(layout.gap.dp),
    ) {
      itemsIndexed(posters, key = { _, item -> item.program.intent }) { index, art ->
        PosterCard(
          art,
          style,
          Modifier.size(HomeLayout.PosterWidth.dp, HomeLayout.PosterHeight.dp)
            .then(if (index == selected) Modifier.focusRequester(first) else Modifier)
            .focusProperties {
              up = settings
              down = FocusRequester.Cancel
              if (index == 0) left = FocusRequester.Cancel
              if (index == posters.lastIndex) right = FocusRequester.Cancel
            },
          layout.motion,
          preview = preview,
          previewFocused = index == selected,
          pivot = if (index == 0) 0f else if (index == posters.lastIndex) 1f else .5f,
          onFocus = {
            selected = index
            onPosterFocus(index)
          },
          onClick = { Actions.openUri(context, art.program.intent) },
        )
      }
    }
    val art = posters.getOrNull(selected)
    if (layout.showTitle && art != null) {
      val top =
        if (layout.mode == 0) layout.posterTop + HomeLayout.PosterHeight + 24
        else layout.posterTop - if (layout.captionStyle == 1) 80 else 52
      val details = SelectionCaption.description(art.program.image)
      if (layout.captionStyle == 4)
        Row(
          Modifier.offset(x = 32.dp, y = top.dp).width(896.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            art.program.title,
            Modifier.widthIn(max = 560.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (details.isNotEmpty())
            Text(
              details,
              Modifier.padding(start = 12.dp).widthIn(max = 324.dp),
              fontSize = 16.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
        }
      else
        Column(Modifier.offset(x = 32.dp, y = top.dp).width(896.dp)) {
          Text(
            art.program.title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (layout.captionStyle == 1 && details.isNotEmpty())
            Text(
              details,
              Modifier.padding(top = 8.dp),
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
        }
    }
  }
}

@Composable
internal fun HomePreview(modifier: Modifier = Modifier, photo: File? = null, revision: Int = 0) {
  val context = LocalContext.current
  var wallpaper by remember { mutableStateOf<Bitmap?>(null) }
  var theme by remember { mutableStateOf<WallpaperTheme?>(null) }
  var posters by remember { mutableStateOf(emptyList<PosterArt>()) }
  val renderer = remember { Dispatchers.Default.limitedParallelism(1) }
  val styleKey = remember(revision) { BackdropStyle(context).fingerprint() }
  val vpn = remember(context) { SettingsActivity.vpnOn(context) }
  LaunchedEffect(Unit) {
    posters =
      withContext(Dispatchers.IO) {
        val store = PosterStore(context)
        ProgramRepository(context).cached().take(16).map {
          val art = store.loadCard(it.image)
          PosterArt(it, art.bitmap, art.separateBadges, Ui.posterAccent(art.bitmap))
        }
      }
  }
  LaunchedEffect(photo, styleKey) {
    val prepared =
      withContext(renderer) {
        val job = currentCoroutineContext()
        WallpaperController.prepare(
          context,
          photo ?: WallpaperStore(context).selected(System.currentTimeMillis() / 3600000),
          width = 960,
          height = 540,
          checkCancelled = { job.ensureActive() },
        )
      }
    wallpaper = prepared.image
    theme = prepared.theme
  }
  BoxWithConstraints(
    modifier.clip(RoundedCornerShape(20.dp)).clearAndSetSemantics {
      contentDescription = "Предпросмотр главного экрана"
    }
  ) {
    val density = LocalDensity.current
    val scale = maxWidth.value / 960f
    CompositionLocalProvider(
      LocalDensity provides Density(density.density * scale, density.fontScale)
    ) {
      HomeTheme(theme) {
        HomeContent(
          wallpaper,
          posters,
          "12:00",
          vpn,
          Modifier.requiredSize(960.dp, 540.dp),
          preview = true,
          appearance = revision,
        )
      }
    }
  }
}

@Composable
internal fun AppIcon(drawable: Drawable, modifier: Modifier = Modifier) {
  val bitmap =
    remember(drawable) {
      Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(android.graphics.Canvas(it))
      }
    }
  Image(bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
}
