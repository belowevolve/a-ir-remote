package belowevolve.tvhome

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Compose owns D-pad traversal; keep the launcher and the embedded TvView silent. */
open class QuietActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onRemoteBack()
                }
            },
        )
    }

    open fun onRemoteBack() {
        finish()
    }

    override fun onContentChanged() {
        super.onContentChanged()
        mute(window.decorView)
    }

    private fun mute(view: View) {
        view.isSoundEffectsEnabled = false
        if (view is ViewGroup) for (i in 0 until view.childCount) mute(view.getChildAt(i))
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        currentFocus?.isSoundEffectsEnabled = false
        return super.dispatchKeyEvent(event)
    }
}
