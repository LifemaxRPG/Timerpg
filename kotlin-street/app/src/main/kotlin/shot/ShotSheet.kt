package com.timerpg.street.shot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timerpg.street.voice.PlayerVoice
import kotlin.math.min

/** A screenshot just taken, waiting for its note: the picture, when, how it was captured, and the game state then. */
class PendingShot(val bmp: Bitmap, val at: Long, val how: String, val game: Map<String, Any?>?)

/** Drawing the marks: on the screen (scaled to the view) and into the picture that is sent (the same code, scale 1). */
object ShotArt {
    fun DrawScope.drawMarks(marks: List<ShotMark>, imageWidth: Int) {
        val w = ShotMarks.stroke(imageWidth)
        for (m in marks) {
            val c = Color(m.color)
            when (m) {
                is ShotMark.Arrow -> {
                    val h = ShotMarks.head(m.x0, m.y0, m.x1, m.y1, w) ?: continue
                    drawLine(c, Offset(m.x0, m.y0), Offset(h.baseX, h.baseY), w, StrokeCap.Round)
                    drawPath(Path().apply { moveTo(h.tipX, h.tipY); lineTo(h.leftX, h.leftY); lineTo(h.rightX, h.rightY); close() }, c)
                }
                is ShotMark.Circle -> drawOval(c, Offset(min(m.x0, m.x1), min(m.y0, m.y1)),
                    Size(kotlin.math.abs(m.x1 - m.x0), kotlin.math.abs(m.y1 - m.y0)), style = Stroke(w))
                is ShotMark.Pen -> if (m.points.size == 1) drawCircle(c, w / 2, Offset(m.points[0].first, m.points[0].second)) else
                    drawPath(Path().apply { moveTo(m.points[0].first, m.points[0].second); for (p in m.points.drop(1)) lineTo(p.first, p.second) },
                        c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    /** A copy of [src] with the marks drawn into it (full resolution). */
    fun render(src: Bitmap, marks: List<ShotMark>): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val ib = out.asImageBitmap()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(ib), Size(src.width.toFloat(), src.height.toFloat())) {
            drawImage(src.asImageBitmap())
            drawMarks(marks, src.width)
        }
        return out
    }
}

// ---- dictation: the phone's on-device recognizer (never a cloud one), else the game's own, tap to start and tap to stop

private class Dictation(val ctx: Context, val ears: PlayerVoice) {
    var onDevice by mutableStateOf(false)       // the on-device recognizer is listening now
    var problem by mutableStateOf<String?>(null)
    private var rec: SpeechRecognizer? = null
    private var base = ""
    private var set: (String) -> Unit = {}
    /** The game's own recognizer is in use (hearing, or writing down). */
    val ownBusy get() = ears.state != PlayerVoice.State.IDLE
    val busy get() = onDevice || ownBusy
    val listening get() = onDevice || ears.state == PlayerVoice.State.LISTENING

    private val phoneOk get() = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)

    private fun join(a: String, b: String) = if (a.isBlank()) b else if (b.isBlank()) a else a.trimEnd() + " " + b

    fun toggle(current: String, setText: (String) -> Unit) {
        problem = null
        if (onDevice) { runCatching { rec?.stopListening() }; return }            // results follow
        if (ears.state == PlayerVoice.State.LISTENING) { ears.stop("") { setText(join(base, it)) }; return }
        if (busy) return
        base = current; set = setText
        if (phoneOk) startPhone() else {
            ears.start()
            problem = ears.problem
        }
    }

    private fun startPhone() {
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) { problem = "On-device speech recognition could not start"; return }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun text(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            override fun onPartialResults(p: Bundle?) { text(p).takeIf { it.isNotBlank() }?.let { set(join(base, it).take(300)) } }
            override fun onResults(b: Bundle?) { text(b).takeIf { it.isNotBlank() }?.let { set(join(base, it).take(300)) }; end() }
            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) problem = "Speech recognition failed (error $error)"
                else problem = "Didn't catch that"
                end()
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        onDevice = true
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        try { r.startListening(i) } catch (e: Exception) { problem = "On-device speech recognition could not start"; end() }
    }

    private fun end() { onDevice = false; runCatching { rec?.destroy() }; rec = null }

    fun release() { end(); if (ears.state == PlayerVoice.State.LISTENING) ears.stop("") {} }
}

// ---- the sheet

private val PANEL = Color(0xE6101420)
private val DIM = Color(0xFFB8C2D6)
private val BLUE = Color(0xCC3A6FD8)

@Composable
private fun Label(s: String, size: Int = 13, color: Color = Color.White) =
    BasicText(s, style = TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight.SemiBold))

