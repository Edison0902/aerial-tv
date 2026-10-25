package app.orchard.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import java.time.LocalDateTime
import kotlin.math.roundToInt

// One large row of apps; the bar below hugs its four smaller tiles, centred, so it never reads
// as an empty slab.
private val RowTile = 200.dp
private val RowGap = 26.dp
private val DockTile = 148.dp
private val DockGap = 22.dp

/** The weather's condition in words, beside the temperature under the title. */
internal fun conditionFor(kind: app.orchard.tv.weather.WeatherKind): Int = when (kind) {
    app.orchard.tv.weather.WeatherKind.Clear -> app.orchard.tv.R.string.w_clear
    app.orchard.tv.weather.WeatherKind.PartlyCloudy -> app.orchard.tv.R.string.w_partly
    app.orchard.tv.weather.WeatherKind.Cloudy -> app.orchard.tv.R.string.w_cloudy
    app.orchard.tv.weather.WeatherKind.Fog -> app.orchard.tv.R.string.w_fog
    app.orchard.tv.weather.WeatherKind.Rain -> app.orchard.tv.R.string.w_rain
    app.orchard.tv.weather.WeatherKind.Snow -> app.orchard.tv.R.string.w_snow
    app.orchard.tv.weather.WeatherKind.Thunder -> app.orchard.tv.R.string.w_thunder
}

/**
 * The Apple TV home shape: one horizontal row of apps that scrolls sideways, and a fixed bar of
 * four favourites underneath. Moving left/right walks the row and the row follows; moving down
 * drops into the bar, up comes back. Nothing is stacked vertically, so nothing can hide behind
 * the bar. The focused app's name, with the date and weather, owns the top of the screen.
 */
