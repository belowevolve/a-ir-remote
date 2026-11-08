package belowevolve.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import java.text.Collator
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet
import java.util.LinkedHashMap

/* Installed apps with a TV or regular launcher entry; one row per package. */
object AppCatalog {
    private val HIDDEN: Set<String> =
        HashSet<String>(
            Arrays.asList<String>(
                "com.android.tv.settings",
                "com.example.bt_pair_tips",
                "mediatek.factorymenu.ui",
            ),
        )

    class App(val packageName: String, val label: String, val icon: Drawable)

    fun load(context: Context): ArrayList<App> {
        val pm: PackageManager = context.getPackageManager()
        val apps: LinkedHashMap<String, App> = LinkedHashMap<String, App>()
        for (category: String in
            arrayOf<String>(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent: Intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info: ResolveInfo in pm.queryIntentActivities(intent, 0)) {
                val pkg: String = info.activityInfo.packageName
                if (
                    pkg == context.getPackageName() || HIDDEN.contains(pkg) || apps.containsKey(pkg)
                )
                    continue
                val label: CharSequence? = info.loadLabel(pm)
                apps.put(
                    pkg,
                    App(pkg, if (label == null) pkg else label.toString(), info.loadIcon(pm)),
                )
            }
        }
        val result: ArrayList<App> = ArrayList<App>(apps.values)
        val order: Collator = Collator.getInstance()
        result.sortWith { a, b -> order.compare(a.label, b.label) }
        return result
    }
}
