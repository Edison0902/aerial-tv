package app.orchard.tv.ui.home

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.weather.WeatherNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

/** The current time, ticking on the minute. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE") }
    return now.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The home background, in two states as on tvOS. Focus in the top row: the picture itself. Focus
 * down in the grid: the whole screen turns to the picture frosted and darker, so the rows read
 * over anything.
 *
 * Everything follows [scroll] (the grid's scroll, [collapse] being where the grid has fully come
 * in), so the change is exactly as fast as the movement that causes it. Nothing is blurred or
 * tinted live: the frosted copy is baked small ahead of time (Images.frost) and darkened here
 * once. At rest a frame is one full-screen draw; both pictures are drawn only through the middle
 * of the scroll, since each frame that draws both costs a second full-screen fill (Xiaomi
 * MiTV-MFTR0: 90th percentile 20 -> 30 ms when the crossfade ran end to end).
 */
@Composable
fun HomeBackground(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    scroll: () -> Float,
    collapse: Float,
    alpha: Float = 1f,
    // The tvhome layout bakes a new backdrop per focused app and switches between two already
    // dark washes, where a crossfade would mean two full-screen draws on every navigation frame.
    crossfadeMs: Int = 450,
) {
    Crossfade(
        targetState = backdrop to frosted,
        animationSpec = tween(crossfadeMs, easing = Motion.EaseOut),
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { (bmp, frost) ->
        // In "Follow App" mode there is no frosted copy: the app's light is soft already and
        // changes with every move, so the grid darkens it live.
        val dark = remember(frost) { frost?.let { darkened(it, 0.55f) } }
        Canvas(Modifier.fillMaxSize()) {
            if (bmp == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            if (alpha < 0.999f) drawRect(Color.Black)
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val g = ((scroll() / collapse - 0.3f) / 0.4f).coerceIn(0f, 1f)
            if (dark == null) {
                drawImage(
                    bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = alpha,
                    colorFilter = if (g > 0.01f) ColorFilter.tint(Color.Black.copy(alpha = g * 0.5f), BlendMode.SrcAtop) else null,
                )
            } else {
                if (g < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = alpha)
                if (g > 0.01f) drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = g * alpha)
            }
        }
    }
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

// Baked dock backdrops, keyed per app. Caching keeps navigation from re-baking and re-uploading
// a texture on every focus change: a repeated visit shows the finished picture on its first frame.
private val dockBakes = android.util.LruCache<String, ImageBitmap>(5)

/**
 * The tvhome layout's backdrop, baked once per focused app: the owner's wallpaper, or the app's
 * own ambient light, over black, the app's tint glowing from behind the dock, and a shade that
 * keeps the title and the bar legible. Until a first bake lands, the app's 64×36 ambient light
 * stands in. Baked off the main thread, capped at 720p — a dark ambient wash the canvas
 * stretches — so Home still costs one full-screen draw per frame on a TV GPU.
 */
@Composable
fun rememberDockBackdrop(appId: String?, art: app.orchard.tv.apps.TileArt?, photo: ImageBitmap?, canvas: IntSize): ImageBitmap? {
    val scale = minOf(canvas.width / 960f, canvas.height / 540f, 4f / 3f)
    val w = (960 * scale).roundToInt()
    val h = (540 * scale).roundToInt()
    val cacheKey = "$appId/${System.identityHashCode(photo)}/$w"
    return produceState(initialValue = dockBakes.get(cacheKey) ?: art?.backdrop ?: photo, cacheKey) {
        if (dockBakes.get(cacheKey) != null) return@produceState
        withContext(Dispatchers.Default) {
            bakeDockBackdrop(art, photo, w, h)?.let { baked ->
                dockBakes.put(cacheKey, baked)
                value = baked
            }
        }
    }.value
}

private fun bakeDockBackdrop(art: app.orchard.tv.apps.TileArt?, photo: ImageBitmap?, w: Int, h: Int): ImageBitmap? {
    if (art == null && photo == null) return null
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas2d = android.graphics.Canvas(out)
    canvas2d.drawColor(android.graphics.Color.BLACK)

    val tint = (art?.tint ?: Color(0xFF3A6EA5)).toArgb()

    // With no wallpaper the backdrop is composed, not stretched artwork: a deep base with a wide
    // sweep of the app's tint from the upper left and a soft light over the title, which reads
    // cleaner than the app's 64×36 ambient light blown up (that washed out into mud).
    if (photo == null) {
        val sweep = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, w * 0.9f, h * 0.75f,
                intArrayOf(tint and 0x00FFFFFF or (android.graphics.Color.alpha(tint).coerceIn(60, 110) shl 24), 0x00000000),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas2d.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sweep)
        val base = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                w * 0.22f, h * 0.12f, w * 0.55f,
                intArrayOf(0x33FFFFFF, 0x00000000), floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas2d.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
    } else {
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
        canvas2d.drawBitmap(photo.asAndroidBitmap(), null, android.graphics.Rect(0, 0, w, h), paint)
    }

    // The tint glowing from behind the dock bar, and a shade keeping the title and tiles legible.
    art?.let {
        val clear = it.tint.copy(alpha = 0f).toArgb()
        val glow = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                w * 0.5f, h * 0.86f, w * 0.72f,
                intArrayOf(it.tint.copy(alpha = 0.22f).toArgb(), clear), floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas2d.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
    }

    val shade = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                android.graphics.Color.argb(150, 0, 0, 0),
                android.graphics.Color.argb(20, 0, 0, 0),
                android.graphics.Color.argb(70, 0, 0, 0),
                android.graphics.Color.argb(225, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.42f, 0.68f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas2d.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
    // A fine noise breaks up the gradient banding these dark washes show on big panels.
    canvas2d.drawBitmap(ditherTile(), null, android.graphics.Rect(0, 0, w, h), DITHER_PAINT)
    return out.asImageBitmap()
}

private val DITHER_PAINT = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = 10 }

private var dither: android.graphics.Bitmap? = null

/** A small blue-noise-ish tile, drawn stretched over dark backdrops to hide banding. */
@Synchronized
private fun ditherTile(): android.graphics.Bitmap {
    dither?.let { return it }
    val size = 96
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ALPHA_8)
    val px = ByteBuffer.allocate(size * size)
    val random = java.util.Random(7)
    for (i in 0 until size * size) px.put(((random.nextInt(24) - 12) + 12).toByte())
    px.rewind()
    bmp.copyPixelsFromBuffer(px)
    dither = bmp
    return bmp
}

