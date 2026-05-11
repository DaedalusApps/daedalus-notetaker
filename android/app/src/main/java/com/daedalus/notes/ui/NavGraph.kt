package com.daedalus.notes.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.daedalus.notes.ui.screens.DeviceScreen
import com.daedalus.notes.ui.screens.NoteDetailScreen
import com.daedalus.notes.ui.screens.RecordingsScreen
import com.daedalus.notes.ui.screens.SettingsScreen
import com.daedalus.notes.viewmodel.DeviceViewModel
import com.daedalus.notes.viewmodel.RecordingViewModel

@Composable
fun NavGraph(
    navController: NavHostController,
    deviceViewModel: DeviceViewModel,
    recordingViewModel: RecordingViewModel
) {
    NavHost(
        navController = navController,
        startDestination = "device"
    ) {
        composable("device") {
            DeviceScreen(
                viewModel = deviceViewModel,
                onNavigateToRecordings = { navController.navigate("recordings") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable("recordings") {
            RecordingsScreen(
                viewModel = deviceViewModel,
                recordingViewModel = recordingViewModel,
                onNavigateToNote = { filename ->
                    navController.navigate("note/${filename}")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "note/{filename}",
            arguments = listOf(
                navArgument("filename") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val filename = backStackEntry.arguments?.getString("filename") ?: ""
            NoteDetailScreen(
                filename = filename,
                recordingViewModel = recordingViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
