package belowevolve.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import java.text.Collator
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashMap

/* Installed apps with a TV or regular launcher entry; one row per package. */
object AppCatalog {
  private var cached: List<App> = emptyList()
  private var packagesKey = ""
  private val HIDDEN: Set<String> =
    HashSet<String>(
      Arrays.asList<String>(
        "com.android.tv.settings",
        "com.example.bt_pair_tips",
        "mediatek.factorymenu.ui",
      )
    )

  class App(val packageName: String, val label: String, val icon: Bitmap)

  fun hidden(context: Context): Set<String> =
    context.getSharedPreferences("apps", 0).getStringSet("hidden", emptySet())?.toSet().orEmpty()

  fun setHidden(context: Context, packageName: String, hide: Boolean) {
    val packages = hidden(context).toMutableSet()
    if (hide) packages.add(packageName) else packages.remove(packageName)
    context.getSharedPreferences("apps", 0).edit().putStringSet("hidden", packages).apply()
  }

  private fun visible(context: Context, apps: List<App>, includeHidden: Boolean): ArrayList<App> {
    val hidden = if (includeHidden) emptySet() else hidden(context)
    return ArrayList(apps.filterNot { it.packageName in hidden })
  }

  fun load(context: Context, includeHidden: Boolean = false): ArrayList<App> {
    val pm: PackageManager = context.getPackageManager()
    val version =
      pm.getInstalledPackages(0).joinToString {
        "${it.packageName}:${it.lastUpdateTime}:${it.applicationInfo?.enabled}"
      }
    synchronized(this) {
      if (version == packagesKey && cached.isNotEmpty()) return visible(context, cached, includeHidden)
    }
    val apps: LinkedHashMap<String, App> = LinkedHashMap<String, App>()
    for (category: String in
      arrayOf<String>(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
      val intent: Intent = Intent(Intent.ACTION_MAIN).addCategory(category)
      for (info: ResolveInfo in pm.queryIntentActivities(intent, 0)) {
        val pkg: String = info.activityInfo.packageName
        if (pkg == context.getPackageName() || HIDDEN.contains(pkg) || apps.containsKey(pkg))
          continue
        val label: CharSequence? = info.loadLabel(pm)
        val drawable = info.loadIcon(pm)
        val size = Ui.dp(context, 24f).coerceAtLeast(1)
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(icon))
        icon.prepareToDraw()
        apps.put(
          pkg,
          App(pkg, if (label == null) pkg else label.toString(), icon),
        )
      }
    }
    val result: ArrayList<App> = ArrayList<App>(apps.values)
    val order: Collator = Collator.getInstance()
    result.sortWith { a, b -> order.compare(a.label, b.label) }
    synchronized(this) {
      cached = result
      packagesKey = version
    }
    return visible(context, result, includeHidden)
  }
}
