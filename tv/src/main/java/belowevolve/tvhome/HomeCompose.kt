package belowevolve.tvhome

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
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

/** Worker-only: prepare the first focused frame's artwork and coloured glow. */
internal fun preparePosterArt(
  program: ProgramRepository.Program,
  store: PosterStore,
  cachedOnly: Boolean = false,
): PosterArt {
  val art = if (cachedOnly) store.loadCachedCard(program.image) else store.loadCard(program.image)
  val accent = Ui.posterAccent(art.bitmap)
  if (art.bitmap != null) PosterShadow.load(accent, false)
  return PosterArt(program, art.bitmap, art.separateBadges, accent)
}

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
  fillLabel: Boolean = true,
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
      if (fillLabel) Modifier.weight(1f) else Modifier,
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
      contentPadding = PaddingValues(0.dp),
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painterResource(RemoteIcons.Back), "Назад", Modifier.size(20.dp))
      }
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

/** Keep focus on the same grid line, including when the next card is partly visible. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberTvRowFocusSpec(
  step: Int = HomeLayout.RowStep,
  motion: Int? = null,
): BringIntoViewSpec {
  val anchor =
    with(LocalDensity.current) {
      (HomeLayout.RowInset + 2 * step).dp.toPx()
    }
  return remember(anchor, motion) {
    object : BringIntoViewSpec {
      override val scrollAnimationSpec: AnimationSpec<Float> =
        tween(
          if (motion == null) 180 else CardMotion.duration(motion, true),
          easing = if (motion == null) FastOutSlowInEasing else CardMotion.easing(motion),
        )

      override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
      ): Float {
        val distance = offset - minOf(anchor, (containerSize - size).coerceAtLeast(0f))
        return if (kotlin.math.abs(distance) < .5f) 0f else distance
      }
    }
  }
}

@Composable
internal fun PosterBadgesContent(
  image: String,
  modifier: Modifier = Modifier,
  inset: Float = 6f,
  format: Int = PosterBadgeSpec.CIRCLE,
  showEpisode: Boolean = true,
  showNews: Boolean = true,
) {
  val metadata = remember(image) { PosterBadges.metadata(image) } ?: return
  val season = metadata.optString("season")
  val mark = metadata.optString("mark")
  val episode = remember(season, mark, format) { PosterBadgeSpec.format(season, mark, format) }
  val circle = remember(season, mark, format) { PosterBadgeSpec.circleSeason(season, mark, format) }
  val news = metadata.optString("news")
  val lineHeight = with(LocalDensity.current) { PosterBadgeSpec.LINE_SP.sp.toDp() }
  val badgeStyle =
    TextStyle(
      fontSize = PosterBadgeSpec.TEXT_SP.sp,
      lineHeight = PosterBadgeSpec.LINE_SP.sp,
      letterSpacing = .44.sp,
      fontWeight = FontWeight.Medium,
      platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
  @Composable
  fun chip(text: String, season: String = "") {
    Row(
      Modifier.height(lineHeight + (2 * PosterBadgeSpec.PADDING_Y_DP).dp)
        .background(
          MaterialTheme.colorScheme.primaryContainer,
          RoundedCornerShape(PosterBadgeSpec.CORNER_DP.dp),
        )
        .padding(PosterBadgeSpec.PADDING_X_DP.dp, PosterBadgeSpec.PADDING_Y_DP.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (season.isNotEmpty()) {
        Box(
          Modifier.size(lineHeight)
            .border(
              1.dp,
              MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .65f),
              CircleShape,
            ),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            season,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style =
              badgeStyle.copy(
                fontSize = (10 - season.length).coerceAtLeast(5).sp,
                letterSpacing = 0.sp,
              ),
            maxLines = 1,
            softWrap = false,
          )
        }
        Spacer(Modifier.width(4.dp))
      }
      Text(
        text,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = badgeStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
  Box(modifier.padding(inset.dp)) {
    if (showEpisode && episode.isNotEmpty())
      Box(Modifier.align(Alignment.TopStart)) { chip(episode, circle) }
    if (showNews && news.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd)) { chip(news) }
  }
}

@Composable
internal fun PosterCard(
  art: PosterArt,
  style: BackdropStyle,
  layout: HomeLayout,
  modifier: Modifier = Modifier,
  preview: Boolean = false,
  previewFocused: Boolean = false,
  onFocus: () -> Unit = {},
  onClick: () -> Unit = {},
) {
  var focused by remember { mutableStateOf(false) }
  val motion = layout.motion
  val active = if (preview) previewFocused else focused
  val progress by
    animateFloatAsState(
      if (active) 1f else 0f,
      tween(
        CardMotion.duration(motion, active),
        easing = CardMotion.easing(motion),
      ),
      label = "poster focus",
    )
  val shape = RoundedCornerShape(layout.cornerRadius.dp)
  val description =
    remember(art.program) {
      listOf(art.program.title, SelectionCaption.description(art.program.image))
        .filter { it.isNotEmpty() }
        .joinToString(", ")
    }
  val image = art.bitmap
  val wide = style.mode == 2 && style.mat == 1
  val shadow by
    produceState(
      PosterShadow.cached(art.accent, wide, layout.shadow),
      art.accent,
      wide,
      layout.shadow,
    ) {
      value =
        if (layout.shadow == PosterShadow.None) null
        else withContext(Dispatchers.Default) { PosterShadow.load(art.accent, wide, layout.shadow) }
    }
  val baseShadow by
    produceState<PosterShadow.Mask?>(null, style.mode, style.mat, layout.shadow) {
      value =
        if (style.mode == 2 && style.mat != 2 && layout.shadow != PosterShadow.None)
          withContext(Dispatchers.Default) {
            PosterShadow.load(android.graphics.Color.BLACK, wide, layout.shadow)
          }
        else null
    }
  val interaction = remember {
    androidx.compose.foundation.interaction.MutableInteractionSource()
  }
  Box(
    modifier
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
      .clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        this.focused = focused
        onClick {
          onClick()
          true
        }
      }
  ) {
    Box(
      Modifier.fillMaxSize().graphicsLayer {
        val scale = 1f + (CardMotion.scale(motion) - 1f) * progress
        scaleX = scale
        scaleY = scale
        translationY = CardMotion.lift(motion).dp.toPx() * progress
        transformOrigin = TransformOrigin(.5f, 1f)
      }
    ) {
      val baseOpacity = if (style.mode == 2) style.matOpacity / 100f else 0f
      PosterGlow(
        baseShadow,
        layout.glow,
        { baseOpacity * (1f - progress) },
        Modifier.matchParentSize(),
      )
      PosterGlow(
        shadow,
        layout.glow,
        {
          if (style.mode == 2 && style.mat == 2) baseOpacity + (1f - baseOpacity) * progress
          else progress
        },
        Modifier.matchParentSize(),
      )
      Box(
        Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainer, shape)
      ) {
        if (image != null && !image.isRecycled)
          Image(
            remember(image) { image.asImageBitmap() },
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        if (art.clean && (layout.showEpisodeBadges || layout.showNewsBadges))
          PosterBadgesContent(
            art.program.image,
            Modifier.fillMaxSize(),
            layout.badgeInset,
            layout.badgeFormat,
            layout.showEpisodeBadges,
            layout.showNewsBadges,
          )
      }
    }
  }
}

/** Fade a cached texture through layer properties without recording the poster again. */
@Composable
private fun PosterGlow(
  mask: PosterShadow.Mask?,
  strength: Int,
  opacity: () -> Float,
  modifier: Modifier,
) {
  if (mask == null || strength == 0) return
  Spacer(
    modifier
      .graphicsLayer {
        alpha = opacity() * strength / 100f
        compositingStrategy = CompositingStrategy.ModulateAlpha
      }
      .drawBehind {
        val inset = (mask.inset * size.width / HomeLayout.PosterWidth).toInt()
        drawImage(
          mask.image,
          dstOffset = IntOffset(-inset, -inset),
          dstSize = IntSize(size.width.toInt() + inset * 2, size.height.toInt() + inset * 2),
        )
      }
  )
}

