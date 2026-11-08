package belowevolve.tvhome

import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.tv.TvContract
import android.net.*
import android.os.*
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.function.BiConsumer
import kotlinx.coroutines.*

class HomeActivity : QuietActivity() {
  private val main = Handler(Looper.getMainLooper())
  private val scope = MainScope()
  private var background by mutableStateOf<Bitmap?>(null)
  private var theme by mutableStateOf<WallpaperTheme?>(null)
  private var posters by mutableStateOf(emptyList<PosterArt>())
  private var clock by mutableStateOf("")
  private var vpn by mutableStateOf(false)
  private var focusRequest by mutableIntStateOf(0)
  private var appearance by mutableIntStateOf(0)
  private var appearanceKey = ""
  private var focusedPoster = -1
  private var signature = ""
  private var loadJob: Job? = null
  private var loadAgain = false
  private lateinit var wallpapers: WallpaperController
  private lateinit var programs: ProgramRepository
  private lateinit var artwork: PosterStore
  private val observer =
    object : ContentObserver(main) {
      override fun onChange(selfChange: Boolean) {
        main.removeCallbacks(reload)
        main.postDelayed(reload, 600)
      }
    }
  private val reload = Runnable { loadPrograms() }
  private val tick =
    object : Runnable {
      override fun run() {
        clock =
          android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
        wallpapers.update()
        main.postDelayed(this, 60000 - System.currentTimeMillis() % 60000)
      }
    }
  private val network =
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(n: Network) {
        updateVpn()
      }

      override fun onLost(n: Network) {
        updateVpn()
      }

