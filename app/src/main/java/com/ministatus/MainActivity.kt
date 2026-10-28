@file:OptIn(UnstableApi::class)

package com.ministatus

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B6B50))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by rememberSaveable { mutableStateOf<Uri?>(null) }
    var durMs by rememberSaveable { mutableStateOf(0L) }
    var seg by rememberSaveable { mutableStateOf(30) }
    var parts by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf("") }

    val pickVideo = rememberLauncherForActivityResult(PickVisualMedia()) { u ->
        if (u != null) { video = u; durMs = duration(ctx, u); parts = emptyList() }
    }
    val pickPhotos = rememberLauncherForActivityResult(PickMultipleVisualMedia(30)) { list ->
        if (list.isNotEmpty()) {
            // Salin apa adanya (tanpa kompresi) lalu kirim ke WhatsApp.
            val dir = File(ctx.cacheDir, "out").apply { mkdirs() }
            val out = list.mapIndexed { i, u ->
                val f = File(dir, "foto_${i + 1}.jpg")
                ctx.contentResolver.openInputStream(u)?.use { it.copyTo(f.outputStream()) }
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            }
            share(ctx, out, "image/*")
        }
    }

    val n = if (durMs <= 0) 0 else maxOf(1, ceil((durMs - 1000).toDouble() / (seg * 1000)).toInt())

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MiniStatus", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickVideo.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) }) { Text("Pilih video") }
            OutlinedButton(onClick = { pickPhotos.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }) { Text("Pilih foto") }
        }

        if (video != null) {
            Text("Durasi ${fmt(durMs)}, jadi $n bagian")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Per bagian")
                listOf(15, 30, 60).forEach { s ->
                    FilterChip(selected = seg == s, onClick = { seg = s; parts = emptyList() }, label = { Text("$s dtk") })
                }
            }
            Button(
                enabled = busy.isEmpty() && n > 0,
                onClick = {
                    scope.launch {
                        val dir = File(ctx.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
                        val list = mutableListOf<Uri>()
                        try {
                            for (i in 0 until n) {
                                busy = "Memproses ${i + 1} dari $n"
                                val f = File(dir, "status_${i + 1}.mp4")
                                exportClip(ctx, video!!, i * seg * 1000L, minOf((i + 1) * seg * 1000L, durMs), f)
                                list += FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            }
                            parts = list
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Gagal memotong: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        busy = ""
                    }
                }
            ) { Text(busy.ifEmpty { "Potong video" }) }
        }

        if (parts.isNotEmpty()) {
            Button(onClick = { share(ctx, parts, "video/*") }) { Text("Kirim semua ke WhatsApp") }
            LazyColumn {
                itemsIndexed(parts) { i, u ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Bagian ${i + 1}")
                        TextButton(onClick = { share(ctx, listOf(u), "video/*") }) { Text("Kirim") }
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

private fun duration(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } catch (e: Exception) { 0L } finally { r.release() }
}

private suspend fun exportClip(ctx: Context, src: Uri, startMs: Long, endMs: Long, out: File) =
    suspendCancellableCoroutine<Unit> { c ->
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        val item = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
        val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) = c.resume(Unit)
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                c.resumeWithException(exportException)
        }).build()
        t.start(EditedMediaItem.Builder(item).build(), out.absolutePath)
        c.invokeOnCancellation { t.cancel() }
    }

private fun share(ctx: Context, uris: List<Uri>, mime: String) {
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false }
    }
    if (pkg == null) {
        Toast.makeText(ctx, "WhatsApp belum terpasang", Toast.LENGTH_LONG).show()
        return
    }
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    i.setType(mime).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(i)
}
