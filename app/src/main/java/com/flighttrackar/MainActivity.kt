package com.flighttrackar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flighttrackar.ui.FlightArScreen
import com.flighttrackar.ui.FlightArViewModel
import com.flighttrackar.ui.theme.FlightTrackArTheme
import com.flighttrackar.ui.theme.HudColors

class MainActivity : ComponentActivity() {

    private val viewModel: FlightArViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            FlightTrackArTheme {
                PermissionGateAndArView(
                    viewModel = viewModel,
                    hasPermissions = { hasRequiredPermissions() },
                    onPermissionsGranted = {
                        updateCurrentDisplayRotation()
                        viewModel.startTracking()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentDisplayRotation()
        if (hasRequiredPermissions()) {
            viewModel.startTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopTracking()
    }

    private fun updateCurrentDisplayRotation() {
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        viewModel.updateDisplayRotation(rot)
    }

    private fun hasRequiredPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return cam && (fineLoc || coarseLoc)
    }
}

@Composable
private fun PermissionGateAndArView(
    viewModel: FlightArViewModel,
    hasPermissions: () -> Boolean,
    onPermissionsGranted: () -> Unit
) {
    var granted by remember { mutableStateOf(hasPermissions()) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camOk = results[Manifest.permission.CAMERA] == true
        val locOk = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        granted = camOk && locOk
        if (granted) onPermissionsGranted()
    }

    LaunchedEffect(granted) {
        if (granted) {
            onPermissionsGranted()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopTracking() }
    }

    if (granted) {
        FlightArScreen(viewModel = viewModel)
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(HudColors.DeepNightBg)
                .padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FlightTakeoff,
                contentDescription = null,
                tint = HudColors.CyanPrimary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "FlightTrackAR",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = HudColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Camera and Location permissions are needed to overlay live ADS-B flights onto the sky, predict aircraft motion with a 3D Kalman filter, and detect planes visually in Day or Night skies.",
                style = MaterialTheme.typography.bodyMedium,
                color = HudColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            ) {
                Text("Grant Camera & Location Access")
            }
        }
    }
}
