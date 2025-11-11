
package com.autobet.autobetluki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Permission granted for screen capture")
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            Log.d(TAG, "startForegroundService invoked for ScreenCaptureService")
        } else {
            Log.w(TAG, "Screen capture permission denied resultCode=${result.resultCode}")
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { 
        // After user returns from settings screen, check permission again
        if (Settings.canDrawOverlays(this)) {
            startCoordinateService()
        } else {
            Toast.makeText(this, "Overlay permission is required to show coordinates", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startCoordinateService()
        }
    }

    private fun startCoordinateService() {
        val serviceIntent = Intent(this, TouchCoordinateService::class.java)
        startService(serviceIntent)
        Log.d(TAG, "Start service intent sent for TouchCoordinateService")
    }
    
    private fun stopCoordinateService() {
        val serviceIntent = Intent(this, TouchCoordinateService::class.java)
        stopService(serviceIntent)
        Log.d(TAG, "Stop service intent sent for TouchCoordinateService")
    }

    private fun openAccessibilitySettings() {
        Log.d(TAG, "Opening accessibility settings for auto click enablement")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Toast.makeText(
            this,
            "Hãy bật 'AutobetLuki AutoClickService' trong danh sách dịch vụ trợ năng",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContent {
            ConfigScreen(
                onStartClick = {
                    Log.d(TAG, "Start Capture button pressed")
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                },
                onStopClick = {
                    Log.d(TAG, "Stop Capture button pressed")
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    startService(serviceIntent)
                    Log.d(TAG, "Stop service intent sent for ScreenCaptureService")
                },
                onStartShowCoordinatesClick = {
                    Log.d(TAG, "Start Show Coordinates button pressed")
                    checkAndRequestOverlayPermission()
                },
                onStopShowCoordinatesClick = {
                    Log.d(TAG, "Stop Show Coordinates button pressed")
                    stopCoordinateService()
                },
                onOpenAccessibilitySettingsClick = {
                    openAccessibilitySettings()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onStartShowCoordinatesClick: () -> Unit,
    onStopShowCoordinatesClick: () -> Unit,
    onOpenAccessibilitySettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val logTag = "ConfigScreen"
    val sharedPref = remember { context.getSharedPreferences("bet_config", Context.MODE_PRIVATE) }
    
    var deviceName by remember { 
        mutableStateOf(sharedPref.getString("deviceName", "") ?: "") 
    }
    
    val betOptions = listOf("cược Tài", "cược Xỉu")
    var selectedBetOption by remember { 
        mutableStateOf(sharedPref.getString("betOption", "cược Tài") ?: "cược Tài") 
    }
    var expanded by remember { mutableStateOf(false) }
    
    var openHistoryCoord by remember { 
        mutableStateOf(sharedPref.getString("openHistoryCoord", "") ?: "") 
    }
    var closeHistoryCoord by remember { 
        mutableStateOf(sharedPref.getString("closeHistoryCoord", "") ?: "") 
    }
    var startBetTaiCoord by remember { 
        mutableStateOf(sharedPref.getString("startBetTaiCoord", "") ?: "") 
    }
    var startBetXiuCoord by remember { 
        mutableStateOf(sharedPref.getString("startBetXiuCoord", "") ?: "") 
    }
    var bet1KButtonCoord by remember { 
        mutableStateOf(sharedPref.getString("bet1KButtonCoord", "") ?: "") 
    }
    var startBetButtonCoord by remember { 
        mutableStateOf(sharedPref.getString("startBetButtonCoord", "") ?: "") 
    }
    var secondsRegion by remember {
        mutableStateOf(sharedPref.getString("secondsRegion", "") ?: "")
    }
    var betAmountRegion by remember {
        mutableStateOf(sharedPref.getString("betAmountRegion", "") ?: "")
    }

    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text("Start Capture")
                }

                Button(
                    onClick = onStopClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text("Stop Capture")
                }
            }

            Button(
                onClick = onOpenAccessibilitySettingsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Bật Auto Click (Accessibility)")
            }

            Text(
                "Cấu hình cược",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Device name") },
                modifier = Modifier.fillMaxWidth()
            )
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedBetOption,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Loại cược") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    betOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                selectedBetOption = option
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            Text(
                "Tọa độ các nút (format: x;y)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            OutlinedTextField(
                value = openHistoryCoord,
                onValueChange = { openHistoryCoord = it },
                label = { Text("Mở lịch sử") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = closeHistoryCoord,
                onValueChange = { closeHistoryCoord = it },
                label = { Text("Đóng lịch sử") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = startBetTaiCoord,
                onValueChange = { startBetTaiCoord = it },
                label = { Text("Bắt đầu cược Tài") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = startBetXiuCoord,
                onValueChange = { startBetXiuCoord = it },
                label = { Text("Bắt đầu cược Xỉu") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = bet1KButtonCoord,
                onValueChange = { bet1KButtonCoord = it },
                label = { Text("Nút cược 1K") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = startBetButtonCoord,
                onValueChange = { startBetButtonCoord = it },
                label = { Text("Bắt đầu cược") },
                placeholder = { Text("x;y") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                "Vùng nhận diện (format: x1:y1;x2:y2)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            OutlinedTextField(
                value = secondsRegion,
                onValueChange = { secondsRegion = it },
                label = { Text("Vùng giây") },
                placeholder = { Text("x1:y1;x2:y2") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = betAmountRegion,
                onValueChange = { betAmountRegion = it },
                label = { Text("Vùng tiền cược") },
                placeholder = { Text("x1:y1;x2:y2") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    Log.d(logTag, "Save button pressed")
                    with(sharedPref.edit()) {
                        putString("deviceName", deviceName)
                        putString("betOption", selectedBetOption)
                        putString("openHistoryCoord", openHistoryCoord)
                        putString("closeHistoryCoord", closeHistoryCoord)
                        putString("startBetTaiCoord", startBetTaiCoord)
                        putString("startBetXiuCoord", startBetXiuCoord)
                        putString("bet1KButtonCoord", bet1KButtonCoord)
                        putString("startBetButtonCoord", startBetButtonCoord)
                        putString("secondsRegion", secondsRegion)
                        putString("betAmountRegion", betAmountRegion)
                        apply()
                    }
                    Toast.makeText(context, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
                    Log.d(logTag, "Configuration saved to SharedPreferences")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(top = 16.dp)
            ) {
                Text("Save")
            }

            // Coordinate display buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartShowCoordinatesClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Start Show Coords")
                }

                Button(
                    onClick = onStopShowCoordinatesClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Show Coords")
                }
            }
        }
    }
}
