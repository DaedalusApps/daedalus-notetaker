package com.daedalus.notes.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daedalus.notes.ai.*
import kotlinx.coroutines.launch

@Composable
fun ModelDownloadScreen(onReady: () -> Unit) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val model    = remember { selectedModel(context) }
    val downloader = remember { ModelDownloader(context) }
    val state    by downloader.state.collectAsState()

    LaunchedEffect(state) {
        if (state is DownloadState.Done) onReady()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val packageInfo = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
        }
        val versionName = packageInfo?.versionName ?: "Unknown"
        val versionCode = packageInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
        } ?: 0L

        Row(verticalAlignment = Alignment.Bottom) {
            Text("Daedalus Notes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v$versionName ($versionCode)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("AI Voice Recorder Companion", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))

        when (val s = state) {
            is DownloadState.Idle -> {
                Text("One-time setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Download ${model.displayName} (~${model.sizeBytes / 1_000_000_000}GB) to enable AI summarization. This is stored on your device and never sent to the cloud.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { scope.launch { downloader.download(model) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Download AI Model") }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onReady) { Text("Skip for now (AI disabled)") }
            }
            is DownloadState.Downloading -> {
                Text("Downloading ${model.displayName}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(progress = { s.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "${s.progressPct}%  ·  ${s.bytesDownloaded / 1_048_576} MB / ${s.totalBytes / 1_048_576} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Text("Keep the app open. This only happens once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadState.Done -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Model ready, loading…", style = MaterialTheme.typography.bodyMedium)
            }
            is DownloadState.Failed -> {
                Text("Download failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { scope.launch { downloader.download(model) } }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReady) { Text("Skip (AI disabled)") }
            }
        }
    }
}
