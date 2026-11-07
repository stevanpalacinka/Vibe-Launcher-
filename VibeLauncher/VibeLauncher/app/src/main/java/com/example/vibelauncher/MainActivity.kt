package com.example.vibelauncher

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.ui.draw.clip
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

// Rounded UI font. The GitHub build downloads app/src/main/assets/fonts/rounded.ttf (Varela Round);
// if the file is missing the launcher falls back to the system font.
var UiFont: FontFamily = FontFamily.SansSerif

fun uiFont(ctx: Context): FontFamily = try {
    Typeface.createFromAsset(ctx.assets, "fonts/rounded.ttf")
    FontFamily(Font("fonts/rounded.ttf", ctx.assets))
} catch (e: Exception) { FontFamily.SansSerif }

data class App(val label: String, val pkg: String, val icon: Bitmap)
data class Day(val label: String, val code: Int, val hi: Int, val lo: Int)
data class Wx(val temp: Int, val code: Int, val wind: Int, val days: List<Day>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiFont = uiFont(this)
        setContent { Launcher() }
    }
}

fun loadApps(pm: PackageManager, self: String): List<App> {
    val out = LinkedHashMap<String, App>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
        for (ri in pm.queryIntentActivities(q, 0)) {
            val p = ri.activityInfo.packageName
            if (p == self || out.containsKey(p)) continue
            out[p] = App(ri.loadLabel(pm).toString(), p, ri.loadIcon(pm).toBitmap(192, 192))
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

@Composable
fun Tile(label: String, onClick: () -> Unit, framed: Boolean = true, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.14f else 1f, spring(0.55f, 260f), label = "scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(250), label = "glow")
    Column(
        Modifier.width(132.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(112.dp)
                .background(Color.White.copy(alpha = if (framed) .07f + .13f * glow else 0f), RoundedCornerShape(38.dp))
                .border(2.dp, Color.White.copy(alpha = .9f * glow), RoundedCornerShape(38.dp)),
            contentAlignment = Alignment.Center
        ) { content() }
        Spacer(Modifier.height(10.dp))
        T(label, 16, alpha = .6f + .4f * glow)
    }
}

@Composable
fun WeatherCard(w: Wx) {
    val (icon, text) = wxLabel(w.code)
    Row(
        Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(28.dp)).padding(28.dp, 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(icon, 56)
        Spacer(Modifier.width(20.dp))
        Column {
            T(PLACE, 16, alpha = .7f)
            T("${w.temp}°C", 40, FontWeight.Medium)
            T("$text · ${w.wind} km/h", 18, alpha = .7f)
        }
    }
}

@Composable
fun Card(modifier: Modifier, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp)).padding(20.dp)) {
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
                T(wxLabel(d.code).first, 28)
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

@Composable
fun Launcher() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<App>()) }
    var wx by remember { mutableStateOf<Wx?>(null) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx.packageManager, ctx.packageName) } }
    LaunchedEffect(Unit) { while (true) { wx = fetchWx() ?: wx; delay(15 * 60_000L) } }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    BackHandler(true) { } // a launcher should never "back out"

    Box(Modifier.fillMaxSize().background(Color(0xFF0B1026)).bloom()) {
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
                    Tile(app.label, { launch(ctx, app.pkg) }, framed = false) {
                        Image(img, null, Modifier.size(108.dp).clip(RoundedCornerShape(36.dp)))
                    }
                }
                item {
                    Tile("Podešavanja", {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { T("⚙️", 48) }
                }
            }
        }
    }
}
