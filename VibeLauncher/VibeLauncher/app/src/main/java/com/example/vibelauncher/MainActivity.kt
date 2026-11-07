package com.example.vibelauncher

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.ui.draw.clip
import android.view.KeyEvent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontFamily
import java.util.Calendar
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Change these to your location for the weather widget.
const val LAT = 43.32   // Nis (Trosarina is part of Nis)
const val LON = 21.90
const val PLACE = "Niš"
val SR: Locale = Locale.forLanguageTag("sr-Latn-RS")

// App tile size. Make TILE_H bigger for taller tiles, TILE_W for wider ones.
val TILE_W: Dp = 200.dp
val TILE_H: Dp = 140.dp

const val IDLE_MS = 30 * 60 * 1000L   // screensaver after 30 min idle (use 60_000L to test it)
var lastInput by mutableLongStateOf(0L)
var saverOn by mutableStateOf(false)

// Rounded UI font. The GitHub build downloads app/src/main/assets/fonts/rounded.ttf (Varela Round);
// if the file is missing the launcher falls back to the system font.
var UiFont: FontFamily = FontFamily.SansSerif

fun uiFont(ctx: Context): FontFamily = try {
    Typeface.createFromAsset(ctx.assets, "fonts/rounded.ttf")
    FontFamily(Font("fonts/rounded.ttf", ctx.assets))
} catch (e: Exception) { FontFamily.SansSerif }

data class App(val label: String, val pkg: String, val icon: Bitmap, val isBanner: Boolean)
data class Orb(val x: Float, val y: Float, val r: Float, val speed: Int, val ph: Float, val hue: Color)
data class Day(val label: String, val code: Int, val hi: Int, val lo: Int)
data class Wx(val temp: Int, val code: Int, val wind: Int, val days: List<Day>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiFont = uiFont(this)
        setContent { Launcher() }
    }

    override fun onResume() {
        super.onResume()
        saverOn = false
        lastInput = System.currentTimeMillis()
    }

    override fun onUserInteraction() { lastInput = System.currentTimeMillis() }

    // While the screensaver is showing, the first key press only wakes it up.
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (saverOn) { if (e.action == KeyEvent.ACTION_DOWN) saverOn = false; return true }
        return super.dispatchKeyEvent(e)
    }
}

fun loadApps(pm: PackageManager, self: String): List<App> {
    val out = LinkedHashMap<String, App>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
        for (ri in pm.queryIntentActivities(q, 0)) {
            val p = ri.activityInfo.packageName
            if (p == self || out.containsKey(p)) continue
            val banner = ri.activityInfo.loadBanner(pm)
            out[p] = if (banner != null) App(ri.loadLabel(pm).toString(), p, banner.toBitmap(320, 180), true)
                     else App(ri.loadLabel(pm).toString(), p, ri.loadIcon(pm).toBitmap(192, 192), false)
        }
    }
    return out.values.sortedBy { it.label.lowercase() }
}

fun launch(ctx: Context, pkg: String) {
    val pm = ctx.packageManager
    (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.let { ctx.startActivity(it) }
}

suspend fun fetchWx(): Wx? = withContext(Dispatchers.IO) {
    try {
        val u = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto"
        val j = JSONObject(URL(u).readText())
        val c = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = (1..3).map { i ->
            val label = SimpleDateFormat("EEE", SR).format(parse.parse(d.getJSONArray("time").getString(i))!!)
            Day(label, d.getJSONArray("weather_code").getInt(i),
                d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt())
        }
        Wx(c.getDouble("temperature_2m").roundToInt(), c.getInt("weather_code"), c.getDouble("wind_speed_10m").roundToInt(), days)
    } catch (e: Exception) { null }
}

fun wxLabel(code: Int): Pair<String, String> = when (code) {
    0 -> "☀️" to "Vedro"
    1, 2 -> "🌤️" to "Delimično oblačno"
    3 -> "☁️" to "Oblačno"
    45, 48 -> "🌫️" to "Magla"
    in 51..67, in 80..82 -> "🌧️" to "Kiša"
    in 71..77, 85, 86 -> "❄️" to "Sneg"
    in 95..99 -> "⛈️" to "Oluja"
    else -> "🌡️" to "Vreme"
}

@Composable
fun T(s: String, size: Int, w: FontWeight = FontWeight.Normal, alpha: Float = 1f, color: Color = Color.White) =
    BasicText(s, style = TextStyle(color = color.copy(alpha = alpha), fontSize = size.sp, fontWeight = w, fontFamily = UiFont), maxLines = 1)

// Bloom wallpaper: three petal rings that rotate in opposite directions, breathe in and out, and
// drift gently. Draw phase only; one gradient brush per ring per frame to stay light on weak TVs.
@Composable
fun Modifier.bloom(): Modifier {
    val inf = rememberInfiniteTransition(label = "bloom")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(120000, easing = LinearEasing)), label = "spin")
    val breath by inf.animateFloat(.93f, 1.07f, infiniteRepeatable(tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val drift by inf.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(45000, easing = LinearEasing)), label = "drift")
    return drawBehind {
        val c = Offset(size.width * .66f + sin(drift) * size.width * .02f, size.height * .45f + cos(drift) * size.height * .03f)
        val r = size.height * .5f
        drawRect(Brush.radialGradient(listOf(Color(0x592563EB), Color.Transparent), c, size.width * .6f))
        fun ring(n: Int, angle: Float, k: Float, len: Float, wid: Float, from: Color, to: Color) {
            val b = Brush.linearGradient(listOf(from, to), Offset(c.x, c.y - len), c)
            for (i in 0 until n) rotate(angle + i * 360f / n, c) {
                scale(k, c) { drawOval(b, Offset(c.x - wid / 2, c.y - len), Size(wid, len)) }
            }
        }
        ring(8, spin, breath, r, r * .5f, Color(0xB360A5FA), Color(0x597C3AED))
        ring(8, 22.5f - spin * 2f, 2f - breath, r * .65f, r * .34f, Color(0xB3F472B6), Color(0x597C3AED))
        ring(12, spin * 3f, breath, r * .34f, r * .16f, Color(0x99FDE68A), Color(0x40F472B6))
        drawCircle(Color(0xCCC4B5FD), r * .08f * breath, c)
    }
}

