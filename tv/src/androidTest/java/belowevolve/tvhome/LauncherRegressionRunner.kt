package belowevolve.tvhome

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.ArrayList
import java.util.function.Function

/* Uses isolated cache files and synthetic provider cursors; never changes the user's shelf. */
class LauncherRegressionRunner : Instrumentation() {
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    start()
  }

  override fun onStart() {
    val result: Bundle = Bundle()
    var directory: File? = null
    try {
      directory =
        Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "shelf-test-").toFile()
      val files: File? = directory
      val context: Context =
        object : ContextWrapper(getTargetContext()) {
          override fun getFilesDir(): File = requireNotNull(files)

          override fun getCacheDir(): File = File(requireNotNull(files), "cache").apply { mkdirs() }

          override fun getApplicationContext(): Context = this
        }
      val repository: ProgramRepository = ProgramRepository(context, { uri -> snapshot(uri, true) })
      check(repository.load()!!.size == 1, "Initial shelf missing")
      check(repository.cached().size == 1, "Initial shelf was not saved")

      // Failure after one successful source must not replace the complete cached snapshot.
      val partial: Function<Uri, Cursor?> = { uri ->
        if ("preview_program" == uri.getLastPathSegment())
          throw IllegalStateException("Synthetic provider restart")
        snapshot(uri, false)
      }
      check(
        ProgramRepository(context, partial).load() == null,
        "Partial query became an empty shelf",
      )
      check(repository.cached().size == 1, "Provider failure erased cached cards")
      check(
        ProgramRepository(context, { uri -> null }).load() == null,
        "Null cursor became an empty shelf",
      )
      check(repository.cached().size == 1, "Unavailable provider erased cached cards")

      val cleared: ProgramRepository = ProgramRepository(context, { uri -> snapshot(uri, false) })
      val empty: ArrayList<ProgramRepository.Program>? = cleared.load()
      check(empty != null && empty.isEmpty(), "Successful empty query was rejected")
      check(
        ProgramRepository(context, partial).cached().isEmpty(),
        "Removed cards returned after restart",
      )
      checkPosters(context)
      checkArtworkSources(context)
      checkViews(context)
      checkWallpaperPreview(context)
      ColorKeyActionsRegression.run()
      val wallpaper: Actions.PreparedAction? = Actions.prepare(context, "next-wallpaper")
      check(
        (wallpaper != null &&
          wallpaper.initial.getBooleanExtra("next_wallpaper", false) &&
          !wallpaper.restore.hasExtra("next_wallpaper")),
        "Restoring HOME would change wallpaper twice",
      )
      check(
        wallpaper!!.initial.getComponent() == wallpaper.restore.getComponent(),
        "Restoration changed the resolved activity",
      )
      val film: Actions.PreparedAction =
        Actions.PreparedAction(
          context,
          "mov.film",
          android.content
            .Intent()
            .setComponent(android.content.ComponentName("mov.film", "mov.film.Main")),
        )
      check(
        film.canRestore(context.getPackageName(), "home", false),
        "HOME displacement cannot be corrected",
      )
      check(
        film.canRestore("com.google.android.backdrop", "dream", true),
        "Dream displacement cannot be corrected",
      )
      check(
        !film.canRestore("mov.film", "film", false),
        "Already-visible target would be restarted",
      )
      check(
        !film.canRestore(null, "home", false),
        "Unknown window would trigger a speculative restart",
      )
      check(
        !film.canRestore("another.app", "", false),
        "Restoration would steal focus from another app",
      )
      result.putString(
        "stream",
        "Launcher: shelf/provider/cache, clean local artwork priority, nullable clean-artwork metadata and bounds, bounded/cancellable wallpaper previews, immediate color shortcut launch, dream/HOME races and idempotent target restoration: PASS\n",
      )
      finish(Activity.RESULT_OK, result)
    } catch (error: Throwable) {
      result.putString("stream", android.util.Log.getStackTraceString(error))
      finish(Activity.RESULT_CANCELED, result)
    } finally {
      if (directory != null) remove(directory)
    }
  }

  /** Preview work stays bounded and never modifies the original photo or user appearance. */
  private fun checkWallpaperPreview(context: Context) {
    val preview = WallpaperController.prepare(context, null, width = 960, height = 540)
    try {
      check(
        preview.image?.width == 960 && preview.image?.height == 540,
        "Preview must not decode/process a full-screen wallpaper",
      )
      check(preview.theme != null, "Preview lost its wallpaper palette")
    } finally {
      preview.image?.recycle()
    }
    var stages = 0
    var cancelled = false
    try {
      WallpaperController.prepare(
        context,
        null,
        width = 960,
        height = 540,
        checkCancelled = {
          if (++stages == 3) throw kotlinx.coroutines.CancellationException("Superseded preview")
        },
      )
    } catch (expected: kotlinx.coroutines.CancellationException) {
      cancelled = true
    }
    check(cancelled, "Obsolete preview continued after cancellation")
    val appearance = context.getSharedPreferences("preview-regression", 0)
    val isolated =
      object : ContextWrapper(context) {
        override fun getSharedPreferences(
          name: String,
          mode: Int,
        ): android.content.SharedPreferences =
          if (name == "appearance") appearance else super.getSharedPreferences(name, mode)
      }
    try {
      val original = File(context.filesDir, "preview-original.jpg")
      writeImage(original, Color.WHITE)
      appearance.edit().putInt("mode", 0).putInt("dim", 50).commit()
      val dimmed = WallpaperController.prepare(isolated, original, width = 960, height = 540)
      try {
        check(
          Color.red(requireNotNull(dimmed.image).getPixel(480, 270)) in 120..135,
          "Preview ignored the chosen style",
        )
      } finally {
        dimmed.image?.recycle()
      }
      appearance.edit().putInt("dim", 0).commit()
      val clear = WallpaperController.prepare(isolated, original, width = 960, height = 540)
      try {
        check(
          Color.red(requireNotNull(clear.image).getPixel(480, 270)) >= 250,
          "Cached preview baked the previous dimming into the original",
        )
        check(
          BitmapFactory.decodeFile(original.path)?.let { bitmap ->
            try {
              bitmap.width == 2 && bitmap.getPixel(0, 0) == Color.WHITE
            } finally {
              bitmap.recycle()
            }
          } == true,
          "Preview cache modified the original wallpaper",
        )
      } finally {
        clear.image?.recycle()
      }
    } finally {
      context.deleteSharedPreferences("preview-regression")
    }
  }

  private fun checkViews(context: Context) {
    check(Ui.posterAccent(null) == -0x655e56, "Missing artwork must have a neutral accent")
    val artwork = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
    try {
      artwork.eraseColor(Color.RED)
      check(Ui.posterAccent(artwork) == Color.RED, "Poster sampling lost the actual artwork color")
    } finally {
      artwork.recycle()
    }
    check(PosterBadges.metadata(null) == null, "Missing badge URI must stay optional")
    check(
      SelectionCaption.description(null).isEmpty(),
      "Missing caption metadata must stay optional",
    )
  }

  private fun checkArtworkSources(context: Context) {
    val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val original = "https://example.invalid/original.jpg?a=1&b=2"
    val metadata = org.json.JSONObject().put("v", 1).put("poster", original).put("mark", "1 серия")
    val legacy =
      Uri.Builder()
        .scheme("content")
        .authority("mov.film.tvart")
        .path("p00000000000000000000000000000000.jpg")
        .appendQueryParameter("airhome_badges", metadata.toString())
        .build()
        .toString()
    val store = PosterStore(context)
    val reads = ArrayList<String>()
    try {
      val local =
        store.loadCard(legacy) { uri ->
          reads.add(uri)
          check(Uri.parse(uri).path == "/clean", "Local artwork was not attempted first")
          check(
            Uri.parse(uri).getQueryParameter("poster") == original,
            "Original URL was not preserved",
          )
          image
        }
      check(
        local.bitmap === image && local.separateBadges && reads.size == 1,
        "Cached clean artwork unexpectedly needed network or lost dynamic badges",
      )

      reads.clear()
      val remote =
        store.loadCard(legacy) { uri ->
          reads.add(uri)
          if (uri == original) image else null
        }
      check(
        remote.separateBadges && remote.bitmap === image && reads.size == 2,
        "Older provider must fall back to the clean remote original",
      )

      reads.clear()
      val baked =
        store.loadCard(legacy) { uri ->
          reads.add(uri)
          if (uri == legacy) image else null
        }
      check(
        !baked.separateBadges && baked.bitmap === image && reads.size == 3,
        "Legacy baked artwork was mistaken for a clean image",
      )

      reads.clear()
      val oldUri = "content://mov.film.tvart/p00000000000000000000000000000000.jpg"
      val old =
        store.loadCard(oldUri) { uri ->
          reads.add(uri)
          image
        }
      check(
        !old.separateBadges && reads == listOf(oldUri),
        "Legacy metadata must remain a single unmodified provider read",
      )
    } finally {
      image.recycle()
    }
  }

  private fun snapshot(uri: Uri, populated: Boolean): Cursor {
    if ("channel" == uri.getLastPathSegment())
      return MatrixCursor(arrayOf<String>("_id", "package_name", "display_name"))
    val cursor: MatrixCursor =
      MatrixCursor(
        arrayOf<String>(
          "package_name",
          "title",
          "poster_art_uri",
          "intent_uri",
          "last_engagement_time_utc_millis",
          "weight",
          "channel_id",
        )
      )
    if (populated && "watch_next_program" == uri.getLastPathSegment())
      cursor.addRow(
        arrayOf<Any>(
          "mov.film",
          "Synthetic film",
          "https://example.invalid/poster",
          "film://test",
          100L,
          1L,
          1L,
        )
      )
    return cursor
  }

  private fun check(passed: Boolean, message: String) {
    if (!passed) throw AssertionError(message)
  }

  @Throws(Exception::class)
  private fun checkPosters(context: Context) {
    val red: File = File(context.getFilesDir(), "Aa")
    val blue: File = File(context.getFilesDir(), "BB")
    writeImage(red, Color.RED)
    writeImage(blue, Color.BLUE)
    val first: String = red.toURI().toString()
    val second: String = blue.toURI().toString()
    check(first.hashCode() == second.hashCode(), "URLs must reproduce the old cache collision")
    val store: PosterStore = PosterStore(context)
    checkColor(store.load(first), Color.RED)
    checkColor(store.load(second), Color.BLUE)
    val cached: Array<File>? = File(context.getFilesDir(), "posters").listFiles()
    check(cached != null && cached.size == 2, "Different posters shared a cache entry")
    for (file: File in cached!!) Files.write(file.toPath(), byteArrayOf(0, 1, 2))
    checkColor(store.load(first), Color.RED)
    checkColor(store.load(second), Color.BLUE)
  }

  @Throws(Exception::class)
  private fun writeImage(file: File, color: Int) {
    val image: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    image.eraseColor(color)
    try {
      FileOutputStream(file)
        .use({ out ->
          check(
            image.compress(Bitmap.CompressFormat.PNG, 100, out),
            "Cannot create test poster",
          )
        })
    } finally {
      image.recycle()
    }
  }

  private fun checkColor(image: Bitmap?, color: Int) {
    check(image != null, "Poster did not load")
    try {
      check(
        image!!.getPixel(image.width / 2, image.height / 2) == color,
        "Wrong poster returned from cache",
      )
      check(
        Color.alpha(image.getPixel(0, 0)) == 0,
        "Poster corners must be prepared before rendering",
      )
    } finally {
      image!!.recycle()
    }
  }

  private fun remove(file: File?) {
    val children: Array<File>? = file!!.listFiles()
    if (children != null) for (child: File in children) remove(child)
    file.delete()
  }
}
