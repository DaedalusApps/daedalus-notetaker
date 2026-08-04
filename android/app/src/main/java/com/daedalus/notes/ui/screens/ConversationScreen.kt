package com.daedalus.notes.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.daedalus.notes.ai.Role
import com.daedalus.notes.ai.VoiceInfo
import com.daedalus.notes.viewmodel.ChatMessage
import com.daedalus.notes.viewmodel.ConversationViewModel
import com.daedalus.notes.viewmodel.VoiceButtonState
import com.daedalus.notes.viewmodel.canStartNewSession
import com.daedalus.notes.viewmodel.voiceButtonState

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
    val isSpeaking by conversationViewModel.isSpeaking.collectAsState()
    val speakingMessageId by conversationViewModel.speakingMessageId.collectAsState()
    val instantSend by conversationViewModel.instantSend.collectAsState()
    val autoListen by conversationViewModel.autoListen.collectAsState()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    // Instant send ON routes through startVoiceInputInterruptingSpeech() instead of plain
    // startVoiceInput() (P9.3): the voice-only surface's big mic button must stop any playing
    // reply before recording — see that function's KDoc. Both permission entry points (already-
    // granted and just-granted-via-launcher) go through this one lambda so the behavior is
    // identical regardless of which path the OS takes.
    val beginVoiceInput = {
        if (instantSend) conversationViewModel.startVoiceInputInterruptingSpeech()
        else conversationViewModel.startVoiceInput()
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) beginVoiceInput() }

    val startVoiceInput = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) beginVoiceInput()
        else recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A pending transcription (P9.3), shown as a synthetic bubble at the list's end while Whisper
    // runs, keeps the list non-empty even with zero real messages yet — and is an extra item past
    // the last real message, so the auto-scroller must count it too or it lands below the fold.
    val showPendingBubble = instantSend && isTranscribing
    val listItemCount = messages.size + if (showPendingBubble) 1 else 0

    LaunchedEffect(listItemCount) {
        if (listItemCount > 0) listState.animateScrollToItem(listItemCount - 1)
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

    // Keep the screen awake while a voice session is active (P9.5), so recording/transcribing/
    // speaking/generating doesn't get cut short by the screen locking. Released on idle and on
    // leaving the screen.
    val voiceActive = isRecordingVoice || isTranscribing || isSpeaking || isGenerating
    val view = LocalView.current
    DisposableEffect(voiceActive) {
        view.keepScreenOn = voiceActive
        onDispose { view.keepScreenOn = false }
    }

    // Auto-listen (P9.4) only fires while this screen is actually on-screen: ON_RESUME/ON_PAUSE
    // track that, and disposal (e.g. process death) leaves it not-visible rather than stuck true.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> conversationViewModel.setConversationVisible(true)
                Lifecycle.Event.ON_PAUSE -> conversationViewModel.setConversationVisible(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            conversationViewModel.setConversationVisible(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showSpeedDialog) {
        SpeedDialog(
            conversationViewModel = conversationViewModel,
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showVoiceDialog) {
        VoiceSheet(
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
                        // Tapping while speaking cancels the reply's speech (Cancel reply, P8.4)
                        // without flipping the enabled toggle; otherwise it behaves as today.
                        onClick = {
                            if (isSpeaking) conversationViewModel.stopSpeaking()
                            else conversationViewModel.setTtsEnabled(!ttsEnabled)
                        }
                    ) {
                        if (ttsEnabled) {
                            Icon(
                                Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isSpeaking) "Speaking — tap to stop" else "Spoken replies on",
                                tint = if (isSpeaking) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
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
                        DropdownMenuItem(
                            text = { Text("Auto-listen") },
                            trailingIcon = {
                                Switch(checked = autoListen, onCheckedChange = null)
                            },
                            onClick = {
                                menuExpanded = false
                                conversationViewModel.setAutoListen(!autoListen)
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
            if (messages.isEmpty() && !showPendingBubble) {
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
                    itemsIndexed(messages) { index, message ->
                        val id = index.toString()
                        ChatBubble(
                            message = message,
                            isReplaying = speakingMessageId == id,
                            onReplayClick = { conversationViewModel.replayMessage(id) },
                            onStopReplayClick = { conversationViewModel.stopSpeaking() }
                        )
                    }
                    if (showPendingBubble) {
                        item { PendingTranscriptionBubble() }
                    }
                }
            }

            if (instantSend) {
                // Voice-only instant-send surface (P9.3): the text field and send button are
                // hidden entirely in favor of one big mic/stop button, since every transcription
                // sends itself the moment it's ready.
                VoiceOnlyInputRow(
                    isRecordingVoice = isRecordingVoice,
                    isTranscribing = isTranscribing,
                    isGenerating = isGenerating,
                    onStartVoiceInput = startVoiceInput,
                    onStopRecording = { conversationViewModel.stopVoiceInput() },
                    onStopGenerating = { conversationViewModel.stopGenerating() }
                )
            } else {
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
                        // While generating, the send button becomes an inline Stop button (P8.4):
                        // tapping it aborts the in-flight generation instead of sending.
                        onClick = {
                            if (isGenerating) {
                                conversationViewModel.stopGenerating()
                            } else {
                                conversationViewModel.send(input)
                                input = ""
                            }
                        },
                        enabled = isGenerating || input.isNotBlank()
                    ) {
                        if (isGenerating) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

/** Diameter of the big mic/stop button on the voice-only instant-send surface (P9.3). */
private val VOICE_ONLY_MIC_SIZE = 72.dp

/**
 * The voice-only instant-send input surface (P9.3, morphed into a single center button by
 * P10.1): one button that morphs by [VoiceButtonState] rather than a big mic plus a separate Stop
 * pill — the owner rule is that a stop and a mic are never visible at once.
 *
 * IDLE shows Mic and starts recording via [onStartVoiceInput] (which — per
 * [ConversationViewModel.startVoiceInputInterruptingSpeech] — stops any playing reply first).
 * RECORDING shows Stop with a pulsing ring and calls [onStopRecording]; it stays tappable even if
 * generation starts mid-recording (e.g. via endSession), so the user can never be locked out of
 * stopping their own recording (P9.3 mic-hostage lesson). TRANSCRIBING disables the button and
 * shows a small spinner in its place. GENERATING shows Stop (no pulse) and calls
 * [onStopGenerating]; recording cannot be started from this state — auto-listen handles
 * hands-free continuation once generation ends.
 */
@Composable
private fun VoiceOnlyInputRow(
    isRecordingVoice: Boolean,
    isTranscribing: Boolean,
    isGenerating: Boolean,
    onStartVoiceInput: () -> Unit,
    onStopRecording: () -> Unit,
    onStopGenerating: () -> Unit
) {
    val state = voiceButtonState(isRecordingVoice, isTranscribing, isGenerating)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state == VoiceButtonState.RECORDING) {
            val transition = rememberInfiniteTransition(label = "mic-pulse")
            val ringScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.6f,
                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
                label = "mic-pulse-scale"
            )
            val ringAlpha by transition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
                label = "mic-pulse-alpha"
            )
            Box(
                modifier = Modifier
                    .size(VOICE_ONLY_MIC_SIZE)
                    .scale(ringScale)
                    .alpha(ringAlpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
        FilledIconButton(
            onClick = when (state) {
                VoiceButtonState.IDLE -> onStartVoiceInput
                VoiceButtonState.RECORDING -> onStopRecording
                VoiceButtonState.GENERATING -> onStopGenerating
                VoiceButtonState.TRANSCRIBING -> ({})
            },
            enabled = state != VoiceButtonState.TRANSCRIBING,
            modifier = Modifier.size(VOICE_ONLY_MIC_SIZE)
        ) {
            when (state) {
                VoiceButtonState.TRANSCRIBING -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Transcribing" },
                    strokeWidth = 2.dp
                )
                VoiceButtonState.RECORDING -> Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop recording",
                    modifier = Modifier.size(32.dp)
                )
                VoiceButtonState.GENERATING -> Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop thinking",
                    modifier = Modifier.size(32.dp)
                )
                VoiceButtonState.IDLE -> Icon(
                    Icons.Default.Mic,
                    contentDescription = "Start voice input",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/**
 * Synthetic user-side bubble shown at the end of the message list while a voice-only instant-send
 * transcription is running (P9.3): stands in for the not-yet-sent message and is naturally
 * replaced once the real one lands in [ConversationViewModel.messages] (or simply disappears, on
 * an empty transcription — the error snackbar covers that case instead).
 *
 * The hand-off does not flicker: `viewModelScope` runs on `Dispatchers.Main.immediate`, so the
 * `launch { performSend(...) }` that appends the real user message runs inline — the message is
 * already in [ConversationViewModel.messages] before the transcribing flag that hides this bubble
 * clears, so both land in the same recomposition.
 */
@Composable
private fun PendingTranscriptionBubble() {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
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

/** Full-width selectable list of "System default" + the engine's available voices; each
 *  selection applies (and previews) immediately. Stays open until dismissed (swipe/scrim) so the
 *  user can compare voices. Shows a loading row while the engine is still initializing (#52) —
 *  [ConversationViewModel.ttsReady] is observed, so the list replaces the loading row as soon as
 *  init finishes without the user needing to reopen the sheet, and a failed init gets its own
 *  message rather than spinning forever. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(
    conversationViewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val ttsVoiceId by conversationViewModel.ttsVoiceId.collectAsState()
    val ttsEnabled by conversationViewModel.ttsEnabled.collectAsState()
    val ttsReady by conversationViewModel.ttsReady.collectAsState()
    // Evaluated on every composition, not just in the branch that shows the list: this call is
    // what lazily builds the engine (and so what starts init and eventually flips ttsReady).
    // Moving it inside the `else` branch below would leave the loading row spinning forever.
    val voices = remember(ttsReady, ttsEnabled) { conversationViewModel.availableVoices() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Text(
            "Voice",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when {
            !ttsEnabled -> {
                Text(
                    "Turn spoken replies on to see available voices.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            ttsReady == null -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Starting speech engine…")
                }
            }
            ttsReady == false -> {
                Text(
                    "This device's speech engine could not be started, so no voices are available.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.selectableGroup()) {
                    item {
                        VoiceRow(
                            label = "System default",
                            // A persisted id that isn't in the list (unusable/uninstalled voice
                            // data, #67) is one the engine has already fallen back to the default
                            // for, so show that here rather than leaving nothing selected.
                            selected = ttsVoiceId.isEmpty() || voices.none { it.id == ttsVoiceId },
                            onClick = { conversationViewModel.setTtsVoice("") }
                        )
                    }
                    if (voices.isEmpty()) {
                        item {
                            Text(
                                "No other voices available — your speech engine offers only the " +
                                    "system default.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(voices) { voice: VoiceInfo ->
                        VoiceRow(
                            label = voice.label,
                            selected = ttsVoiceId == voice.id,
                            onClick = { conversationViewModel.setTtsVoice(voice.id) }
                        )
                    }
                }
            }
        }
    }
}

/** A single full-width, tappable "radio + label" row used by [VoiceSheet]. */
@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/**
 * A single message bubble. AGENT messages (never USER ones) get a small trailing "Read aloud"
 * icon below the bubble (P9.2): tapping it replays that message's text via [onReplayClick]; while
 * this is the message currently replaying ([isReplaying]), the icon becomes Stop and tapping it
 * calls [onStopReplayClick] instead.
 */
@Composable
private fun ChatBubble(
    message: ChatMessage,
    isReplaying: Boolean,
    onReplayClick: () -> Unit,
    onStopReplayClick: () -> Unit
) {
    val isUser = message.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
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
        if (!isUser) {
            IconButton(
                onClick = { if (isReplaying) onStopReplayClick() else onReplayClick() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isReplaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isReplaying) "Stop reading" else "Read aloud",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
