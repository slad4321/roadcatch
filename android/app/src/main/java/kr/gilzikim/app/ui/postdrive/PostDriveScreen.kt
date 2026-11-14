package kr.gilzikim.app.ui.postdrive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TireRepair
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.launch
import kr.gilzikim.app.net.Place
import kr.gilzikim.app.net.PlaceClient
import kr.gilzikim.app.net.DemoAssets
import kr.gilzikim.app.net.TileClient
import kr.gilzikim.app.trip.Candidate
import kr.gilzikim.app.trip.CandidateReason
import kr.gilzikim.app.trip.HazardLabel
import kr.gilzikim.app.trip.Trip
import kr.gilzikim.app.trip.formatDistance
import kr.gilzikim.app.ui.theme.GzColors

/**
 * Post-drive review. Only shown while parked (the activity is not distraction optimized).
 * The trip runs full-bleed on a dark skeleton map; a floating panel on the right carries the
 * selected spot: when (oversized numerals), where, what the vehicle detected, and a one-tap
 * answer for what actually happened. In-car rules: no outlines, formal noun-form copy.
 */
@Composable
fun PostDriveScreen(trip: Trip, modifier: Modifier = Modifier) {
    // Open on the spot where the car may be damaged, if any: that is the one worth acting on.
    var selected by remember(trip.id) {
        mutableIntStateOf(trip.candidates.indexOfFirst { it.evidence.damage }.coerceAtLeast(0))
    }
    val labels = remember(trip.id) { mutableStateMapOf<String, HazardLabel>() }
    val places = remember(trip.id) { mutableStateMapOf<String, Place>() }
    val context = LocalContext.current
    LaunchedEffect(trip.id) {
        // Server first (it also knows the managing agency), then the car's own geocoder; anything
        // left unnamed shows the distance along the trip.
        places.putAll(PlaceClient.fetch(context, trip.candidates))
        trip.candidates.filter { it.id !in places }.forEach { c ->
            resolvePlaceName(context, c.lat, c.lon)?.let { places[c.id] = Place(it) }
        }
    }
    val allAnswered = trip.candidates.isNotEmpty() && labels.size == trip.candidates.size
    // The results replace the spot only when the driver says so ("완료"); opening a spot leaves them.
    var showResults by remember(trip.id) { mutableStateOf(false) }

    fun open(index: Int) {
        selected = index
        showResults = false
    }

    // A tapped answer is a draft until saved. Absent key: the draft follows the saved answer.
    val drafts = remember(trip.id) { mutableStateMapOf<String, HazardLabel?>() }
    fun draftOf(c: Candidate): HazardLabel? =
        if (c.id in drafts) drafts[c.id] else labels[c.id]?.takeUnless { it == HazardLabel.NONE }

    /**
     * Tapping an answer selects it; tapping the selected one again clears it, and if it was the
     * saved answer, the save is undone too, so what is highlighted always matches what is kept.
     * Nothing moves on its own.
     */
    fun pick(label: HazardLabel) {
        val c = trip.candidates.getOrNull(selected) ?: return
        if (draftOf(c) != label) {
            drafts[c.id] = label
            return
        }
        if (labels[c.id] == label) labels.remove(c.id)
        drafts[c.id] = null
    }

    fun save(label: HazardLabel) {
        val c = trip.candidates.getOrNull(selected) ?: return
        labels[c.id] = label
        drafts.remove(c.id)
    }

    /** Forward to the next unanswered spot, wrapping. */
    fun next() {
        val n = trip.candidates.size
        (1..n).map { (selected + it) % n }.firstOrNull { trip.candidates[it].id !in labels }?.let(::open)
    }

    Box(modifier = modifier.fillMaxSize().background(GzColors.Background)) {
        TripMap(
            trip, labels, places, selected,
            onSelect = ::open,
            rightInset = PANEL_WIDTH + PANEL_MARGIN,
            modifier = Modifier.fillMaxSize(),
        )
        TripLine(trip, Modifier.align(Alignment.TopStart).padding(horizontal = 20.dp, vertical = 16.dp))
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(PANEL_MARGIN)
                .width(PANEL_WIDTH).fillMaxHeight()
                .background(GzColors.Card, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            val c = trip.candidates.getOrNull(selected)
            when {
                c == null -> Unit
                showResults -> DonePanel(trip, labels, places, onOpen = ::open)
                else -> {
                    SpotHeader(
                        c, selected + 1, trip.candidates.size, places[c.id],
                        onPrev = { if (selected > 0) open(selected - 1) },
                        onNext = { if (selected < trip.candidates.lastIndex) open(selected + 1) },
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("감지 내용")
                    DetectedRows(c)
                    Spacer(Modifier.height(12.dp))
                    SituationPicker(
                        draft = draftOf(c), saved = labels[c.id], allAnswered = allAnswered,
                        onPick = ::pick, onSave = ::save, onNext = ::next, onFinish = { showResults = true },
                    )
                }
            }
        }
    }
}

private val PANEL_WIDTH = 352.dp
private val PANEL_MARGIN = 12.dp

// -------------------------------------------------------------------- map projection

private fun mercX(lon: Double) = (lon + 180.0) / 360.0

private fun mercY(lat: Double): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}

