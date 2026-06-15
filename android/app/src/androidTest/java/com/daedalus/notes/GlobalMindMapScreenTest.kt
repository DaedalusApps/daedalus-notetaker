package com.daedalus.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.daedalus.notes.ble.BleManager
import com.daedalus.notes.ble.BleState
import com.daedalus.notes.ui.mindmap.GlobalGraph
import com.daedalus.notes.ui.mindmap.GraphNode
import com.daedalus.notes.ui.screens.GlobalMindMapScreen
import com.daedalus.notes.ui.theme.DaedalusTheme
import com.daedalus.notes.viewmodel.DeviceViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GlobalMindMapScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val deviceViewModel = mockk<DeviceViewModel>(relaxed = true)
    private val bleManager = mockk<BleManager>(relaxed = true)
    private val bleStateFlow = MutableStateFlow(BleState())

    @Before
    fun setup() {
        every { deviceViewModel.bleManager } returns bleManager
        every { bleManager.bleState } returns bleStateFlow
    }

    @Test
    fun globalMindMapScreen_displaysTopicsAndRecordings() {
        val graph = GlobalGraph(
            nodes = listOf(
                GraphNode("r1", "Recording One", GraphNode.Type.RECORDING),
                GraphNode("t1", "Topic AI", GraphNode.Type.TOPIC)
            ),
            edges = emptyList()
        )

        composeTestRule.setContent {
            DaedalusTheme {
                GlobalMindMapScreen(
                    graph = graph,
                    deviceViewModel = deviceViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        // Nodes should be rendered on canvas (we check for labels)
        composeTestRule.onNodeWithText("Recording One").assertExists()
        composeTestRule.onNodeWithText("Topic AI").assertExists()
    }

    @Test
    fun globalMindMapScreen_showsEmptyState() {
        composeTestRule.setContent {
            DaedalusTheme {
                GlobalMindMapScreen(
                    graph = GlobalGraph(emptyList(), emptyList()),
                    deviceViewModel = deviceViewModel,
                    onNavigateToNote = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("No topics analyzed yet.").assertIsDisplayed()
    }
}