      override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
        updateVpn()
      }
    }

  private fun updateVpn() {
    main.post { vpn = SettingsActivity.vpnOn(this) }
  }

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    Ui.fullscreen(this)
    WakeTrace.record(this, "home_create")
    programs = ProgramRepository(this)
    artwork = PosterStore(this)
    wallpapers =
      WallpaperController(
        this,
        BiConsumer { image, palette ->
          background = image
          theme = palette
          Ui.useTheme(palette)
        },
      )
    setContent {
      HomeTheme(theme) {
        key(appearance) {
          HomeContent(
            background,
            posters,
            clock,
            vpn,
            focusRequest = focusRequest,
            onPosterFocus = { focusedPoster = it },
          )
        }
      }
    }
    scope.launch {
      val cached = withContext(Dispatchers.IO) { programs.cached() }
      if (signature.isEmpty() && cached.isNotEmpty()) showPrograms(cached)
    }
    route(intent)
    if (
      checkSelfPermission("android.permission.READ_TV_LISTINGS") !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    )
      requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), 1)
  }

  private fun loadPrograms() {
    if (loadJob?.isActive == true) {
      loadAgain = true
      return
    }
    loadJob = scope.launch {
      do {
        loadAgain = false
        val loaded = withContext(Dispatchers.IO) { programs.load() }
        if (loaded != null) showPrograms(loaded)
      } while (loadAgain)
    }
  }

  private fun showPrograms(programs: List<ProgramRepository.Program>) {
    val next = programs.joinToString { it.intent + it.image + it.title }
    if (next == signature) return
    signature = next
    val previous = posters.associateBy { it.program.intent }
    posters =
      programs.take(16).map { program ->
        val old = previous[program.intent]
        val sameArtwork =
          old != null &&
            (old.program.image == program.image ||
              old.clean &&
                PosterBadges.metadata(old.program.image)?.optString("poster") ==
                  PosterBadges.metadata(program.image)?.optString("poster"))
        if (sameArtwork && old?.bitmap?.isRecycled == false) old.copy(program = program)
        else PosterArt(program)
      }
    val dispatcher = Dispatchers.IO.limitedParallelism(2)
    posters
      .filter { it.bitmap == null }
      .forEach { item ->
        scope.launch {
          val loaded =
            withContext(dispatcher) {
              preparePosterArt(item.program, artwork)
            }
          if (signature == next)
            posters = posters.map {
              if (it.program.intent == item.program.intent) loaded else it
            }
        }
      }
  }

  private fun route(value: Intent) {
    if (value.getBooleanExtra("next_wallpaper", false)) {
      value.removeExtra("next_wallpaper")
      val p = getSharedPreferences("home", 0)
      p.edit().putInt("wallpaper_offset", p.getInt("wallpaper_offset", 0) + 1).apply()
      wallpapers.invalidate()
      wallpapers.update()
      return
    }
    if (value.getBooleanExtra("wallpaper_changed", false)) {
      value.removeExtra("wallpaper_changed")
      wallpapers.invalidate()
      wallpapers.update()
      return
    }
    if (value.getBooleanExtra("hdmi", false) || value.getBooleanExtra("hdmi_toggle", false))
      startActivity(HdmiActivity.intent(this, value.getStringExtra("input")))
    else focusRequest++
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    route(intent)
  }

  override fun onStart() {
    super.onStart()
    getSystemService(ConnectivityManager::class.java)
      .registerNetworkCallback(
        NetworkRequest.Builder()
          .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          .build(),
        network,
        main,
      )
    contentResolver.registerContentObserver(
      TvContract.WatchNextPrograms.CONTENT_URI,
      true,
      observer,
    )
    contentResolver.registerContentObserver(
      Uri.parse("content://android.media.tv/preview_program"),
      true,
      observer,
    )
  }

  override fun onResume() {
    super.onResume()
    Ui.fullscreen(this)
    theme = WallpaperTheme.load(this)
    val nextStyle = getSharedPreferences("appearance", 0).all.toString()
    if (nextStyle != appearanceKey) {
      appearanceKey = nextStyle
      appearance++
    }
    WakeTrace.record(this, "home_resume")
    if (Build.VERSION.SDK_INT >= 29)
      window.decorView.viewTreeObserver.registerFrameCommitCallback {
        WakeTrace.record(this, "home_frame")
      }
    updateVpn()
    main.removeCallbacks(tick)
    tick.run()
    loadPrograms()
    wallpapers.resume()
  }

  override fun onPause() {
    main.removeCallbacks(tick)
    super.onPause()
  }

  override fun onStop() {
    WakeTrace.record(this, "home_stop")
    wallpapers.stop()
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
    contentResolver.unregisterContentObserver(observer)
    main.removeCallbacks(reload)
    super.onStop()
  }

  override fun onDestroy() {
    scope.cancel()
    wallpapers.close()
    main.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  override fun onRemoteBack() {
    focusRequest++
  }

  override fun dispatchKeyEvent(e: KeyEvent): Boolean {
    if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_TV_INPUT) {
      if (e.repeatCount == 0)
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "inputs"))
      return true
    }
    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && Actions.color(this, e.keyCode))
      return true
    return super.dispatchKeyEvent(e)
  }

  override fun dump(
    prefix: String,
    fd: FileDescriptor?,
    out: PrintWriter,
    args: Array<out String>?,
  ) {
    super.dump(prefix, fd, out, args)
    WakeTrace.dump(this, prefix, out)
    out.println(
      "${prefix}AirHome ui=Compose focusedPoster=$focusedPoster posters=${posters.size} liveBadgeOverlays=${posters.count{it.clean&&PosterBadges.metadata(it.program.image)!=null}}"
    )
    out.println(prefix + "AIRHOME_WALLPAPERS " + WallpaperStore(this).diagnostics())
    WallpaperTheme.load(this)?.let { theme ->
      val palette = theme.filmPalette()
      val colors = org.json.JSONObject()
      for (k in palette.keySet()) if (k != "v")
        colors.put(k, palette.getInt(k).toLong() and 0xffffffffL)
      out.println(prefix + "AIRHOME_PALETTE " + colors)
    }
  }
}