/**
 * Fits the trip into the part of the view the panel leaves free ([rightInset] px on the right), in
 * Web Mercator, and says which base map tiles cover the whole view.
 */
private class Viewport(trip: Trip, val width: Float, val height: Float, pad: Float, rightInset: Float) {
    private val xs = trip.points.map { mercX(it.lon) }
    private val ys = trip.points.map { mercY(it.lat) }
    private val cx = (xs.min() + xs.max()) / 2
    private val cy = (ys.min() + ys.max()) / 2
    private val freeWidth = width - rightInset
    // A trip that never moved (the emulator) has zero span; cap at street level instead.
    private val k = min(
        (freeWidth - 2 * pad) / max(xs.max() - xs.min(), 1e-9),
        (height - 2 * pad) / max(ys.max() - ys.min(), 1e-9),
    ).coerceAtMost(TILE_PX * 2.0.pow(MAX_ZOOM))
    /** Screen x of the trip's center: the middle of the free area, not of the whole view. */
    private val screenCx = freeWidth / 2

    /** Tile zoom: the next level up, so tiles are drawn slightly shrunk rather than blurred. */
    val zoom = ceil(log2(k / TILE_PX)).toInt().coerceIn(1, MAX_ZOOM)
    private val n = 2.0.pow(zoom)
    val tilePx = (k / n).toFloat()

    val tiles: List<Triple<Int, Int, Int>> = run {
        val x0 = floor((cx - screenCx / k) * n).toInt().coerceAtLeast(0)
        val x1 = floor((cx + (width - screenCx) / k) * n).toInt().coerceAtMost(n.toInt() - 1)
        val y0 = floor((cy - height / 2 / k) * n).toInt().coerceAtLeast(0)
        val y1 = floor((cy + height / 2 / k) * n).toInt().coerceAtMost(n.toInt() - 1)
        (x0..x1).flatMap { x -> (y0..y1).map { y -> Triple(zoom, x, y) } }
    }

    fun tileOffset(x: Int, y: Int) = Offset(
        ((x / n - cx) * k + screenCx).toFloat(),
        ((y / n - cy) * k + height / 2).toFloat(),
    )

    fun toOffset(lat: Double, lon: Double) = Offset(
        ((mercX(lon) - cx) * k + screenCx).toFloat(),
        ((mercY(lat) - cy) * k + height / 2).toFloat(),
    )

    private companion object {
        const val TILE_PX = 256.0
        const val MAX_ZOOM = 18
    }
}

/**
 * OpenStreetMap tiles turned into a dark skeleton. Only the near-white end of the tile survives:
 * land becomes a dark gray, roads a little lighter, while labels, buildings, water and casings
 * fall to black. What is left is the street grid, quiet enough for the route to lead.
 */
