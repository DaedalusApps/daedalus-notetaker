package com.daedalus.notes.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daedalus.notes.ble.BleState
import com.daedalus.notes.ble.ConnectionState
import com.daedalus.notes.data.model.AudioUtils
import com.daedalus.notes.data.model.DateUtils
import com.daedalus.notes.data.model.Recording
import com.daedalus.notes.viewmodel.DeviceViewModel
import com.daedalus.notes.viewmodel.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: DeviceViewModel,
    recordingViewModel: RecordingViewModel,
    onNavigateToNote: (String) -> Unit,
    onNavigateToGlobalMindMap: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val bleState by viewModel.bleManager.bleState.collectAsState()
    val syncProgress by recordingViewModel.syncProgress.collectAsState()
    val recordings by recordingViewModel.filteredRecordings.collectAsState()
    val searchQuery by recordingViewModel.searchQuery.collectAsState()
    val libraryAnswer by recordingViewModel.libraryAnswer.collectAsState()
    val librarySources by recordingViewModel.librarySources.collectAsState()
    val isAsking by recordingViewModel.isAsking.collectAsState()
    val aiError by recordingViewModel.aiError.collectAsState()

    var selectedFilenames by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedFilenames.isNotEmpty()
    var showAskSheet by remember { mutableStateOf(false) }

    // Auto-scan when disconnected, refresh files when connected
    LaunchedEffect(bleState.connectionState) {
        when (bleState.connectionState) {
            ConnectionState.DISCONNECTED -> viewModel.scan()
            ConnectionState.CONNECTED -> viewModel.refreshFiles()
            else -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) Text("${selectedFilenames.size} selected")
                    else Text("Daedalus Notes")
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { selectedFilenames = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val isConnected = bleState.connectionState == ConnectionState.CONNECTED
                        IconButton(
                            onClick = {
                                recordingViewModel.deleteMultipleRecordings(
                                    selectedFilenames.toList(),
                                    viewModel.bleManager
                                )
                                selectedFilenames = emptySet()
                            },
                            enabled = isConnected
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = if (isConnected) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                            )
                        }
                    } else {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onNavigateToGlobalMindMap) {
                            Icon(Icons.Default.Hub, contentDescription = "Global Mind Map")
                        }
                        IconButton(onClick = { showAskSheet = true; recordingViewModel.clearAskAnswer() }) {
                            Icon(Icons.Default.QuestionAnswer, contentDescription = "Ask your notes")
                        }
                        IconButton(onClick = { recordingViewModel.syncAllBleFiles(viewModel.bleManager) }) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync via BLE")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            // Device status area
            DeviceStatusRow(
                bleState = bleState,
                onScan = { viewModel.scan() },
                onCancelScan = { viewModel.disconnect() }
            )

            // Sync progress
            if (syncProgress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = syncProgress!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { recordingViewModel.cancelSync() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel sync",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Search bar (hidden in selection mode)
            if (!isSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { recordingViewModel.setSearchQuery(it) },
                    placeholder = { Text("Search recordings…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { recordingViewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true
                )
            }

            // Recordings list
            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "No recordings found.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Connect the FW920 via BLE then tap the sync button.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = { recordingViewModel.syncAllBleFiles(viewModel.bleManager) }) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sync via BLE")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings, key = { it.filename }) { recording ->
                        val isSelected = selectedFilenames.contains(recording.filename)
                        HomeSwipeToDeleteCard(
                            recording = recording,
                            bleManager = viewModel.bleManager,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onPlay = {
                                if (isSelectionMode) {
                                    selectedFilenames = if (isSelected)
                                        selectedFilenames - recording.filename
                                    else
                                        selectedFilenames + recording.filename
                                } else {
                                    onNavigateToNote(recording.filename)
                                }
                            },
                            onLongClick = {
                                selectedFilenames = selectedFilenames + recording.filename
                            },
                            onDelete = {
                                recordingViewModel.deleteRecording(
                                    recording.filename,
                                    viewModel.bleManager
                                )
                            },
                            onEditSave = { title, summary ->
                                recordingViewModel.updateTitleAndSummary(
                                    recording.filename,
                                    title,
                                    summary
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAskSheet) {
        AskLibrarySheet(
            answer = libraryAnswer,
            sources = librarySources,
            isAsking = isAsking,
            error = if (!isAsking) aiError else null,
            onAsk = { q -> recordingViewModel.askLibraryQuestion(q) },
            onNavigateToNote = onNavigateToNote,
            onDismiss = { showAskSheet = false }
        )
    }
}

@Composable
private fun DeviceStatusRow(
    bleState: BleState,
    onScan: () -> Unit,
    onCancelScan: () -> Unit
) {
    when (bleState.connectionState) {
        ConnectionState.CONNECTED -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Connection indicator
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(Color(0xFF4CAF50), CircleShape)
                        )
                        Text(
                            "FW920",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Battery
                    if (bleState.batteryPct > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(
                                Icons.Default.BatteryFull,
                                contentDescription = "Battery",
                                modifier = Modifier.size(14.dp),
                                tint = when {
                                    bleState.batteryPct > 50 -> Color(0xFF4CAF50)
                                    bleState.batteryPct > 20 -> Color(0xFFFFC107)
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                            Text(
                                "${bleState.batteryPct}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Storage
                    if (bleState.storageFreeKb > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = "Storage",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatStorage(bleState.storageFreeKb, bleState.storageTotalKb),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Recording indicator
                    if (bleState.isRecording) {
                        RecordingDot()
                    }
                }
            }
        }

        ConnectionState.SCANNING, ConnectionState.CONNECTING -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = if (bleState.connectionState == ConnectionState.SCANNING)
                            "Scanning for FW920…" else "Connecting…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancelScan) { Text("Cancel") }
                }
            }
        }

        ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (bleState.connectionState == ConnectionState.ERROR && bleState.errorMessage.isNotBlank())
                            bleState.errorMessage else "FW920 not connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onScan) {
                        Text("Scan", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(alpha)
                .background(Color.Red, CircleShape)
        )
        Text(
            "REC",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.Red,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSwipeToDeleteCard(
    recording: Recording,
    bleManager: com.daedalus.notes.ble.BleManager,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onEditSave: (title: String, shortSummary: String) -> Unit
) {
    val bleState by bleManager.bleState.collectAsState()
    val isConnected = bleState.connectionState == ConnectionState.CONNECTED

    var showConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editTitle by remember(recording.filename) { mutableStateOf(recording.title) }
    var editShortSummary by remember(recording.filename) { mutableStateOf(recording.shortSummary) }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editShortSummary,
                        onValueChange = { editShortSummary = it },
                        label = { Text("One-line summary") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onEditSave(editTitle, editShortSummary)
                    showEditDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !isSelectionMode) {
                showConfirm = true
            }
            false
        }
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete recording?") },
            text = { Text("This will remove the recording from BOTH this app and the FW920 permanently. The device must be connected.") },
            confirmButton = {
                Button(
                    onClick = { showConfirm = false; onDelete() },
                    enabled = isConnected
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (!isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = if (isConnected) Color.Red else Color.Red.copy(alpha = 0.3f)
                    )
                }
            }
        }
    ) {
        HomeRecordingItem(
            recording = recording,
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            onPlay = onPlay,
            onLongClick = onLongClick,
            onEdit = { showEditDialog = true }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeRecordingItem(
    recording: Recording,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onPlay: () -> Unit,
    onLongClick: () -> Unit = {},
    onEdit: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                 else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onPlay() },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .semantics { contentDescription = "Select item" }
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                val displayTitle = recording.title.ifBlank {
                    DateUtils.parseDateFromFilename(recording.filename)
                }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (recording.title.isNotBlank()) {
                    Text(
                        text = DateUtils.parseDateFromFilename(recording.filename),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (recording.shortSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = recording.shortSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = AudioUtils.formatDuration(recording.durationMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "  •  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatFileSize(recording.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isSelectionMode) {
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit title and summary",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open note",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

private fun formatStorage(freeKb: Long, totalKb: Long): String {
    if (freeKb == 0L && totalKb == 0L) return "--"
    val freeMb = freeKb / 1024
    val totalMb = totalKb / 1024
    return if (totalMb >= 1024) {
        val freeGb = freeMb / 1024f
        val totalGb = totalMb / 1024f
        "${"%.1f".format(freeGb)}/${"%.1f".format(totalGb)}GB"
    } else {
        "${freeMb}MB free"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024 -> "${bytes / 1_024} KB"
        else -> "$bytes B"
    }
}