@OptIn(ExperimentalFoundationApi::class)
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
  val row = rememberScrollState()
  val density = LocalDensity.current
  val rowFocus = rememberTvRowFocusSpec(layout.posterWidth + layout.gap, layout.motion)
  val posterFocus =
    remember(posters.map { it.program.intent }) {
      posters.map { FocusRequester() }
    }
  val choosePoster =
    remember(onPosterFocus) {
      { index: Int ->
        selected = index
        onPosterFocus(index)
      }
    }
  val settings = remember { FocusRequester() }
  val vpnFocus = remember { FocusRequester() }
  val appsFocus = remember { FocusRequester() }
  var previewActive by remember { mutableStateOf(true) }
  val inputMode = LocalInputModeManager.current
  LaunchedEffect(focusRequest, posters.size) {
    if (preview && focusRequest > 0) {
      previewActive = false
      delay(CardMotion.duration(layout.motion, false).toLong())
      previewActive = true
    } else if (!preview) {
      withFrameNanos {}
      inputMode.requestInputMode(InputMode.Keyboard)
      if (posters.isNotEmpty()) {
        selected = selected.coerceAtMost(posters.lastIndex)
        row.scrollTo(
          with(density) { (selected * (layout.posterWidth + layout.gap)).dp.roundToPx() }
        )
        withFrameNanos {}
        posterFocus[selected].requestFocus()
      } else settings.requestFocus()
    }
  }
  val windowPixels = LocalWindowInfo.current.containerSize
  Box(
    modifier
      .fillMaxSize()
      .then(
        if (preview || wallpaper == null) Modifier.background(MaterialTheme.colorScheme.background)
        else Modifier
      )
  ) {
    if (wallpaper != null && !wallpaper.isRecycled)
      Image(
        remember(wallpaper) { wallpaper.asImageBitmap() },
        null,
        Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        filterQuality =
          if (
            !preview &&
              wallpaper.width == windowPixels.width &&
              wallpaper.height == windowPixels.height
          )
            FilterQuality.None
          else FilterQuality.Low,
      )
    Row(
      Modifier.align(Alignment.TopEnd)
        .padding(top = 20.dp, end = 32.dp)
        .height(48.dp)
        .graphicsLayer(),
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
              down = posterFocus.getOrNull(selected) ?: FocusRequester.Cancel
              up = FocusRequester.Cancel
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
        Modifier.focusRequester(vpnFocus).focusProperties {
          left = FocusRequester.Cancel
          right = appsFocus
        },
        active = vpn,
      ) {
        Actions.toggleVpn(context)
      }
      action(
        R.drawable.ic_apps,
        "Приложения",
        Modifier.focusRequester(appsFocus).focusProperties {
          left = vpnFocus
          right = settings
        },
      ) {
        context.startActivity(
          android.content.Intent(context, SettingsActivity::class.java).putExtra("section", "apps")
        )
      }
      action(
        RemoteIcons.Settings,
        "Настройки",
        Modifier.focusRequester(settings).focusProperties {
          left = appsFocus
          right = FocusRequester.Cancel
        },
      ) {
        Actions.run(context, "settings")
      }
      Text(clock, Modifier.padding(start = 4.dp), fontSize = 25.sp)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowFocus) {
      PosterShelf(
        posters,
        layout,
        style,
        row,
        posterFocus,
        settings,
        preview,
        previewActive,
        choosePoster,
      )
    }
    val art = posters.getOrNull(selected)
    if (layout.showTitle && art != null) {
      val anchor =
        if (layout.mode == 0) layout.posterTop + layout.posterHeight else layout.posterTop
      HomeCaption(
        Caption(art.program.title, SelectionCaption.description(art.program.image)),
        layout,
        Modifier.offset(x = 32.dp, y = anchor.dp).width(896.dp).layout { measurable, constraints ->
          val caption = measurable.measure(constraints)
          val gap = layout.captionGap.dp.roundToPx()
          layout(caption.width, caption.height) {
            caption.place(0, if (layout.mode == 0) gap else -caption.height - gap)
          }
        },
      )
    }
  }
}