private val SkeletonFilter: ColorFilter = run {
    val a = 1.4f
    val offset = -317f
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                a * 0.299f, a * 0.587f, a * 0.114f, 0f, offset,
                a * 0.299f, a * 0.587f, a * 0.114f, 0f, offset + 1f,
                a * 0.299f, a * 0.587f, a * 0.114f, 0f, offset + 5f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

// -------------------------------------------------------------------- map

/** Every numbered marker is white; possible damage is a "!" badge on top, not a different color. */
private fun markerColor(c: Candidate, label: HazardLabel?): Color = when (label) {
    null -> GzColors.TextPrimary
    HazardLabel.NONE -> GzColors.Dismissed
    // Answered: still white, but dimmed, so what is left to answer stands out on the map.
    else -> GzColors.TextPrimary.copy(alpha = 0.55f)
}

private fun markerText(c: Candidate, label: HazardLabel?): Color =
    if (label == HazardLabel.NONE) GzColors.TextSecondary else GzColors.Card

/** Small red "!" at the marker's upper right, with a gap ring in the backdrop color. */
private fun DrawScope.drawDamageBadge(measurer: TextMeasurer, center: Offset, markerRadius: Float, backdrop: Color) {
    val at = Offset(center.x + markerRadius * 0.75f, center.y - markerRadius * 0.75f)
    drawCircle(backdrop, 10.dp.toPx(), at)
    drawCircle(GzColors.Hazard, 8.dp.toPx(), at)
    val mark = measurer.measure("!", TextStyle(Color.White, 12.sp, FontWeight.Bold))
    drawText(mark, topLeft = Offset(at.x - mark.size.width / 2f, at.y - mark.size.height / 2f))
}

private val clock = SimpleDateFormat("HH:mm", Locale.KOREA)

@Composable
private fun TripMap(
    trip: Trip,
    labels: Map<String, HazardLabel>,
    places: Map<String, Place>,
    selected: Int,
    onSelect: (Int) -> Unit,
    rightInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.background(GzColors.MapEmpty)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val viewport = remember(trip.id, w, h) {
            with(density) { Viewport(trip, w, h, 72.dp.toPx(), rightInset.toPx()) }
        }
        val tiles = remember(viewport) { mutableStateMapOf<Triple<Int, Int, Int>, ImageBitmap>() }
        // Without a tile server (release build, no network) the bundled road network is drawn instead.
        val context = LocalContext.current
        val roads = remember(viewport) {
            DemoAssets.roads(context).sortedByDescending { it.cls }.map { road ->
                road.cls to Path().apply {
                    for (i in road.latLon.indices step 2) {
                        val o = viewport.toOffset(road.latLon[i], road.latLon[i + 1])
                        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                }
            }
        }
        LaunchedEffect(viewport) {
            viewport.tiles.forEach { t ->
                TileClient.cached(t.first, t.second, t.third)?.let { tiles[t] = it }
            }
            viewport.tiles.filter { it !in tiles }.forEach { t ->
                launch { TileClient.load(t.first, t.second, t.third)?.let { tiles[t] = it } }
            }
        }

        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(viewport) {
                detectTapGestures { tap ->
                    val hit = trip.candidates.indices.minByOrNull {
                        val p = viewport.toOffset(trip.candidates[it].lat, trip.candidates[it].lon)
                        hypot(p.x - tap.x, p.y - tap.y)
                    } ?: return@detectTapGestures
                    val p = viewport.toOffset(trip.candidates[hit].lat, trip.candidates[hit].lon)
                    if (hypot(p.x - tap.x, p.y - tap.y) < 40.dp.toPx()) onSelect(hit)
                }
            },
        ) {
            if (tiles.isEmpty()) {
                roads.forEach { (cls, path) ->
                    val (color, width) = when (cls) {
                        0 -> GzColors.RoadMajor to 9.dp
                        1 -> GzColors.RoadMiddle to 6.dp
                        else -> GzColors.RoadMinor to 3.dp
                    }
                    drawPath(path, color, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            val size = ceil(viewport.tilePx).toInt() + 1
            tiles.forEach { (t, image) ->
                val o = viewport.tileOffset(t.second, t.third)
                drawImage(
                    image,
                    dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                    dstSize = IntSize(size, size),
                    colorFilter = SkeletonFilter,
                    filterQuality = FilterQuality.Medium,
                )
            }

            // The route glows and brightens toward the end, so the direction reads at a glance.
            val start = viewport.toOffset(trip.points.first().lat, trip.points.first().lon)
            val end = viewport.toOffset(trip.points.last().lat, trip.points.last().lon)
            val route = Path().apply {
                trip.points.forEachIndexed { i, p ->
                    val o = viewport.toOffset(p.lat, p.lon)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            }
            val round = { width: Float -> Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round) }
            drawPath(route, GzColors.Primary.copy(alpha = 0.10f), style = round(22.dp.toPx()))
            drawPath(route, GzColors.Primary.copy(alpha = 0.18f), style = round(11.dp.toPx()))
            drawPath(
                route,
                Brush.linearGradient(listOf(GzColors.Primary.copy(alpha = 0.35f), GzColors.Primary), start, end),
                style = round(5.dp.toPx()),
            )
            drawCircle(GzColors.Background, 6.dp.toPx(), start)
            drawCircle(GzColors.Primary.copy(alpha = 0.6f), 6.dp.toPx(), start, style = Stroke(2.5f.dp.toPx()))
            drawCircle(GzColors.TextPrimary, 9.dp.toPx(), end)
            drawCircle(GzColors.Background, 3.5f.dp.toPx(), end)

            // Unselected first, so the selected marker and its chip sit on top.
            trip.candidates.indices.sortedBy { it == selected }.forEach { i ->
                val c = trip.candidates[i]
                val o = viewport.toOffset(c.lat, c.lon)
                val color = markerColor(c, labels[c.id])
                val isSelected = i == selected
                val r = if (isSelected) 16.dp.toPx() else 12.dp.toPx()
                if (isSelected) {
                    drawCircle(color.copy(alpha = 0.12f), 44.dp.toPx(), o)
                    drawCircle(color.copy(alpha = 0.22f), 26.dp.toPx(), o)
                }
                drawCircle(color, r, o)
                val number = measurer.measure(
                    "${i + 1}",
                    TextStyle(markerText(c, labels[c.id]), if (isSelected) 16.sp else 13.sp, FontWeight.Bold),
                )
                drawText(number, topLeft = Offset(o.x - number.size.width / 2f, o.y - number.size.height / 2f))
                if (c.evidence.damage) drawDamageBadge(measurer, o, r, GzColors.MapEmpty)

                // Time first (people place a moment by when), the address under it.
                drawChip(measurer, o, r + 10.dp.toPx(), clock.format(Date(c.timeMillis)), places[c.id]?.name, isSelected)
            }

            // Attribution at the bottom right of the free map area, clear of the panel.
            val credit = measurer.measure("© OpenStreetMap", TextStyle(GzColors.TextMuted, 10.sp))
            val creditX = this.size.width - (PANEL_WIDTH + PANEL_MARGIN * 2).toPx() - credit.size.width
            drawText(credit, topLeft = Offset(creditX, this.size.height - credit.size.height - 6.dp.toPx()))
        }
    }
}

/** Dark chip beside a marker: when, then where in gray. Flips to the left near the panel. */
private fun DrawScope.drawChip(
    measurer: TextMeasurer,
    anchor: Offset,
    gap: Float,
    title: String,
    subtitle: String?,
    emphasized: Boolean,
) {
    val titleText = measurer.measure(
        title,
        TextStyle(GzColors.TextPrimary, if (emphasized) 16.sp else 14.sp, FontWeight.Medium),
    )
    val subText = subtitle?.let { measurer.measure(it, TextStyle(GzColors.TextSecondary, if (emphasized) 13.sp else 12.sp)) }
    val padH = 12.dp.toPx()
    val padV = 7.dp.toPx()
    val width = max(titleText.size.width.toFloat(), subText?.size?.width?.toFloat() ?: 0f) + 2 * padH
    val height = titleText.size.height + (subText?.let { it.size.height + 1.dp.toPx() } ?: 0f) + 2 * padV
    val margin = 8.dp.toPx()
    val left = if (anchor.x + gap + width <= size.width - margin) anchor.x + gap else anchor.x - gap - width
    val top = (anchor.y - height / 2).coerceIn(margin, max(margin, size.height - height - margin))
    drawRoundRect(GzColors.Chip, Offset(left, top), Size(width, height), CornerRadius(6.dp.toPx()))
    drawText(titleText, topLeft = Offset(left + padH, top + padV))
    subText?.let { drawText(it, topLeft = Offset(left + padH, top + padV + titleText.size.height + 1.dp.toPx())) }
}

// -------------------------------------------------------------------- side panel

@Composable
private fun TripLine(trip: Trip, modifier: Modifier = Modifier) {
    val length = if (trip.lengthM >= 1000) "%.1fkm".format(trip.lengthM / 1000) else "${trip.lengthM.toInt()}m"
    Text("${trip.title} · $length · ${trip.durationMinutes}분", color = GzColors.TextSecondary, fontSize = 14.sp, modifier = modifier)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = GzColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
}

/** Small gray label over an oversized time, as in the references; then the place. */
@Composable
private fun SpotHeader(
    c: Candidate,
    number: Int,
    total: Int,
    place: Place?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One classification per spot, all at the same level: what the vehicle suspects.
            Text(
                when {
                    c.evidence.damage -> "차량 손상 의심"
                    c.reasons == listOf(CandidateReason.VOICE) -> "음성 신고"
                    else -> "도로 위험 의심"
                },
                color = if (c.evidence.damage) GzColors.Hazard else GzColors.TextSecondary,
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            StepArrow(Icons.Rounded.ChevronLeft, enabled = number > 1, onClick = onPrev)
            Text("$number / $total", color = GzColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
            StepArrow(Icons.Rounded.ChevronRight, enabled = number < total, onClick = onNext)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                clock.format(Date(c.timeMillis)),
                color = GzColors.TextPrimary, fontSize = 46.sp, fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp, lineHeight = 50.sp,
            )
        }
        Text(
            place?.name ?: "출발 후 ${formatDistance(c.distanceFromStartM)}",
            color = GzColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        )
        // Always takes its line, so the buttons below do not jump when the address arrives.
        Text(place?.agency?.let { "관할: $it" } ?: "", color = GzColors.TextSecondary, fontSize = 14.sp)
    }
}

private data class DetectedRow(val icon: ImageVector, val label: String, val value: String, val alert: Boolean = false)

/** What the vehicle detected, one line per signal, named in formal noun form. */
private fun detectedRows(c: Candidate): List<DetectedRow> {
    val e = c.evidence
    val speed = e.speedKmh?.let { "${it.roundToInt()} km/h" } ?: ""
    val rows = c.reasons.mapNotNull { r ->
        when (r) {
            CandidateReason.SWERVE -> DetectedRow(
                Icons.Rounded.AltRoute, r.label,
                listOfNotNull(e.steeringDeg?.let { "${it.roundToInt()}°" }, "방향지시등 미작동".takeUnless { e.turnSignal }).joinToString(" · "),
            )
            CandidateReason.HARD_BRAKE -> DetectedRow(
                Icons.Rounded.Speed, r.label,
                if (e.speedKmh != null && e.lowestKmh != null) "${e.speedKmh.roundToInt()} → ${e.lowestKmh.roundToInt()} km/h" else speed,
            )
            CandidateReason.IMPACT -> DetectedRow(Icons.Rounded.Vibration, r.label, if ((e.impactG ?: 0.0) >= 0.8) "강함" else "보통", alert = true)
            CandidateReason.TPMS -> null
            CandidateReason.ICY_ROAD -> DetectedRow(Icons.Rounded.AcUnit, r.label, speed)
            CandidateReason.VOICE -> DetectedRow(Icons.Rounded.Mic, r.label, speed)
            else -> DetectedRow(Icons.Rounded.ReportProblem, r.label, speed)
        }
    }
    return if (e.lowTire) rows + DetectedRow(Icons.Rounded.TireRepair, CandidateReason.TPMS.label, "감지", alert = true) else rows
}

/**
 * Read-only rows: no fill, only hairline dividers between them, so they never look like the
 * filled buttons below (which are the only things to tap).
 */
@Composable
private fun DetectedRows(c: Candidate) {
    RowList(detectedRows(c)) { row ->
        InfoRow(row.label, row.value, icon = row.icon, alert = row.alert, strongLabel = true)
    }
}

@Composable
private fun <T> RowList(items: List<T>, row: @Composable (T) -> Unit) {
    Column {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(GzColors.Divider))
            row(item)
        }
    }
}

