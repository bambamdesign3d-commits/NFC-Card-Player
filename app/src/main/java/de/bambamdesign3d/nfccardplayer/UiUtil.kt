package de.bambamdesign3d.nfccardplayer

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object UiUtil {
    /** Hide status and navigation bar everywhere (no permission needed); swipe shows them briefly. */
    fun hideSystemBars(a: Activity) {
        WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
