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
const val LAT = 44.8
const val LON = 20.46

// One UI-style font. SamsungOne is proprietary, so drop Figtree .ttf files into app/src/main/res/font/
// and swap this for: FontFamily(Font(R.font.figtree_regular), Font(R.font.figtree_medium, FontWeight.Medium))
val UiFont: FontFamily = FontFamily.SansSerif

data class App(val label: String, val pkg: String, val icon: Bitmap)
data class Day(val label: String, val code: Int, val hi: Int, val lo: Int)
data class Wx(val temp: Int, val code: Int, val wind: Int, val days: List<Day>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val label = SimpleDateFormat("EEE", Locale.getDefault()).format(parse.parse(d.getJSONArray("time").getString(i))!!)
            Day(label, d.getJSONArray("weather_code").getInt(i),
                d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt())
        }
        Wx(c.getDouble("temperature_2m").roundToInt(), c.getInt("weather_code"), c.getDouble("wind_speed_10m").roundToInt(), days)
    } catch (e: Exception) { null }
}

fun wxLabel(code: Int): Pair<String, String> = when (code) {
    0 -> "☀️" to "Clear"
    1, 2 -> "🌤️" to "Partly cloudy"
    3 -> "☁️" to "Cloudy"
    45, 48 -> "🌫️" to "Fog"
    in 51..67, in 80..82 -> "🌧️" to "Rain"
    in 71..77, 85, 86 -> "❄️" to "Snow"
    in 95..99 -> "⛈️" to "Storm"
    else -> "🌡️" to "Weather"
}

@Composable
fun T(s: String, size: Int, w: FontWeight = FontWeight.Normal, alpha: Float = 1f, color: Color = Color.White) =
    BasicText(s, style = TextStyle(color = color.copy(alpha = alpha), fontSize = size.sp, fontWeight = w, fontFamily = UiFont), maxLines = 1)

// Bloom-style wallpaper: two rings of translucent petals that slowly rotate. Runs in the draw phase only.
@Composable
fun Modifier.bloom(): Modifier {
    val t by rememberInfiniteTransition(label = "bloom")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(90000, easing = LinearEasing)), label = "t")
    return drawBehind {
        val c = Offset(size.width * .66f, size.height * .45f)
        val r = size.height * .5f
        drawRect(Brush.radialGradient(listOf(Color(0x592563EB), Color.Transparent), c, size.width * .6f))
        fun ring(n: Int, offset: Float, len: Float, wid: Float, from: Color, to: Color) {
            for (i in 0 until n) rotate(t + offset + i * 360f / n, c) {
                drawOval(
                    Brush.linearGradient(listOf(from, to), Offset(c.x, c.y - len), c),
                    Offset(c.x - wid / 2, c.y - len),
                    Size(wid, len)
                )
            }
        }
        ring(8, 0f, r, r * .5f, Color(0xB360A5FA), Color(0x597C3AED))
        ring(8, 22.5f, r * .65f, r * .34f, Color(0xB3F472B6), Color(0x597C3AED))
        drawCircle(Color(0xCCC4B5FD), r * .08f, c)
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
                .background(Color.White.copy(alpha = if (framed) .07f + .13f * glow else 0f), RoundedCornerShape(28.dp))
                .border(2.dp, Color.White.copy(alpha = .9f * glow), RoundedCornerShape(28.dp)),
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
fun ForecastCard(days: List<Day>, modifier: Modifier) = Card(modifier, "Forecast") {
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
    Card(modifier, SimpleDateFormat("LLLL", Locale.getDefault()).format(now)) {
        val start = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; time = now; set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        val today = Calendar.getInstance().apply { time = now }.get(Calendar.DAY_OF_YEAR)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(7) { i ->
                val c = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
                val isToday = c.get(Calendar.DAY_OF_YEAR) == today
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(SimpleDateFormat("EEEEE", Locale.getDefault()).format(c.time), 13, alpha = .6f)
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
fun DeviceCard(modifier: Modifier) = Card(modifier, "This TV") {
    val ctx = LocalContext.current
    val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
    val st = StatFs(Environment.getDataDirectory().path)
    val gb = 1024.0 * 1024 * 1024
    val sTotal = st.totalBytes / gb
    val sFree = st.availableBytes / gb
    val mTotal = mi.totalMem / gb
    val mFree = mi.availMem / gb
    Bar("Storage", "%.1f GB free".format(sFree), (1 - sFree / sTotal).toFloat())
    Bar("Memory", "%.1f GB free".format(mFree), (1 - mFree / mTotal).toFloat())
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
                    T(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 96, FontWeight.Light)
                    T(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now), 24, alpha = .7f)
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
            T("Apps", 20, alpha = .6f)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                items(apps, key = { it.pkg }) { app ->
                    val img = remember(app.pkg) { app.icon.asImageBitmap() }
                    Tile(app.label, { launch(ctx, app.pkg) }, framed = false) {
                        Image(img, null, Modifier.size(108.dp).clip(RoundedCornerShape(26.dp)))
                    }
                }
                item {
                    Tile("Settings", {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { T("⚙️", 48) }
                }
            }
        }
    }
}
