package cat.alysis.foldusage

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Edge-to-edge is enforced at targetSdk 35+: pad [root] for system bars, cutouts
 * and the keyboard so no content sits under the status or navigation bar.
 */
fun applySystemInsets(root: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        root.fitsSystemWindows = true
        return
    }
    root.setOnApplyWindowInsetsListener { v, insets ->
        val i = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        v.setPadding(i.left, i.top, i.right, i.bottom)
        WindowInsets.CONSUMED
    }
}
