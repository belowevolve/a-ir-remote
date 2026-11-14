package belowevolve.tvhome

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process

/** Public read-only colors; button bindings remain restricted to film.mov. */
class ThemeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        if (method == "hdmi-state") {
            check(Binder.getCallingUid() == Process.myUid()) { "HDMI state is internal to Air Home" }
            return Bundle().apply {
                putBoolean("foreground", HdmiActivity.isForeground)
                putString("input", context.getSharedPreferences("home", 0).getString("last_hdmi", Hdmi.DEFAULT_INPUT))
            }
        }
        if (method == "bindings") {
            val caller = Binder.getCallingUid()
            check(caller == Process.myUid() || context.packageManager.getPackagesForUid(caller)?.contains("mov.film") == true) {
                "Button bindings are available only to the supported film.mov client"
            }
            return Bundle().apply {
                for (key in 183..186) putString(key.toString(), Actions.binding(context, key))
            }
        }
        require(method == "palette") { "Unknown theme method" }
        return WallpaperTheme.load(context)?.filmPalette() ?: Bundle.EMPTY
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
