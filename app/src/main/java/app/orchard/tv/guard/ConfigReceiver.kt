package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.LayoutStore
import app.orchard.tv.apps.TileSheet
import app.orchard.tv.settings.AerialSettings
import kotlinx.coroutines.launch

/**
 * Options set from a computer over ADB, e.g.
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
 *   ... --ez own_screensaver true   (keep Aerial as the system screensaver; see GuardSetup)
 *   ... --ez clear_pin true         (turn the parental PIN off, for an owner who forgot it)
 *   ... --es set_icon "com.example.app:picture.png"   (a picture pushed to files/icon-inbox becomes the app's tile)
 * The manifest requires the sender to hold WRITE_SECURE_SETTINGS, which the ADB shell has and
 * ordinary apps cannot get, so no other app on the TV can flip these.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ... --es export_tiles <name>   (every tile on one picture; see TileSheet)
        // Runs on past the broadcast's time limit; Home keeps the process alive. Look for the file.
        intent.getStringExtra("export_tiles")?.takeIf { Regex("^[A-Za-z0-9_-]+$").matches(it) }?.let { name ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { TileSheet.export(app, name) }.onFailure { android.util.Log.e("AerialTiles", "Export failed", it) }
            }
            resultData = "exporting $name"
            return
        }
        // The same flow the phone page drives (CustomIcons.save), for a computer instead: push a
        // picture into files/icon-inbox over adb, then name the app and the file here.
        intent.getStringExtra("set_icon")?.takeIf {
            Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+:[A-Za-z0-9_.-]+$").matches(it)
        }?.let { spec ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val pkg = spec.substringBefore(':')
                CustomIcons.init(app)
                val file = java.io.File(app.getExternalFilesDir(null), "icon-inbox/" + spec.substringAfter(':'))
                val ok = CustomIcons.import(pkg, file)
                if (ok) {
                    file.delete()
                    CustomIcons.changed()
                }
                android.util.Log.i("AerialIcons", "set_icon $pkg -> $ok")
            }
            resultData = "setting icon"
            return
        }
        // Re-ordering and hiding from a computer: "pkg:+1" moves the app a slot right (negative
        // left), "pkg:true" hides it and "pkg:false" puts it back, exactly as the remote does.
        intent.getStringExtra("arrange")?.takeIf {
            Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+:([-+]?[0-9]+|true|false)$").matches(it)
        }?.let { spec ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val pkg = spec.substringBefore(':')
                val arg = spec.substringAfter(':')
                LayoutStore(app).let { store ->
                    when {
                        arg == "true" -> store.hide(pkg)
                        arg == "false" -> store.unhide(pkg)
                        else -> {
                            val visible = store.visibleOrder()
                            val from = visible.indexOf(pkg)
                            if (from >= 0) {
                                val to = (from + arg.toInt()).coerceIn(0, visible.lastIndex)
                                if (to != from) store.move(pkg, visible[to])
                            }
                        }
                    }
                }
                android.util.Log.i("AerialArrange", "arrange $spec done")
            }
            resultData = "arranging"
            return
        }
        if (intent.getBooleanExtra("clear_pin", false)) {
            AerialSettings.init(context)
            AerialSettings.choosePin(null)
        }
        if (intent.hasExtra("keep_adb")) GuardSetup.setKeepAdb(context, intent.getBooleanExtra("keep_adb", false))
        if (intent.hasExtra("own_screensaver")) GuardSetup.setOwnScreensaver(context, intent.getBooleanExtra("own_screensaver", false))
        GuardSetup.ensureEnabled(context)
        resultData = "keep_adb=${GuardSetup.keepAdb(context)}"
    }
}