// Ambience: slow drifting, twinkling light particles. Integer speeds keep the loop seamless.
@Composable
fun Modifier.ambience(): Modifier {
    val orbs = remember {
        val rnd = kotlin.random.Random(7)
        val cols = listOf(Color(0xFF93C5FD), Color(0xFFC4B5FD), Color(0xFFF9A8D4), Color(0xFFFDE68A))
        List(18) { Orb(rnd.nextFloat(), rnd.nextFloat(), 2f + rnd.nextFloat() * 5f, 1 + rnd.nextInt(3), rnd.nextFloat() * 6.2832f, cols[rnd.nextInt(cols.size)]) }
    }
    val t by rememberInfiniteTransition(label = "amb").animateFloat(0f, 1f, infiniteRepeatable(tween(90000, easing = LinearEasing)), label = "t")
    return drawBehind {
        orbs.forEach { o ->
            val y = ((o.y - t * o.speed) % 1f + 1f) % 1f
            val x = o.x + sin(t * 6.2832f * o.speed + o.ph) * .02f
            val tw = .5f + .5f * sin(t * 6.2832f * 6f * o.speed + o.ph)
            val c = Offset(x * size.width, y * size.height)
            drawCircle(o.hue.copy(alpha = .05f + .05f * tw), o.r.dp.toPx() * 3.2f, c)
            drawCircle(o.hue.copy(alpha = .25f + .35f * tw), o.r.dp.toPx(), c)
        }
    }
}

@Composable
fun Tile(label: String, onClick: () -> Unit, framed: Boolean = true, w: Dp = 132.dp, h: Dp = 112.dp, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.14f else 1f, spring(0.55f, 260f), label = "scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(250), label = "glow")
    Column(
        Modifier.width(w)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(w, h)
                .background(Color.White.copy(alpha = if (framed) .07f + .13f * glow else 0f), RoundedCornerShape(10.dp))
                .border(2.dp, Color.White.copy(alpha = .9f * glow), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { content() }
        Spacer(Modifier.height(10.dp))
        T(label, 16, alpha = .6f + .4f * glow)
    }
}

@Composable
fun WeatherCard(w: Wx) {
    val text = wxLabel(w.code).second
    Row(
        Modifier.glass(28.dp).padding(28.dp, 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WxIcon(w.code, 64.dp)
        Spacer(Modifier.width(20.dp))
        Column {
            T(PLACE, 16, alpha = .7f)
            T("${w.temp}°C", 40, FontWeight.Medium)
            T("$text · ${w.wind} km/h", 18, alpha = .7f)
        }
    }
}

@Composable
fun Modifier.glass(radius: Dp = 26.dp): Modifier = drawBehind {
    val rad = CornerRadius(radius.toPx())
    val diag = Offset(size.width, size.height)
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .05f)), Offset.Zero, diag), cornerRadius = rad)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent), endY = size.height * .5f), cornerRadius = rad)
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = .7f), Color.White.copy(alpha = .05f), Color.White.copy(alpha = .3f)), Offset.Zero, diag),
        cornerRadius = rad, style = Stroke(1.5f.dp.toPx())
    )
}

