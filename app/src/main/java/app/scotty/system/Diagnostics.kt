package app.scotty.system

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.core.content.FileProvider
import app.scotty.cast.CastController
import app.scotty.cast.Prefs
import java.io.File
import kotlin.concurrent.thread

/**
 * Collects Scotty's own log (protocol exchanges, receiver capabilities, audio stats) into a text
 * file and opens the share sheet. Pairing secrets are never logged.
 */
object Diagnostics {
    fun share(activity: Activity) {
        Toast.makeText(activity, "Collecting diagnostics…", Toast.LENGTH_SHORT).show()
        thread(name = "diagnostics") {
            val ctx = activity.applicationContext
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val report = buildString {
                appendLine("Scotty diagnostics")
                appendLine("App: ${info.versionName} (${info.longVersionCode})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
                appendLine("Notification access (song info): ${NowPlaying.isEnabled(ctx)}")
                appendLine("Silence phone while beaming: ${Prefs.muteLocal(ctx)}")
                appendLine("Last target: ${Prefs.lastTarget(ctx)}")
                appendLine("State: ${CastController.state.value}")
                appendLine()
                appendLine("---- log ----")
                append(
                    runCatching {
                        val p = Runtime.getRuntime().exec(
                            arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", "6000"),
                        )
                        p.inputStream.bufferedReader().readText()
                    }.getOrElse { "log unavailable: $it" },
                )
            }
            val dir = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(dir, "scotty-diagnostics.txt").apply { writeText(report) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Scotty diagnostics")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.runOnUiThread { activity.startActivity(Intent.createChooser(send, "Share diagnostics")) }
        }
    }
}
