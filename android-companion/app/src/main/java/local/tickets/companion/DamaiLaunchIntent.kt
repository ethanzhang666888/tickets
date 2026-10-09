package local.tickets.companion

import android.content.Context
import android.content.Intent

/** Launcher-style task resume; never construct product routes or clear the existing picker. */
internal fun Context.damaiLaunchIntent(): Intent? = packageManager.getLaunchIntentForPackage(Safety.DAMAI)
    // PackageManager supplies NEW_TASK alone. Damai's task root is its routed home
    // activity, not its launcher splash; without RESET_TASK_IF_NEEDED Android can
    // push a new splash onto the prepared picker and Damai then returns home.
    ?.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