@Composable
fun TvShelfHome(
    apps: List<AppEntry>,
    dockApps: List<AppEntry>,
    focusedKey: String?,
    dockFocusedKey: String?,
    title: String?,
    now: LocalDateTime,
    reset: Int = 0,
    requester: (String) -> FocusRequester,
    enabled: Boolean,
    movingKey: String?,
    onAppFocused: (AppEntry) -> Unit,
    onAppLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry, Rect?) -> Unit,
    onDockFocused: (AppEntry) -> Unit,
    onDockLaunch: (AppEntry, Rect?) -> Unit,
    onDockLongPress: (AppEntry, Rect?) -> Unit,
) {
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val density = LocalDensity.current
    val quiet = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = 56.dp
        val tileH = RowTile * 9f / 16f
        val pitch = RowTile + RowGap
        val rowY = maxHeight * 0.40f

        // One quiet line across the top — time, date, the reading now, the place — and the next
        // 24 hours draw as a full-width curve under it, well above the row.
        Row(
            Modifier.padding(start = margin, top = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText(timeText(now), style = Type.Title.copy(color = Color.White, shadow = quiet))
            val dim = Color.White.copy(alpha = 0.80f)
            Weather.now?.let { w ->
                GlyphIcon(glyphFor(w.kind, w.day), dim, 16.dp)
                BasicText(
                    stringResource(conditionFor(w.kind)) + " ${w.celsius}°",
                    style = Type.Callout.copy(color = dim),
                )
            }
            BasicText(dateText(now), style = Type.Callout.copy(color = dim))
            Weather.place?.takeIf { it.length <= 12 }?.let {
                BasicText(it, style = Type.Callout.copy(color = Color.White.copy(alpha = 0.6f)))
            }
        }
        if (Weather.hourly.size >= 3) TemperatureCurve(
            Weather.hourly,
            Modifier.padding(start = margin, end = margin, top = 76.dp),
        )

        // The row follows focus, the way tvOS keeps the selected icon in view

        // The row follows focus, the way tvOS keeps the selected icon in view: it only moves when
        // the selected app would leave the edges, instead of re-centring on every step. With the
        // focus elsewhere (the bar, or nothing yet) the row rests where it is.
        val scroll = remember { Animatable(0f) }
        val maxScroll = ((pitch * apps.size - RowGap) + margin * 2 - maxWidth)
            .coerceAtLeast(0.dp)
        val desired = when {
            focusedIndex < 0 -> null
            else -> {
                val tileLeft = margin + pitch * focusedIndex
                when {
                    tileLeft - scroll.value.dp < margin -> tileLeft - margin
                    tileLeft + RowTile - scroll.value.dp > maxWidth - margin -> tileLeft + RowTile - (maxWidth - margin)
                    else -> scroll.value.dp
                }.coerceIn(0.dp, maxScroll)
            }
        }
        if (desired != null) LaunchedEffect(desired) {
            scroll.animateTo(desired.value, spring(dampingRatio = 1f, stiffness = 190f))
        }
        // Coming back to Home, the row presents its first apps again.
        LaunchedEffect(reset) {
            if (reset > 0) scroll.animateTo(0f, spring(dampingRatio = 1f, stiffness = 190f))
        }

        // Row, clipped so apps scroll in and out from the edges.
        if (apps.isNotEmpty()) Box(
            Modifier
                .fillMaxWidth()
                .height(rowY + tileH + 54.dp)
                .clipToBounds(),
        ) {
            apps.forEachIndexed { index, app ->
                key(app.key) {
                    val base = with(density) {
                        IntOffset((margin + pitch * index).roundToPx(), rowY.roundToPx())
                    }
                    // Re-ordering animates: a moved app slides to its slot instead of jumping.
                    val slot by animateIntOffsetAsState(
                        base,
                        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                        label = "shelf-slot",
                    )
                    AppTile(
                        app = app,
                        width = RowTile,
                        height = tileH,
                        focusRequester = requester(app.key),
                        enabled = enabled,
                        moving = movingKey == app.key,
                        appearDelayMs = (index * 22).coerceAtMost(260),
                        onFocused = { onAppFocused(app) },
                        onClick = { b -> onAppLaunch(app, b) },
                        onLongPress = { b -> onAppLongPress(app, b) },
                        labels = false,
                        modifier = Modifier.offset {
                            IntOffset(slot.x - scroll.value.dp.roundToPx(), slot.y)
                        },
                    )
                }
            }
        }

        // The favourites bar with the focused app's name centred under it, Apple TV's arrangement.
        // Only the glass is clipped; the tiles sit above it, so a focused one can lift and show
        // its ring past the bar's edge.
        if (dockApps.isNotEmpty()) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp)),
            )
            Row(
                Modifier.padding(horizontal = 26.dp).padding(top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(DockGap),
            ) {
                dockApps.forEach { app ->
                    key("dock-" + app.key) {
                        AppTile(
                            app = app,
                            width = DockTile,
                            height = DockTile * 9f / 16f,
                            focusRequester = requester("dock:" + app.key),
                            enabled = enabled,
                            moving = movingKey == app.key,
                            appearDelayMs = 0,
                            onFocused = { onDockFocused(app) },
                            onClick = { b -> onDockLaunch(app, b) },
                            onLongPress = { b -> onDockLongPress(app, b) },
                            labels = false,
                            modifier = Modifier,
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * The next 24 hours of temperature as one quiet curve in the tvOS layout's empty left: a thin
 * line with a soft fill under it, markers and labels on the first, lowest, highest and last
 * points, and an hour tick every six hours.
 */
@Composable
private fun TemperatureCurve(hours: List<app.orchard.tv.weather.WeatherHour>, modifier: Modifier = Modifier) {
    // The whole curve, labels and ticks end well above the row: a focused tile lifts and rings
    // about 17 dp past its top edge, and the curve must clear that too.
    val h = 84.dp
    val top = 22.dp
    val bottom = 56.dp
    val temps = hours.map { it.celsius }
    val tmin = temps.min()
    val tmax = temps.max()
    val span = maxOf(1, tmax - tmin)
    val lowIdx = temps.indexOf(tmin)
    val highIdx = temps.indexOf(tmax)
    BoxWithConstraints(modifier) {
        val w = maxWidth
        fun px(i: Int) = w * i / (hours.size - 1)
        fun py(t: Int) = bottom - (bottom - top) * (t - tmin) / span

        Canvas(Modifier.fillMaxWidth().height(h)) {
            val line = Path()
            val fill = Path()
            hours.forEachIndexed { i, hh ->
                val x = px(i).toPx(); val y = py(hh.celsius).toPx()
                if (i == 0) { line.moveTo(x, y); fill.moveTo(x, bottom.toPx()) } else { line.lineTo(x, y) }
                fill.lineTo(x, y)
            }
            fill.lineTo(size.width, bottom.toPx()); fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(Color(0xFF78A0E6).copy(alpha = 0.22f), Color.Transparent), startY = top.toPx(), endY = bottom.toPx()))
            drawPath(line, Color(0xFFA8C8FF), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (i in setOf(0, lowIdx, highIdx, hours.lastIndex)) {
                drawCircle(Color.White, radius = 3.5.dp.toPx(), center = Offset(px(i).toPx(), py(temps[i]).toPx()))
            }
        }
        // Every label sits above its point; below the line there is nothing but the hour ticks,
        // so the whole block stays clear of a lifted, ringed tile in the row under it.
        val label = @Composable { i: Int, res: Int ->
            BasicText(
                stringResource(res) + " " + temps[i] + "°",
                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.82f)),
                modifier = Modifier.offset { IntOffset((px(i) - 30.dp).roundToPx().coerceIn(0, w.roundToPx() - 60.dp.roundToPx()), (py(temps[i]) - 24.dp).roundToPx()) },
            )
        }
        label(0, R.string.w_now)
        if (lowIdx != 0) label(lowIdx, R.string.w_low)
        if (highIdx != 0 && highIdx != hours.lastIndex) label(highIdx, R.string.w_high)
        if (hours.lastIndex !in setOf(0, lowIdx, highIdx)) {
            BasicText(
                "${hours[hours.lastIndex].hour}" + stringResource(R.string.w_hour) + " " + temps[hours.lastIndex] + "°",
                style = Type.Caption.copy(color = Color.White.copy(alpha = 0.82f)),
                modifier = Modifier.offset { IntOffset((px(hours.lastIndex) - 30.dp).roundToPx().coerceIn(0, w.roundToPx() - 60.dp.roundToPx()), (py(temps[hours.lastIndex]) - 24.dp).roundToPx()) },
            )
        }
        for (i in listOf(0, 6, 12, 18)) {
            hours.getOrNull(i)?.let { hh ->
                BasicText(
                    "${hh.hour}" + stringResource(R.string.w_hour),
                    style = Type.Caption.copy(color = Color.White.copy(alpha = 0.55f)),
                    modifier = Modifier.offset { IntOffset((px(i) - 14.dp).roundToPx().coerceIn(0, w.roundToPx() - 28.dp.roundToPx()), 62.dp.roundToPx()) },
                )
            }
        }
    }
}
