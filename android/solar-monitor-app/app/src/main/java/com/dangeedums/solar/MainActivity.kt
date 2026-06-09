package com.dangeedums.solar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dangeedums.solar.ui.MainViewModel
import com.dangeedums.solar.ui.ScanScreen
import com.dangeedums.solar.ui.SavedDevicesScreen
import com.dangeedums.solar.ui.theme.SolarMonitorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SolarMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("scan") }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_scan))
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "saved",
            modifier = Modifier.padding(padding),
        ) {
            composable("saved") {
                SavedDevicesScreen(
                    devices = savedDevices,
                    onRemove = viewModel::removeDevice,
                )
            }
            composable("scan") {
                ScanScreen(
                    state = scanState,
                    onStart = viewModel::startScan,
                    onStop = viewModel::stopScan,
                    onAdd = viewModel::addDevice,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppRootPreview() {
    SolarMonitorTheme { Text("Solar Monitor") }
}
