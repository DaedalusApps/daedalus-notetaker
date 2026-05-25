package com.daedalus.notes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.io.File
import com.daedalus.notes.ai.CATEGORIES
import com.daedalus.notes.viewmodel.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    filename: String,
    recordingViewModel: RecordingViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val note by recordingViewModel.currentNote.collectAsState()
    val isProcessing by recordingViewModel.isProcessing.collectAsState()
    val aiError by recordingViewModel.aiError.collectAsState()

    // Player setup
    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    val transcript = note?.transcript.orEmpty()
    val summary = note?.summary.orEmpty()
    val mindMap = note?.mindMap.orEmpty()

    // Load note on first composition
    LaunchedEffect(filename) {
        recordingViewModel.loadNote(filename)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var selectedCategoryId by remember { mutableStateOf(CATEGORIES.lastOrNull()?.id ?: 15) }

    if (showCategoryDialog) {
        CategoryPickerDialog(
            selectedCategoryId = selectedCategoryId,
            onConfirm = { categoryId ->
                selectedCategoryId = categoryId
                showCategoryDialog = false
                recordingViewModel.analyze(filename, categoryId)
            },
            onDismiss = { showCategoryDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = formatFilename(filename),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (aiError != null) {
                    Text(
                        text = "AI Error: $aiError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Play Button
                    Button(
                        onClick = {
                            if (isPlaying) {
                                player.stop()
                                isPlaying = false
                            } else {
                                val file = note?.localPath?.let { File(it) } ?: File(context.getExternalFilesDir(null), "Recordings/$filename")
                                if (file.exists()) {
                                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                    player.prepare()
                                    player.play()
                                    isPlaying = true
                                } else {
                                    Log.e("Playback", "File not found: ${file.absolutePath}")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "Stop" else "Play", maxLines = 1)
                    }

                    Button(
                        onClick = { showCategoryDialog = true },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Analyze")
                    }
                    OutlinedButton(
                        onClick = { recordingViewModel.exportMarkdown(filename) },
                        enabled = transcript.isNotEmpty() && !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export MD")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("Transcript", "Summary", "Mind Map").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> TranscriptTab(transcript)
                    1 -> SummaryTab(summary)
                    2 -> MindMapTab(mindMap)
                }
            }
        }
    }
}

@Composable
private fun TranscriptTab(transcript: String) {
    if (transcript.isEmpty()) {
        PlaceholderText("Tap 'Analyze' to transcribe this recording.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SummaryTab(summary: String) {
    if (summary.isEmpty()) {
        PlaceholderText("No summary yet. Tap 'Analyze' to generate one.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = prettyPrintJson(summary),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
private fun MindMapTab(mindMap: String) {
    if (mindMap.isEmpty()) {
        PlaceholderText("No mind map yet. Tap 'Analyze' to generate one.")
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = mindMap,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PlaceholderText(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CategoryPickerDialog(
    selectedCategoryId: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var currentId by remember { mutableStateOf(selectedCategoryId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Category",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState())
            ) {
                CATEGORIES.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = currentId == category.id,
                                onClick = { currentId = category.id },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentId == category.id,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(currentId) }) {
                Text("Analyze")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatFilename(filename: String): String {
    val base = filename.substringBeforeLast(".")
    val match = Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})""").find(base) ?: return base
    val (year, month, day, hour, min, sec) = match.destructured
    return "$year-$month-$day $hour:$min:$sec"
}

/** Naive JSON pretty-printer without pulling in an extra library. */
private fun prettyPrintJson(raw: String): String {
    return try {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false
        for (ch in raw.trim()) {
            when {
                escape -> {
                    sb.append(ch)
                    escape = false
                }
                ch == '\\' && inString -> {
                    sb.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    inString = !inString
                    sb.append(ch)
                }
                inString -> sb.append(ch)
                ch == '{' || ch == '[' -> {
                    indent++
                    sb.append(ch).append('\n').append("  ".repeat(indent))
                }
                ch == '}' || ch == ']' -> {
                    indent--
                    sb.append('\n').append("  ".repeat(indent)).append(ch)
                }
                ch == ',' -> sb.append(ch).append('\n').append("  ".repeat(indent))
                ch == ':' -> sb.append(": ")
                ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' -> { /* skip raw whitespace */ }
                else -> sb.append(ch)
            }
        }
        sb.toString()
    } catch (_: Exception) {
        raw
    }
}
