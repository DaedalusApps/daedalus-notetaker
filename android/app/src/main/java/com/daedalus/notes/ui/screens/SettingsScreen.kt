package com.daedalus.notes.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daedalus.notes.ai.AVAILABLE_MODELS
import com.daedalus.notes.ai.PREFS_MODEL_ID
import com.daedalus.notes.ai.modelFile
import com.daedalus.notes.ai.selectedModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("daedalus_prefs", Context.MODE_PRIVATE) }
    val scope   = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selectedModelId by remember { mutableStateOf(prefs.getString(PREFS_MODEL_ID, "gemma4_e4b") ?: "gemma4_e4b") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var autoProcess by remember { mutableStateOf(prefs.getBoolean("auto_process", false)) }

    val selectedModelObj = AVAILABLE_MODELS.find { it.id == selectedModelId } ?: AVAILABLE_MODELS[0]
    val modelFileOnDisk  = modelFile(context, selectedModelId)
    val modelSizeMb      = if (modelFileOnDisk.exists()) modelFileOnDisk.length() / 1_048_576 else 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Local AI Model section
            Text("Local AI Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedModelObj.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("AI Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelDropdownExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = modelDropdownExpanded, onDismissRequest = { modelDropdownExpanded = false }) {
                    AVAILABLE_MODELS.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { selectedModelId = m.id; modelDropdownExpanded = false }
                        )
                    }
                }
            }

            // Model file info
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Model Storage", style = MaterialTheme.typography.labelMedium)
                    if (modelFileOnDisk.exists()) {
                        Text("Downloaded: ${modelSizeMb} MB", style = MaterialTheme.typography.bodyMedium)
                        Text(modelFileOnDisk.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Not downloaded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text("Go back to the main screen to download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Recording behaviour
            Text("Recording Behaviour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-process on import", style = MaterialTheme.typography.bodyMedium)
                    Text("Analyze recordings automatically when imported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoProcess, onCheckedChange = { autoProcess = it })
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    prefs.edit()
                        .putString(PREFS_MODEL_ID, selectedModelId)
                        .putBoolean("auto_process", autoProcess)
                        .apply()
                    scope.launch { snackbar.showSnackbar("Settings saved") }
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Settings") }

            // Version info footer
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

            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Version $versionName (Build $versionCode)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
