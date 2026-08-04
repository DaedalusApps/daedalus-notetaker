package com.daedalus.notes.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daedalus.notes.ai.Role
import com.daedalus.notes.ai.VoiceInfo
import com.daedalus.notes.viewmodel.ChatMessage
import com.daedalus.notes.viewmodel.ConversationViewModel
import com.daedalus.notes.viewmodel.canStartNewSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationViewModel: ConversationViewModel,
    onBack: () -> Unit
) {
    val messages by conversationViewModel.messages.collectAsState()
    val isGenerating by conversationViewModel.isGenerating.collectAsState()
    val error by conversationViewModel.error.collectAsState()
    val isRecordingVoice by conversationViewModel.isRecordingVoice.collectAsState()
    val isTranscribing by conversationViewModel.isTranscribing.collectAsState()
    val voiceTranscript by conversationViewModel.voiceTranscript.collectAsState()
    val ttsEnabled by conversationViewModel.ttsEnabled.collectAsState()
    val instantSend by conversationViewModel.instantSend.collectAsState()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) conversationViewModel.startVoiceInput() }

    val startVoiceInput = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) conversationViewModel.startVoiceInput()
        else recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            conversationViewModel.clearError()
        }
    }

    // Appended rather than assigned: the field stays editable while transcription runs, and
    // overwriting would silently drop whatever the user typed in the meantime.
    LaunchedEffect(voiceTranscript) {
        voiceTranscript?.let {
            input = if (input.isBlank()) it else "${input.trimEnd()} $it"
            conversationViewModel.clearVoiceTranscript()
        }
    }

    // Leaving the screen abandons an in-progress recording and stops any in-progress speech; the
    // ViewModel outlives this composable, so leaving either running would otherwise hold the mic
    // or keep talking after the user navigated away.
    DisposableEffect(Unit) {
        onDispose {
            conversationViewModel.cancelVoiceInput()
            conversationViewModel.stopSpeaking()
        }
    }

    if (showSpeedDialog) {
        SpeedDialog(
            conversationViewModel = conversationViewModel,
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showVoiceDialog) {
        VoiceDialog(
            conversationViewModel = conversationViewModel,
            onDismiss = { showVoiceDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.navigationBarsPadding().imePadding(),
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text("Converse") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { conversationViewModel.setTtsEnabled(!ttsEnabled) }
                    ) {
                        if (ttsEnabled) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Spoken replies on")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = "Spoken replies off")
                        }
                    }
                    IconButton(
                        onClick = { conversationViewModel.endSession() },
                        enabled = messages.isNotEmpty() && !isGenerating
                    ) {
                        Icon(Icons.Default.Done, contentDescription = "End session")
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("New conversation") },
                            leadingIcon = { Icon(Icons.Default.AddComment, contentDescription = null) },
                            enabled = canStartNewSession(messages, isGenerating),
                            onClick = {
                                menuExpanded = false
                                conversationViewModel.startNewSession()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Speed…") },
                            onClick = {
                                menuExpanded = false
                                showSpeedDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Voice…") },
                            onClick = {
                                menuExpanded = false
                                showVoiceDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Instant send") },
                            trailingIcon = {
                                // Null so the whole row is the single tap target: tapping the
                                // switch itself otherwise toggles without closing the menu.
                                Switch(checked = instantSend, onCheckedChange = null)
                            },
                            onClick = {
                                menuExpanded = false
                                conversationViewModel.setInstantSend(!instantSend)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Start a conversation — think out loud with your local agent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(message)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Say something…") },
                    enabled = !isGenerating,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4
                )
                Box {
                    IconButton(
                        onClick = {
                            if (isRecordingVoice) conversationViewModel.stopVoiceInput() else startVoiceInput()
                        },
                        // Stays tappable while recording so the user can always stop — otherwise a
                        // send/end started mid-recording would lock the mic until it finishes.
                        enabled = isRecordingVoice || (!isGenerating && !isTranscribing)
                    ) {
                        when {
                            isTranscribing -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            isRecordingVoice -> Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop recording",
                                tint = MaterialTheme.colorScheme.error
                            )
                            else -> Icon(Icons.Default.Mic, contentDescription = "Voice input")
                        }
                    }
                    if (isRecordingVoice) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        conversationViewModel.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !isGenerating
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

private val SPEED_OPTIONS = listOf(0.75f to "0.75×", 1.0f to "1×", 1.25f to "1.25×", 1.5f to "1.5×", 2.0f to "2×")

/** Radio list of speed presets; each selection applies (and previews) immediately. Stays open
 *  until dismissed so the user can compare presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedDialog(
    conversationViewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val ttsRate by conversationViewModel.ttsRate.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech speed") },
        text = {
            Column(Modifier.selectableGroup()) {
                SPEED_OPTIONS.forEach { (rate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = ttsRate == rate,
                                onClick = { conversationViewModel.setTtsRate(rate) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ttsRate == rate, onClick = { conversationViewModel.setTtsRate(rate) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Radio list of "System default" + the engine's available voices; each selection applies (and
 *  previews) immediately. Stays open until dismissed so the user can compare voices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDialog(
    conversationViewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val ttsVoiceId by conversationViewModel.ttsVoiceId.collectAsState()
    val voices = remember { conversationViewModel.availableVoices() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(Modifier.selectableGroup()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = ttsVoiceId.isEmpty(),
                            onClick = { conversationViewModel.setTtsVoice("") }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = ttsVoiceId.isEmpty(), onClick = { conversationViewModel.setTtsVoice("") })
                    Spacer(Modifier.width(8.dp))
                    Text("System default")
                }
                if (voices.isEmpty()) {
                    Text(
                        "No other voices available. Turn spoken replies on to see the voices your " +
                            "device's speech engine offers.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                voices.forEach { voice: VoiceInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = ttsVoiceId == voice.id,
                                onClick = { conversationViewModel.setTtsVoice(voice.id) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ttsVoiceId == voice.id, onClick = { conversationViewModel.setTtsVoice(voice.id) })
                        Spacer(Modifier.width(8.dp))
                        Text(voice.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
