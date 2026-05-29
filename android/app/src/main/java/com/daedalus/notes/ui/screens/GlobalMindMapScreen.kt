package com.daedalus.notes.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daedalus.notes.ui.mindmap.GlobalGraph
import com.daedalus.notes.ui.mindmap.GraphNode
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class PositionedGraphNode(
    val node: GraphNode,
    val x: Float,
    val y: Float,
    var rect: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalMindMapScreen(
    graph: GlobalGraph,
    onNavigateToNote: (String) -> Unit,
    onBack: () -> Unit
) {
    var showHelp by remember { mutableStateOf(false) }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Using the Knowledge Graph") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This map visualizes the relationships between your recordings based on topics identified by AI.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Text("• Nodes", fontWeight = FontWeight.Bold)
                    Text(
                        "Large rectangles represent key Topics. Smaller rectangles represent your Recordings. Tap a recording to open it.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("• Connections", fontWeight = FontWeight.Bold)
                    Text(
                        "Lines show which recordings share common topics. Clustered nodes indicate related themes across your library.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("• Exploration", fontWeight = FontWeight.Bold)
                    Text(
                        "Pinch to zoom in/out and drag to pan across your semantic landscape.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Got it") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Knowledge Graph") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.Info, contentDescription = "How to use", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        if (graph.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No topics analyzed yet.", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            GlobalMindMapCanvas(graph, onNavigateToNote, Modifier.padding(innerPadding))
        }
    }
}

@Composable
fun GlobalMindMapCanvas(
    graph: GlobalGraph,
    onNavigateToNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val textMeasurer = rememberTextMeasurer()
    
    var scale by remember { mutableStateOf(0.8f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.1f, 5f)
        offset += panChange
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    var selectedTopic by remember { mutableStateOf<PositionedGraphNode?>(null) }

    BoxWithConstraints(modifier.fillMaxSize().transformable(transformState)) {
        val cx = constraints.maxWidth / 2f
        val cy = constraints.maxHeight / 2f

        val positionedNodes = remember(graph, cx, cy) {
            val result = mutableListOf<PositionedGraphNode>()
            val topicNodes = graph.nodes.filter { it.type == GraphNode.Type.TOPIC }
            val recordingNodes = graph.nodes.filter { it.type == GraphNode.Type.RECORDING }

            // Place topics in a circle
            topicNodes.forEachIndexed { i, node ->
                val angle = (2 * PI * i / topicNodes.size).toFloat()
                val radius = 350f * density
                result.add(PositionedGraphNode(node, cx + radius * cos(angle), cy + radius * sin(angle)))
            }

            // Place recordings in a wider outer circle
            recordingNodes.forEachIndexed { i, node ->
                val angle = (2 * PI * i / recordingNodes.size).toFloat()
                val radius = 650f * density
                result.add(PositionedGraphNode(node, cx + radius * cos(angle), cy + radius * sin(angle)))
            }
            result
        }
        
        val nodeMap = remember(positionedNodes) { positionedNodes.associateBy { it.node.id } }

        selectedTopic?.let { topic ->
            val connectedRecordings = graph.edges
                .filter { it.toId == topic.node.id }
                .mapNotNull { nodeMap[it.fromId]?.node?.label }
            AlertDialog(
                onDismissRequest = { selectedTopic = null },
                title = { Text(topic.node.label) },
                text = if (connectedRecordings.isNotEmpty()) ({
                    Text("Recordings:\n" + connectedRecordings.joinToString("\n") { "• $it" })
                }) else null,
                confirmButton = {
                    TextButton(onClick = { selectedTopic = null }) { Text("OK") }
                }
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(positionedNodes, scale, offset) {
                    detectTapGestures { tapOffset ->
                        // Calculate where the tap was in the untransformed canvas space
                        val transformedTapX = (tapOffset.x - offset.x - cx) / scale + cx
                        val transformedTapY = (tapOffset.y - offset.y - cy) / scale + cy
                        val transformedTap = Offset(transformedTapX, transformedTapY)

                        positionedNodes.forEach { p ->
                            if (p.node.type == GraphNode.Type.TOPIC && p.rect.contains(transformedTap)) {
                                selectedTopic = p
                                return@detectTapGestures
                            }
                            if (p.node.type == GraphNode.Type.RECORDING && p.rect.contains(transformedTap)) {
                                val filename = p.node.id.removePrefix("rec_")
                                onNavigateToNote(filename)
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, Offset(cx, cy))
            }) {
                // Draw Edges
                graph.edges.forEach { edge ->
                    val from = nodeMap[edge.fromId] ?: return@forEach
                    val to = nodeMap[edge.toId] ?: return@forEach
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.4f),
                        start = Offset(from.x, from.y),
                        end = Offset(to.x, to.y),
                        strokeWidth = 2f
                    )
                }
            }
        }

        // Render Nodes as Composables for testability and accessibility
        Box(Modifier.fillMaxSize()) {
            positionedNodes.forEach { p ->
                val label = p.node.label.take(20)
                val isTopic = p.node.type == GraphNode.Type.TOPIC
                
                // Calculate position considering scale and offset
                // This is tricky because these are Composables inside a Box.
                // We'll use absolute offsets and scale modifier.
                
                val paddingH = if (isTopic) 10.dp else 8.dp
                val paddingV = if (isTopic) 6.dp else 4.dp
                val color = if (isTopic) secondaryColor else tertiaryColor

                // Pre-calculate rect for tap detection in Canvas (already done in previous version but we need to update it here)
                // Actually, let's keep the rect calculation in a way that matches what we draw.
                val textStyle = TextStyle(color = Color.White, fontSize = if (isTopic) 14.sp else 12.sp, fontWeight = if (isTopic) FontWeight.Bold else FontWeight.Normal)
                val textLayout = textMeasurer.measure(label, textStyle)
                val rectW = (textLayout.size.width / density + paddingH.value * 2).dp
                val rectH = (textLayout.size.height / density + paddingV.value * 2).dp
                
                // Update rect for tap detection (untransformed space)
                val paddingHPx = with(LocalDensity.current) { paddingH.toPx() }
                val paddingVPx = with(LocalDensity.current) { paddingV.toPx() }
                val topLeftUntransformed = Offset(p.x - (textLayout.size.width + paddingHPx * 2) / 2, p.y - (textLayout.size.height + paddingVPx * 2) / 2)
                p.rect = androidx.compose.ui.geometry.Rect(topLeftUntransformed, Size(textLayout.size.width + paddingHPx * 2, textLayout.size.height + paddingVPx * 2))

                Surface(
                    color = color,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                (offset.x + (p.x - cx) * scale + cx - (textLayout.size.width + paddingHPx * 2) * scale / 2).toInt(),
                                (offset.y + (p.y - cy) * scale + cy - (textLayout.size.height + paddingVPx * 2) * scale / 2).toInt()
                            )
                        }
                        .size(rectW * scale, rectH * scale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = (if (isTopic) 14.sp else 12.sp) * scale,
                            fontWeight = if (isTopic) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