@Composable
private fun Btn(label: String, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.background(if (on) BLUE else Color(0x99283050), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { Label(label) }

/**
 * The sheet after a screenshot: the picture small (tap it to draw on it), a note with a mic for dictating it, Send or
 * Cancel. [onSend] gets the note and the marks drawn (picture pixels). Full-screen and modal.
 */
@Composable
fun ShotSheet(s: PendingShot, ears: PlayerVoice, onSend: (String, List<ShotMark>) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }
    val marks = remember(s) { mutableStateListOf<ShotMark>() }
    var drawing by remember { mutableStateOf(false) }
    val img = remember(s) { s.bmp.asImageBitmap() }
    val dict = remember { Dictation(ctx, ears) }
    DisposableEffect(Unit) { onDispose { ShotSettings.typing = false; dict.release() } }
    fun toggleMic() = dict.toggle(note) { note = it.take(300) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleMic() else dict.problem = "The microphone is not allowed"
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).background(PANEL, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Send this screenshot to Claude", 15)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(120.dp).aspectRatio(img.width.toFloat() / img.height).border(1.dp, Color(0x66FFFFFF)).clickable { drawing = true }) {
                    ShotCanvas(img, marks, null, Modifier.matchParentSize())
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("Draw on it", marks.isNotEmpty()) { drawing = true }
                    if (marks.isNotEmpty()) Label("${marks.size} mark${if (marks.size > 1) "s" else ""}", 12, DIM)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(note, { note = it.take(300) },
                    Modifier.weight(1f).heightIn(min = 44.dp).background(Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 10.dp)
                        .onFocusChanged { ShotSettings.typing = it.isFocused },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner -> Box { if (note.isEmpty()) Label(if (dict.listening) "Listening…" else "A note (optional): type or use the mic", 13, DIM.copy(alpha = .7f)); inner() } })
                Btn(if (dict.listening) "Stop" else if (dict.busy) "…" else "Mic", dict.listening, Modifier.heightIn(min = 44.dp)) {
                    if (ears.hasPermission()) toggleMic() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            dict.problem?.let { Label(it, 12, Color(0xFFFFB0A0)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Send", true) { dict.release(); onSend(note, marks.toList()) }
                Btn("Cancel") { onCancel() }
            }
        }
        if (drawing) ShotEditor(img, marks) { drawing = false }
    }
}

/** The picture fitted into the box, with the marks (and the one being drawn) over it. Returns nothing: touches are the caller's. */
@Composable
private fun ShotCanvas(img: ImageBitmap, marks: List<ShotMark>, live: ShotMark?, modifier: Modifier) {
    Canvas(modifier) {
        val sc = min(size.width / img.width, size.height / img.height)
        val ox = (size.width - img.width * sc) / 2; val oy = (size.height - img.height * sc) / 2
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            drawImage(img, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            with(ShotArt) { drawMarks(if (live != null) marks + live else marks, img.width) }
        }
    }
}

private enum class Tool(val label: String) { ARROW("Arrow"), PEN("Pen"), CIRCLE("Circle") }

/** The picture full screen: drag to draw (arrow: the head is where the finger lifts), tools and colours at the side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotEditor(img: ImageBitmap, marks: SnapshotStateList<ShotMark>, onDone: () -> Unit) {
    BackHandler { onDone() }
    var tool by remember { mutableStateOf(Tool.ARROW) }
    var color by remember { mutableIntStateOf(ShotMarks.RED) }
    var live by remember { mutableStateOf<ShotMark?>(null) }
    val toolNow by rememberUpdatedState(tool)
    val colorNow by rememberUpdatedState(color)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF05070C))) {
        val wide = maxWidth > maxHeight
        val bar: @Composable () -> Unit = {
            Tool.entries.forEach { t -> Btn(t.label, tool == t) { tool = t } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShotMarks.COLORS.forEach { c ->
                    Box(Modifier.size(34.dp).background(Color(c), CircleShape).border(if (color == c) 3.dp else 1.dp, if (color == c) Color(0xFF6FA8FF) else Color(0x88000000), CircleShape)
                        .clickable { color = c })
                }
            }
            Btn("Undo") { if (marks.isNotEmpty()) marks.removeAt(marks.size - 1) }
            Btn("Clear") { marks.clear() }
            Btn("Done", true, onClick = onDone)
        }
        val pad = Modifier.background(Color(0xCC101420)).padding(8.dp)
        val canvas = Modifier.pointerInput(img) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                val sz: IntSize = size
                val sc = min(sz.width.toFloat() / img.width, sz.height.toFloat() / img.height)
                val ox = (sz.width - img.width * sc) / 2; val oy = (sz.height - img.height * sc) / 2
                fun at(o: Offset) = ((o.x - ox) / sc).coerceIn(0f, img.width.toFloat()) to ((o.y - oy) / sc).coerceIn(0f, img.height.toFloat())
                val w = ShotMarks.stroke(img.width)
                val (x0, y0) = at(down.position)
                val pts = arrayListOf(x0 to y0)
                var x1 = x0; var y1 = y0
                fun mk(): ShotMark = when (toolNow) {
                    Tool.ARROW -> ShotMark.Arrow(colorNow, x0, y0, x1, y1)
                    Tool.CIRCLE -> ShotMark.Circle(colorNow, x0, y0, x1, y1)
                    Tool.PEN -> ShotMark.Pen(colorNow, ShotMarks.thin(pts, w))
                }
                live = mk()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    val (x, y) = at(ch.position)
                    x1 = x; y1 = y; pts += x to y
                    ch.consume()
                    if (!ch.pressed) break
                    live = mk()
                }
                val m = mk()
                live = null
                val big = kotlin.math.hypot(x1 - x0, y1 - y0) >= ShotMarks.minLength(w)
                if (m is ShotMark.Pen || big) marks += m          // (a tap draws a dot with the pen, nothing with the others)
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            ShotCanvas(img, marks, live, Modifier.weight(1f).fillMaxHeight().then(canvas))
            Column(pad.fillMaxHeight().width(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) { bar() }
        } else Column(Modifier.fillMaxSize()) {
            ShotCanvas(img, marks, live, Modifier.weight(1f).fillMaxWidth().then(canvas))
            FlowRow(pad.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { bar() }
        }
    }
}