private data class Caption(val title: String, val details: String)

private class CaptionLayer(initialCaption: Caption, initialOpacity: Float) {
  var caption by mutableStateOf(initialCaption)
  val opacity = Animatable(initialOpacity)
}

@Composable
private fun HomeCaption(target: Caption, layout: HomeLayout, modifier: Modifier) {
  val accessible = modifier.clearAndSetSemantics {
    contentDescription =
      listOf(target.title, target.details).filter { it.isNotEmpty() }.joinToString(", ")
  }
  if (!layout.animateTitle) {
    CaptionContent(target, layout, accessible)
    return
  }
  // Keep both text trees alive. Creating/removing a Text subtree on every focus change
  // caused frequent ART collections and visible suspension pauses on the TV.
  val layers = remember { listOf(CaptionLayer(target, 1f), CaptionLayer(target, 0f)) }
  LaunchedEffect(target) {
    val incoming =
      layers.filter { it.caption == target }.maxByOrNull { it.opacity.value }
        ?: layers
          .minBy { it.opacity.value }
          .also {
            it.opacity.snapTo(0f)
            it.caption = target
          }
    kotlinx.coroutines.coroutineScope {
      layers.forEach { layer ->
        launch {
          val to = if (layer === incoming) 1f else 0f
          if (layer.opacity.value != to)
            layer.opacity.animateTo(to, tween(120, easing = LinearEasing))
        }
      }
    }
  }
  Box(accessible) {
    layers.forEach { layer ->
      key(layer) {
        CaptionContent(
          layer.caption,
          layout,
          Modifier.fillMaxWidth().graphicsLayer {
            alpha = layer.opacity.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
          },
        )
      }
    }
  }
}