/**
 * Date over a large, light clock: the lock-screen arrangement. The weather follows the date, with
 * the city it is for (issue #59). The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(now: LocalDateTime, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val dim = Color.White.copy(alpha = 0.86f)
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(dateText(now), style = Type.Title.copy(color = dim))
            Weather.now?.let { w ->
                GlyphIcon(glyphFor(w.kind, w.day), dim, 22.dp, Modifier.padding(start = 18.dp, end = 6.dp))
                val place = Weather.place?.takeIf { it.length <= 12 }
                BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Title.copy(color = dim))
            }
        }
        BasicText(timeText(now), style = Type.Clock, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * One-row Home: the time and the weather in one quiet line in the top left, level with the status
 * pill, so the wallpaper keeps the rest of the screen. A soft shadow keeps it readable on photos.
 */@Composable
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier) {
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(timeText(now), style = Type.Title.copy(color = dim, shadow = shadow))
        Weather.now?.let { w ->
            GlyphIcon(glyphFor(w.kind, w.day), dim, 18.dp, Modifier.padding(start = 16.dp, end = 6.dp))
            val place = Weather.place?.takeIf { it.length <= 12 }
            BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow))
        }
    }
}

internal fun glyphFor(kind: WeatherKind, day: Boolean): Glyph = when (kind) {
    WeatherKind.Clear -> if (day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}

/**
 * Top right: network and, once the shelf clock has scrolled away, the time. Focusable: it is
 * where Up from the top row leads, and it opens Control Center.
 */
@Composable
fun StatusPill(
    now: LocalDateTime,
    network: NetworkStatus,
    showTime: Boolean,
    showWeather: Boolean = false,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (network.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
            }
            GlyphIcon(glyph, fg, 17.dp, level = network.level)
            if (showWeather) Weather.now?.let { w ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlyphIcon(glyphFor(w.kind, w.day), fg, 17.dp)
                    BasicText("${w.celsius}°", style = Type.Headline.copy(color = fg))
                }
            }
            if (showTime || state.focused) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
