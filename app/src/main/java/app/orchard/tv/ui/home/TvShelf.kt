package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlin.math.roundToInt

/**
 * The Apple TV home shape: one horizontal row of apps that scrolls sideways, and a fixed bar of
 * four favourites underneath. Moving left/right walks the row and the row follows; moving down
 * drops into the bar, up comes back. Nothing is stacked vertically, so nothing can hide behind
 * the bar.
 */
@Composable
fun TvShelfHome(
    apps: List<AppEntry>,
    dockApps: List<AppEntry>,
    focusedKey: String?,
    dockFocusedKey: String?,
    title: String?,
    requester: (String) -> FocusRequester,
    enabled: Boolean,
    movingKey: String?,
    onAppFocused: (AppEntry) -> Unit,
    onAppLaunch: (AppEntry, Rect?) -> Unit,
    onAppLongPress: (AppEntry, Rect?) -> Unit,
    onDockFocused: (AppEntry) -> Unit,
    onDockLaunch: (AppEntry, Rect?) -> Unit,
) {
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = 56.dp
        val gap = 22.dp
        val tileW = 140.dp
        val tileH = tileW * 9f / 16f
        val pitch = tileW + gap
        val rowY = maxHeight * 0.42f

        // The focused app's name, large, in the empty upper half — the way tvOS lets the
        // selected app own the screen instead of showing a clock.
        androidx.compose.animation.Crossfade(
            targetState = title,
            animationSpec = androidx.compose.animation.core.tween(260),
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
                modifier = Modifier.padding(start = margin, top = 48.dp),
            )
        }

        // The row follows focus, the way tvOS keeps the selected icon in view: it only moves when
        // the selected app would leave the edges, instead of re-centring on every step.
        val scroll = remember { Animatable(0f) }
        val maxScroll = ((pitch * apps.size - gap) + margin * 2 - maxWidth)
            .coerceAtLeast(0.dp)
        val current = scroll.value.dp
        val tileLeft = margin + pitch * focusedIndex.coerceAtLeast(0)
        val desired = when {
            focusedIndex < 0 -> current
            tileLeft - current < margin -> tileLeft - margin
            tileLeft + tileW - current > maxWidth - margin -> tileLeft + tileW - (maxWidth - margin)
            else -> current
        }.coerceIn(0.dp, maxScroll)
        LaunchedEffect(desired) {
            scroll.animateTo(desired.value, spring(dampingRatio = 1f, stiffness = 190f))
        }

        // Row, clipped so apps scroll in and out from the edges.
        Box(
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
                        spring(dampingRatio = 0.86f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                        label = "shelf-slot",
                    )
                    AppTile(
                        app = app,
                        width = tileW,
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

        // The favourites bar, always in the same place.
        val dockTileW = 150.dp
        val dockTileH = dockTileW * 9f / 16f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(dockTileH + 44.dp),
        ) {
            val inset = 28.dp
            val top = 8.dp
            Box(
                Modifier
                    .padding(horizontal = inset)
                    .padding(top = top, bottom = 10.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.115f), Color.White.copy(alpha = 0.045f)),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
            )
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = margin),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
            ) {
                dockApps.forEach { app ->
                    key("dock-" + app.key) {
                        AppTile(
                            app = app,
                            width = dockTileW,
                            height = dockTileH,
                            focusRequester = requester("dock:" + app.key),
                            enabled = enabled,
                            moving = false,
                            appearDelayMs = 0,
                            onFocused = { onDockFocused(app) },
                            onClick = { b -> onDockLaunch(app, b) },
                            onLongPress = { },
                            labels = false,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}