@Composable
fun Card(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glass(26.dp).padding(20.dp)) {
        T(title, 15, FontWeight.Medium, .6f)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun ForecastCard(days: List<Day>, modifier: Modifier) = Card(modifier, "Prognoza") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(d.label, 15, alpha = .7f)
                WxIcon(d.code, 36.dp)
                T("${d.hi}°/${d.lo}°", 15)
            }
        }
    }
}

@Composable
fun CalendarCard(now: Date, modifier: Modifier) =
    Card(modifier, SimpleDateFormat("LLLL", SR).format(now).replaceFirstChar { it.uppercase() }) {
        val start = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; time = now; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        val today = Calendar.getInstance().apply { time = now }.get(Calendar.DAY_OF_YEAR)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(7) { i ->
                val c = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
                val isToday = c.get(Calendar.DAY_OF_YEAR) == today
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(SimpleDateFormat("EEEEE", SR).format(c.time), 13, alpha = .6f)
                    Box(
                        Modifier.size(32.dp).background(if (isToday) Color.White else Color.Transparent, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) { T("${c.get(Calendar.DAY_OF_MONTH)}", 15, if (isToday) FontWeight.Medium else FontWeight.Normal, color = if (isToday) Color(0xFF05070D) else Color.White) }
                }
            }
        }
    }

@Composable
fun Bar(label: String, value: String, frac: Float) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(label, 15, alpha = .7f); T(value, 15) }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = .15f), RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).background(Color.White.copy(alpha = .85f), RoundedCornerShape(50)))
        }
    }
}

@Composable
fun DeviceCard(modifier: Modifier) = Card(modifier, "Ovaj TV") {
    val ctx = LocalContext.current
    val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
    val st = StatFs(Environment.getDataDirectory().path)
    val gb = 1024.0 * 1024 * 1024
    val sTotal = st.totalBytes / gb
    val sFree = st.availableBytes / gb
    val mTotal = mi.totalMem / gb
    val mFree = mi.availMem / gb
    Bar("Memorija", "%.1f GB slobodno".format(SR, sFree), (1 - sFree / sTotal).toFloat())
    Bar("RAM", "%.1f GB slobodno".format(SR, mFree), (1 - mFree / mTotal).toFloat())
}

// Drawn weather icons: sun, clouds, rain, snow, fog and storm.
@Composable
fun WxIcon(code: Int, dp: Dp) = Spacer(Modifier.size(dp).drawBehind {
    val s = size.minDimension
    fun sun(cx: Float, cy: Float, r: Float) {
        for (i in 0 until 8) rotate(i * 45f, Offset(cx, cy)) {
            drawLine(Color(0xFFFCD34D), Offset(cx, cy - r * 1.45f), Offset(cx, cy - r * 1.85f), r * .28f, StrokeCap.Round)
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3A0), Color(0xFFFBBF24)), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    fun cloud(x: Float, y: Float, w: Float) {
        val b = Brush.verticalGradient(listOf(Color.White, Color(0xFFC7D2FE)), y, y + w * .65f)
        drawRoundRect(b, Offset(x + .05f * w, y + .35f * w), Size(.9f * w, .3f * w), CornerRadius(.15f * w))
        drawCircle(b, .2f * w, Offset(x + .32f * w, y + .38f * w))
        drawCircle(b, .27f * w, Offset(x + .6f * w, y + .3f * w))
    }
    fun drops(col: Color) {
        for (i in 0..2) {
            val x = (.3f + i * .2f) * s
            drawLine(col, Offset(x, .66f * s), Offset(x - .05f * s, .84f * s), .07f * s, StrokeCap.Round)
        }
    }
    when (code) {
        0 -> sun(.5f * s, .5f * s, .24f * s)
        1, 2 -> { sun(.36f * s, .34f * s, .17f * s); cloud(.2f * s, .3f * s, .75f * s) }
        45, 48 -> {
            cloud(.12f * s, .06f * s, .76f * s)
            for (i in 0..2) drawLine(Color(0xAAFFFFFF), Offset((.2f + i * .05f) * s, (.68f + i * .1f) * s), Offset((.8f - i * .05f) * s, (.68f + i * .1f) * s), .06f * s, StrokeCap.Round)
        }
        in 51..67, in 80..82 -> { cloud(.12f * s, .06f * s, .76f * s); drops(Color(0xFF60A5FA)) }
        in 71..77, 85, 86 -> {
            cloud(.12f * s, .06f * s, .76f * s)
            for (i in 0..2) drawCircle(Color.White, .05f * s, Offset((.3f + i * .2f) * s, (.74f + (i % 2) * .08f) * s))
        }
        in 95..99 -> {
            cloud(.12f * s, .04f * s, .76f * s)
            val bolt = Path().apply {
                moveTo(.54f * s, .58f * s); lineTo(.38f * s, .8f * s); lineTo(.5f * s, .8f * s)
                lineTo(.44f * s, .98f * s); lineTo(.66f * s, .72f * s); lineTo(.54f * s, .72f * s); close()
            }
            drawPath(bolt, Color(0xFFFCD34D))
        }
        else -> cloud(.12f * s, .2f * s, .76f * s)
    }
})