/**
 * [strongLabel]: the label is the point (what the vehicle detected) and the value a dim detail.
 * Otherwise the label is a field name and the value is what matters (the damage record).
 */
@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector? = null, alert: Boolean = false, strongLabel: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (alert) GzColors.Hazard else GzColors.TextPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = if (strongLabel) GzColors.TextPrimary else GzColors.TextSecondary,
            fontSize = if (strongLabel) 15.sp else 14.sp,
            fontWeight = if (strongLabel) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            color = when {
                alert -> GzColors.Hazard.copy(alpha = if (strongLabel) 0.75f else 1f)
                strongLabel -> GzColors.TextSecondary
                else -> GzColors.TextPrimary
            },
            fontSize = if (strongLabel) 13.sp else 14.sp,
            fontWeight = if (strongLabel) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

/**
 * One tap. The four kinds of trouble sit together; "이상 없음" is set apart at the bottom, so
 * "something was there" and "nothing was there" read as different choices.
 */
@Composable
private fun ColumnScope.SituationPicker(
    draft: HazardLabel?,
    saved: HazardLabel?,
    allAnswered: Boolean,
    onPick: (HazardLabel) -> Unit,
    onSave: (HazardLabel) -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    SectionLabel(
        when (saved) {
            null -> "발생 상황"
            HazardLabel.NONE -> "발생 상황 · 이상 없음 저장됨"
            else -> "발생 상황 · 저장됨"
        },
    )
    listOf(
        listOf(HazardLabel.POTHOLE, HazardLabel.DEBRIS),
        listOf(HazardLabel.SLIPPERY, HazardLabel.OTHER),
    ).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            row.forEach { label ->
                ChoiceButton(
                    label.label, selected = draft == label,
                    icon = if (label == HazardLabel.OTHER) Icons.Rounded.Mic else null,
                    onClick = { onPick(label) }, modifier = Modifier.weight(1f),
                )
            }
        }
    }
    // At least this much air before the bottom button: its separate place sets it apart.
    Spacer(Modifier.height(12.dp))
    Spacer(Modifier.weight(1f))
    // One bottom button whose job follows the state: answer "nothing", save the pick, or move on.
    when {
        draft != null && draft != saved ->
            ChoiceButton("저장", selected = false, primary = true, onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth())
        draft == null && saved != HazardLabel.NONE ->
            ChoiceButton(HazardLabel.NONE.label, selected = false, onClick = { onSave(HazardLabel.NONE) }, modifier = Modifier.fillMaxWidth())
        allAnswered ->
            ChoiceButton("완료", selected = false, primary = true, onClick = onFinish, modifier = Modifier.fillMaxWidth())
        else ->
            ChoiceButton("다음 지점", selected = false, onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    val background = if (selected || primary) GzColors.Primary else GzColors.Raised
    val content = if (selected || primary) GzColors.OnPrimary else GzColors.TextPrimary
    Row(
        modifier = modifier.height(50.dp).clip(RoundedCornerShape(8.dp)).background(background).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Previous / next spot. A filled circle, dimmed at either end of the list. */
@Composable
private fun StepArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(GzColors.Raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) GzColors.TextPrimary else GzColors.TextMuted, modifier = Modifier.size(22.dp))
    }
}

