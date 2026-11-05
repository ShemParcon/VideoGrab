package com.shem.videograb

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { YoutubeDL.getInstance().init(this); FFmpeg.getInstance().init(this) } catch (_: Exception) {}
    }
}

class Dl(val id: String, val url: String, val title: String, val fmt: String, val audio: Boolean) {
    var status by mutableStateOf("Starting")
    var pct by mutableFloatStateOf(0f)
    var info by mutableStateOf("")
    var job: Job? = null
    fun dir(ctx: Context) = File(ctx.cacheDir, "dl/$id")
}
class Item(val name: String, val uri: String)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloads = mutableStateListOf<Dl>()
    private val history = mutableStateListOf<Item>()
    private var url by mutableStateOf("")
    private var title by mutableStateOf<String?>(null)
    private var heights by mutableStateOf(listOf<Int>())
    private var sel by mutableIntStateOf(0) // 0 = best, -1 = MP3, else max height
    private var msg by mutableStateOf("")
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("h", 0).getString("items", "")!!.lines().filter { it.contains("\t") }
            .forEach { val p = it.split("\t"); history.add(Item(p[0], p[1])) }
        scope.launch { try { YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE) } catch (_: Exception) {} }
        handle(intent)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val u = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value ?: return
        url = u; analyze()
    }

    private fun analyze() {
        if (url.isBlank() || busy) return
        busy = true; msg = ""; title = null
        scope.launch {
            try {
                val info = YoutubeDL.getInstance().getInfo(url.trim())
                heights = info.formats?.map { it.height }?.filter { it >= 144 }?.distinct()?.sortedDescending() ?: listOf()
                sel = 0; title = info.title ?: "Video"
            } catch (e: Exception) {
                msg = "Can't download this link: " + (e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "unsupported or protected")
            }
            busy = false
        }
    }

    private fun start() {
        val fmt = when { sel < 0 -> "ba/b"; sel == 0 -> "bv*+ba/b"; else -> "bv*[height<=$sel]+ba/b[height<=$sel]/b" }
        val d = Dl(UUID.randomUUID().toString(), url.trim(), title ?: url, fmt, sel < 0)
        downloads.add(0, d); title = null; url = ""; run(d)
    }

    private fun run(d: Dl) {
        d.status = "Downloading"
        d.job = scope.launch {
            try {
                val r = YoutubeDLRequest(d.url)
                r.addOption("-o", d.dir(this@MainActivity).absolutePath + "/%(title).80s.%(ext)s")
                r.addOption("-f", d.fmt); r.addOption("-c"); r.addOption("--no-mtime"); r.addOption("--no-playlist")
                if (d.audio) { r.addOption("-x"); r.addOption("--audio-format", "mp3") } else r.addOption("--merge-output-format", "mp4")
                YoutubeDL.getInstance().execute(r, d.id) { p, eta, line ->
                    if (p >= 0) d.pct = p / 100f
                    val m = Regex("of\\s+~?\\s*([\\d.]+\\S+)\\s+at\\s+([\\d.]+\\S+/s)").find(line)
                    if (m != null) d.info = "${m.groupValues[1]} • ${m.groupValues[2]} • ETA ${eta}s"
                }
                d.status = "Saving"
                val f = d.dir(this@MainActivity).listFiles()?.filter { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() } ?: error("No file produced")
                val uri = save(f); f.delete()
                history.add(0, Item(f.name, uri)); persist(); d.pct = 1f; d.status = "Done"
            } catch (e: Exception) {
                if (d.status == "Downloading" || d.status == "Saving")
                    d.status = "Error: " + (e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(140) ?: "failed")
            }
        }
    }

    private fun pause(d: Dl) { d.status = "Paused"; YoutubeDL.getInstance().destroyProcessById(d.id) }
    private fun cancel(d: Dl) {
        d.status = "Canceled"; YoutubeDL.getInstance().destroyProcessById(d.id)
        d.dir(this).deleteRecursively(); downloads.remove(d)
    }

    private fun save(f: File): String {
        val mp3 = f.extension == "mp3"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (mp3) "audio/mpeg" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (mp3) "Music" else "Movies") + "/VideoGrab")
        }
        val col = if (mp3) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(col, v)!!
        contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        return uri.toString()
    }

    private fun persist() = getSharedPreferences("h", 0).edit()
        .putString("items", history.joinToString("\n") { it.name + "\t" + it.uri }).apply()

    private fun open(i: Item) = try {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(i.uri), if (i.name.endsWith("mp3")) "audio/*" else "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {}

    private fun delete(i: Item) { try { contentResolver.delete(Uri.parse(i.uri), null, null) } catch (_: Exception) {}; history.remove(i); persist() }

    @Composable
    fun Screen() {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("VideoGrab", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Paste video link") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button({ analyze() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Analyzing…" else "Analyze link") }
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
                title?.let { t ->
                    Spacer(Modifier.height(8.dp))
                    Text(t, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(sel == 0, { sel = 0 }, { Text("Best MP4") })
                        heights.forEach { h -> FilterChip(sel == h, { sel = h }, { Text("${h}p MP4") }) }
                        FilterChip(sel == -1, { sel = -1 }, { Text("MP3 audio") })
                    }
                    Button({ start() }, Modifier.fillMaxWidth()) { Text("Download") }
                }
            }
            if (downloads.isNotEmpty()) item { Text("Downloads", style = MaterialTheme.typography.titleLarge) }
            items(downloads.toList(), key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.title, maxLines = 1)
                        LinearProgressIndicator(progress = { d.pct }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text("${d.status} ${(d.pct * 100).toInt()}%  ${d.info}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (d.status == "Downloading") TextButton({ pause(d) }) { Text("Pause") }
                            if (d.status == "Paused" || d.status.startsWith("Error")) TextButton({ run(d) }) { Text(if (d.status == "Paused") "Resume" else "Retry") }
                            if (d.status == "Done") TextButton({ downloads.remove(d) }) { Text("Clear") } else TextButton({ cancel(d) }) { Text("Cancel") }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) item { Text("History / Files", style = MaterialTheme.typography.titleLarge) }
            items(history.toList()) { i ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(i.name, Modifier.weight(1f), maxLines = 2)
                        TextButton({ open(i) }) { Text("Open") }
                        TextButton({ delete(i) }) { Text("Delete") }
                    }
                }
            }
            item { Text("Saved to Movies/VideoGrab (MP3 → Music/VideoGrab)", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
