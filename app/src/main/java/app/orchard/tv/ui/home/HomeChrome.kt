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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
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
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The current time, ticking on the minute (or every second in the tvhome layout). */
@Composable
fun rememberNow(everySecond: Boolean = false): State<LocalDateTime> = produceState(LocalDateTime.now(), everySecond) {
    val step = if (everySecond) 1_000L else 60_000L
    while (true) {
        value = LocalDateTime.now()
        delay(step - System.currentTimeMillis() % step + 30L)
    }
}

@Composable
fun timeText(now: LocalDateTime, seconds: Boolean = false): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = when {
        twelve && seconds -> "h:mm:ss"
        twelve -> "h:mm"
        seconds -> "H:mm:ss"
        else -> "H:mm"
    }
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
) {
    Crossfade(
        targetState = backdrop to frosted,
        animationSpec = tween(450, easing = Motion.EaseOut),
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

/**
 * The tvhome layout's backdrop, baked once per focused app: the app's own blurred artwork over
 * black, its tint glowing from behind the dock, and a shade that keeps the clock and the bar
 * legible. Baked so Home still costs one full-screen draw per frame on a TV GPU.
 */
@Composable
fun rememberDockBackdrop(art: app.orchard.tv.apps.TileArt?, photo: ImageBitmap?): ImageBitmap? = remember(art, photo) {
    if (art == null && photo == null) return@remember null
    val w = 960
    val h = 540
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)

    // The owner's wallpaper, if there is one: a dark photograph reads far more like tvOS than a
    // wash of the app's colour. Otherwise the focused app's own artwork.
    val source = photo ?: art?.backdrop
    if (source != null) {
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            alpha = if (photo != null) 205 else 165
        }
        canvas.drawBitmap(source.asAndroidBitmap(), null, android.graphics.Rect(0, 0, w, h), paint)
    }

    art?.let {
        val tint = it.tint.copy(alpha = 0.20f).toArgb()
        val clear = it.tint.copy(alpha = 0f).toArgb()
        val glow = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                w * 0.5f, h * 0.86f, w * 0.72f,
                intArrayOf(tint, clear), floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), glow)
    }

    val shade = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                android.graphics.Color.argb(185, 0, 0, 0),
                android.graphics.Color.argb(40, 0, 0, 0),
                android.graphics.Color.argb(90, 0, 0, 0),
                android.graphics.Color.argb(235, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.40f, 0.66f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
    out.asImageBitmap()
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
                GlyphIcon(glyphFor(w), dim, 22.dp, Modifier.padding(start = 18.dp, end = 6.dp))
                val place = Weather.place?.takeIf { it.length <= 12 }
                BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Title.copy(color = dim))
            }
        }
        BasicText(timeText(now), style = Type.Clock, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * tvhome-zh's clock, ported for AerialSettings.dockHome: a greeting over a thin clock that counts
 * seconds, with the date beside it. Same top-left corner as ShelfClock, so the rest of Home lines
 * up with the standard layout.
 */
@Composable
fun TvHomeClock(now: LocalDateTime, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val dim = Color.White.copy(alpha = 0.86f)
    val quiet = Color.White.copy(alpha = 0.62f)
    val greeting = when (now.hour) {
        in 5..10 -> R.string.greeting_morning
        in 11..13 -> R.string.greeting_noon
        in 14..17 -> R.string.greeting_afternoon
        in 18..22 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        BasicText(
            stringResource(greeting),
            style = Type.Callout.copy(color = quiet, letterSpacing = 0.18.em),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                timeText(now, seconds = true),
                style = Type.Clock.copy(fontSize = 96.sp, lineHeight = 96.sp),
            )
            BasicText(
                dateText(now),
                style = Type.Headline.copy(color = dim),
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )
        }
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
            GlyphIcon(glyphFor(w), dim, 18.dp, Modifier.padding(start = 16.dp, end = 6.dp))
            val place = Weather.place?.takeIf { it.length <= 12 }
            BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow))
        }
    }
}

private fun glyphFor(w: WeatherNow): Glyph = when (w.kind) {
    WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
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
                    GlyphIcon(glyphFor(w), fg, 17.dp)
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