/**
 * Totals as oversized numerals, then one row per report with where that report is now. Each
 * report moves on its own (접수 → 교차 확인 → 전달 → 보수), so progress belongs to the row,
 * not to the trip.
 */
@Composable
private fun ColumnScope.DonePanel(
    trip: Trip,
    labels: Map<String, HazardLabel>,
    places: Map<String, Place>,
    onOpen: (Int) -> Unit,
) {
    // A damage spot answered with an actual situation keeps its damage record.
    val damaged = trip.candidates.filter { it.evidence.damage && labels[it.id].let { l -> l != null && l != HazardLabel.NONE } }
    val reports = trip.candidates.withIndex().filter { (_, c) -> labels[c.id].let { it != null && it != HazardLabel.NONE } }
    Text("확인 완료", color = GzColors.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(
            Triple("신고", reports.size, false),
            Triple("이상 없음", labels.values.count { it == HazardLabel.NONE }, false),
            Triple("손상 기록", damaged.size, damaged.isNotEmpty()),
        ).forEach { (k, n, alert) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$n", color = if (alert) GzColors.Hazard else GzColors.TextPrimary,
                    fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 48.sp,
                )
                Text(k, color = GzColors.TextSecondary, fontSize = 14.sp)
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    SectionLabel("신고 진행")
    RowList(reports) { (i, c) ->
        // Right after review every report has only been received.
        ReportRow(i + 1, c, labels[c.id], places[c.id], damage = c in damaged, stage = 0, onClick = { onOpen(i) })
    }
}

private val STAGES = listOf("접수", "교차 확인", "전달", "보수")

/**
 * One report. The time is the title (people place a moment by when it happened), the situation
 * and place sit under it, and the report's own progress runs full width underneath.
 */
@Composable
private fun ReportRow(number: Int, c: Candidate, label: HazardLabel?, place: Place?, damage: Boolean, stage: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberBadge(number, damage = c.evidence.damage)
            Spacer(Modifier.width(10.dp))
            Text(clock.format(Date(c.timeMillis)), color = GzColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (damage) Text("손상 기록", color = GzColors.Hazard, fontSize = 13.sp)
        }
        Text(
            listOfNotNull(label?.label, place?.name).joinToString(" · "),
            color = GzColors.TextSecondary, fontSize = 14.sp, maxLines = 1,
            modifier = Modifier.padding(start = 34.dp, top = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        StageBar(stage)
    }
}

/** The report's four stages on one track; reached stages take the primary color. */
@Composable
private fun StageBar(stage: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        STAGES.forEachIndexed { i, name ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    val y = size.height / 2
                    val cx = size.width / 2
                    val track = 2.5f.dp.toPx()
                    if (i > 0) drawLine(if (i <= stage) GzColors.Primary else GzColors.Track, Offset(0f, y), Offset(cx, y), track)
                    if (i < STAGES.lastIndex) drawLine(if (i < stage) GzColors.Primary else GzColors.Track, Offset(cx, y), Offset(size.width, y), track)
                    if (i == stage) drawCircle(GzColors.Primary.copy(alpha = 0.2f), 7.dp.toPx(), Offset(cx, y))
                    drawCircle(if (i <= stage) GzColors.Primary else GzColors.Track, if (i == stage) 4.5f.dp.toPx() else 3.5f.dp.toPx(), Offset(cx, y))
                }
                Text(
                    name,
                    color = if (i == stage) GzColors.Primary else GzColors.TextMuted,
                    fontSize = 12.sp, fontWeight = if (i == stage) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** White numbered dot, as on the map, with the red "!" badge when the car may be damaged. */
@Composable
private fun NumberBadge(number: Int, damage: Boolean) {
    Box(Modifier.size(30.dp)) {
        Box(
            Modifier.size(24.dp).align(Alignment.BottomStart).background(GzColors.TextPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = GzColors.Card, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (damage) {
            Box(
                Modifier.size(17.dp).align(Alignment.TopEnd).background(GzColors.Card, CircleShape).padding(2.dp)
                    .background(GzColors.Hazard, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
            }
        }
    }
}
