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
        val rowY = maxHeight * 0.47f

        // The focused app's name, large, in the empty upper half — the way tvOS lets the selected
        // app own the screen instead of showing a clock. Date and weather sit under it.
        Column(Modifier.padding(start = margin, top = 42.dp)) {
            Crossfade(
                targetState = title,
                animationSpec = tween(260),
                label = "shelf-title",
            ) { label ->
                BasicText(
                    text = label ?: "",
                    style = Type.Clock.copy(
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                        letterSpacing = (-0.02).em,
                        shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 16f),
                    ),
                )
            }
            // The date sits under the name; the weather lives on the right, in its own column.
            val dim = Color.White.copy(alpha = 0.82f)
            BasicText(
                dateText(now),
                style = Type.Title.copy(color = dim, shadow = quiet),
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        // Right column: the weather bubble over the hours ahead, aligned to the right margin.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 78.dp, end = margin),
            horizontalAlignment = Alignment.End,
        ) {
            Weather.now?.let { w ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f)),
                            ),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    GlyphIcon(glyphFor(w.kind, w.day), Color.White, 21.dp)
                    BasicText(
                        stringResource(conditionFor(w.kind)) + " ${w.celsius}°",
                        style = Type.Title.copy(color = Color.White, shadow = quiet),
                    )
                    Weather.place?.takeIf { it.length <= 12 }?.let {
                        BasicText(
                            it,
                            style = Type.Callout.copy(color = Color.White.copy(alpha = 0.75f)),
                        )
                    }
                }
            }
            if (Weather.hourly.size >= 2) {
                Row(
                    Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Weather.hourly.take(6).forEachIndexed { i, h ->
                        val bright = i == 0
                        val tone = Color.White.copy(alpha = if (bright) 0.92f else 0.62f)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BasicText(
                                if (bright) stringResource(R.string.w_now) else "${h.hour}" + stringResource(R.string.w_hour),
                                style = Type.Caption.copy(color = tone),
                            )
                            GlyphIcon(glyphFor(h.kind, h.day), tone, 17.dp, Modifier.padding(top = 5.dp, bottom = 3.dp))
                            BasicText("${h.celsius}°", style = Type.Headline.copy(color = if (bright) Color.White else tone))
                        }
                    }
                }
            }
        }

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

        // The favourites bar, always in the same place. Only the glass is clipped; the tiles sit
        // above it, so a focused one can lift and show its ring past the bar's edge.
        if (dockApps.isNotEmpty()) Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp),
        ) {
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