@Composable
private fun CaptionContent(caption: Caption, layout: HomeLayout, modifier: Modifier) {
  Box(modifier) {
    if (layout.captionStyle == 4)
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          caption.title,
          Modifier.widthIn(max = 560.dp),
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (caption.details.isNotEmpty())
          Text(
            caption.details,
            Modifier.padding(start = 12.dp).widthIn(max = 324.dp),
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
      }
    else
      Column(Modifier.fillMaxWidth()) {
        Text(
          caption.title,
          fontSize = 22.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (layout.captionStyle == 1 && caption.details.isNotEmpty())
          Text(
            caption.details,
            Modifier.padding(top = 8.dp),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
      }
  }
}

/** The bounded shelf is measured once; selecting a title does not rebuild card modifiers. */
@Composable
private fun PosterShelf(
  posters: List<PosterArt>,
  layout: HomeLayout,
  style: BackdropStyle,
  scroll: ScrollState,
  focus: List<FocusRequester>,
  settings: FocusRequester,
  preview: Boolean,
  previewActive: Boolean,
  onFocus: (Int) -> Unit,
) {
  val context = LocalContext.current
  Row(
    Modifier.fillMaxWidth()
      .offset(y = layout.rowTop.dp)
      .height((layout.posterHeight + 32).dp)
      .horizontalScroll(scroll)
      .padding(horizontal = 32.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(layout.gap.dp),
  ) {
    posters.forEachIndexed { index, art ->
      key(art.program.intent) {
        PosterCard(
          art,
          style,
          layout,
          Modifier.size(layout.posterWidth.dp, layout.posterHeight.dp)
            .focusRequester(focus[index])
            .focusProperties {
              up = settings
              down = FocusRequester.Cancel
              if (index == 0) left = FocusRequester.Cancel
              if (index == posters.lastIndex) right = FocusRequester.Cancel
            },
          preview = preview,
          previewFocused = index == 0 && previewActive,
          onFocus = { onFocus(index) },
          onClick = { Actions.openUri(context, art.program.intent) },
        )
      }
    }
  }
}

@Composable
internal fun HomePreview(
  modifier: Modifier = Modifier,
  photo: File? = null,
  revision: Int = 0,
  focusRequest: Int = 0,
) {
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
          preparePosterArt(it, store, cachedOnly = true)
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
          focusRequest = focusRequest,
          appearance = revision,
        )
      }
    }
  }
}

@Composable
internal fun AppIcon(bitmap: Bitmap, modifier: Modifier = Modifier) {
  Image(
    remember(bitmap) { bitmap.asImageBitmap() },
    null,
    modifier,
    contentScale = ContentScale.Fit,
  )
}