fun DrawScope.petals(c: Offset, n: Int, angle: Float, k: Float, len: Float, wid: Float, from: Color, to: Color, a: Float) {
    val b = Brush.linearGradient(listOf(from.copy(alpha = from.alpha * a), to.copy(alpha = to.alpha * a)), Offset(c.x, c.y - len), c)
    for (i in 0 until n) rotate(angle + i * 360f / n, c) {
        scale(k, c) { drawOval(b, Offset(c.x - wid / 2, c.y - len), Size(wid, len)) }
    }
}

// Screensaver: the flower closes and blooms again and again, ring by ring.
@Composable
fun BloomSaver(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "saver")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "p")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(90000, easing = LinearEasing)), label = "spin")
    Box(modifier.background(Color(0xFF05070D)).ambience()) {
        Spacer(Modifier.fillMaxSize().drawBehind {
            val c = Offset(size.width / 2, size.height / 2)
            val r = minOf(size.width, size.height) * .46f
            fun bloomAt(lag: Float) = .5f - .5f * cos(6.2832f * (p - lag))
            val o1 = bloomAt(0f)
            val o2 = bloomAt(.1f)
            val o3 = bloomAt(.2f)
            drawCircle(Brush.radialGradient(listOf(Color(0x662563EB), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
            petals(c, 8, spin + 45f * o1, .12f + .88f * o1, r, r * .5f, Color(0xB360A5FA), Color(0x597C3AED), .25f + .75f * o1)
            petals(c, 8, 22.5f - spin * 2f - 40f * o2, .12f + .88f * o2, r * .65f, r * .34f, Color(0xB3F472B6), Color(0x597C3AED), .25f + .75f * o2)
            petals(c, 12, spin * 3f + 30f * o3, .12f + .88f * o3, r * .34f, r * .16f, Color(0x99FDE68A), Color(0x40F472B6), .25f + .75f * o3)
            drawCircle(Color(0xCCC4B5FD), r * .07f * (.6f + .4f * o3), c)
        })
    }
}

@Composable
fun Launcher() {
    LaunchedEffect(lastInput) { delay(IDLE_MS); saverOn = true }
    val sa by animateFloatAsState(if (saverOn) 1f else 0f, tween(if (saverOn) 2500 else 250), label = "saverFade")
    Box(Modifier.fillMaxSize()) {
        Home()
        if (sa > 0f) BloomSaver(Modifier.fillMaxSize().graphicsLayer { alpha = sa })
    }
}

@Composable
fun Home() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<App>()) }
    var wx by remember { mutableStateOf<Wx?>(null) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx.packageManager, ctx.packageName) } }
    LaunchedEffect(Unit) { while (true) { wx = fetchWx() ?: wx; delay(15 * 60_000L) } }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    BackHandler(true) { } // a launcher should never "back out"

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026)).bloom().ambience()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    T(SimpleDateFormat("HH:mm", SR).format(now), 96, FontWeight.Light)
                    T(SimpleDateFormat("EEEE, d. MMMM", SR).format(now).replaceFirstChar { it.uppercase() }, 24, alpha = .7f)
                }
                wx?.let { WeatherCard(it) }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ForecastCard(wx?.days.orEmpty(), Modifier.weight(1f))
                CalendarCard(now, Modifier.weight(1f))
                DeviceCard(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            T("Aplikacije", 20, alpha = .6f)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                items(apps, key = { it.pkg }) { app ->
                    val img = remember(app.pkg) { app.icon.asImageBitmap() }
                    Tile(app.label, { launch(ctx, app.pkg) }, framed = !app.isBanner, w = TILE_W, h = TILE_H) {
                        if (app.isBanner) Image(img, null, Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        else Image(img, null, Modifier.size(72.dp))
                    }
                }
                item {
                    Tile("Podešavanja", {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, w = TILE_W, h = TILE_H) { T("⚙️", 48) }
                }
            }
        }
    }
}
