
package com.autobet.autobetluki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.Secure
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
        Log.d(TAG, "Screen capture permission result received: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Permission granted for screen capture")
            try {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                Log.d(TAG, "startForegroundService invoked for ScreenCaptureService")
            } catch (e: Exception) {
                Log.e(TAG, "Exception when starting ScreenCaptureService", e)
                Toast.makeText(this, "Lỗi khi khởi động service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "Screen capture permission denied or cancelled. resultCode=${result.resultCode}")
            Toast.makeText(this, "Quyền capture màn hình bị từ chối (resultCode=${result.resultCode})", Toast.LENGTH_LONG).show()
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
                    try {
                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (mediaProjectionManager == null) {
                            Log.e(TAG, "Failed to get MediaProjectionManager service")
                            Toast.makeText(this, "Không thể truy cập MediaProjectionManager", Toast.LENGTH_LONG).show()
                            return@ConfigScreen
                        }
                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                        Log.d(TAG, "Created screen capture intent, launching...")
                        screenCaptureLauncher.launch(captureIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception when requesting screen capture permission", e)
                        Toast.makeText(this, "Lỗi khi yêu cầu quyền capture: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                onStopClick = {
                    Log.d(TAG, "Stop Capture button pressed")
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }
                    startService(serviceIntent)
                    Log.d(TAG, "Stop service intent sent for ScreenCaptureService")
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
    onOpenAccessibilitySettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val logTag = "ConfigScreen"
    val sharedPref = remember { context.getSharedPreferences("bet_config", Context.MODE_PRIVATE) }
    
    // Initialize device name: use saved value or get ANDROID_ID as default
    var deviceName by remember { 
        val savedDeviceName = sharedPref.getString("deviceName", "")?.trim()
        mutableStateOf(
            savedDeviceName?.takeIf { it.isNotEmpty() } 
                ?: Secure.getString(context.contentResolver, Secure.ANDROID_ID).takeIf { it.isNotEmpty() }
                ?: ""
        )
    }
    
    // Initialize skip rounds: use saved value or default to 0
    var skipRounds by remember {
        mutableStateOf(sharedPref.getInt("skipRoundsAfter5Losses", 0))
    }
    
    // Initialize consecutive loss threshold: use saved value or default to 5
    var consecutiveLossThreshold by remember {
        mutableStateOf(sharedPref.getInt("consecutiveLossThreshold", 5))
    }
    
    // Save ANDROID_ID as default device name on first install if not already set
    LaunchedEffect(Unit) {
        val savedDeviceName = sharedPref.getString("deviceName", "")?.trim()
        if (savedDeviceName.isNullOrEmpty()) {
            val androidId = Secure.getString(context.contentResolver, Secure.ANDROID_ID)
            if (androidId.isNotEmpty()) {
                sharedPref.edit().putString("deviceName", androidId).apply()
                deviceName = androidId
                Log.d(logTag, "Set default device name to ANDROID_ID: $androidId")
            }
        }
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
                "Cấu hình",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Device name") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                "Số ván thua liên tiếp để bắt đầu skip",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decrease button
                Button(
                    onClick = {
                        if (consecutiveLossThreshold > 1) {
                            consecutiveLossThreshold--
                        }
                    },
                    modifier = Modifier
                        .width(56.dp)
                        .height(56.dp),
                    enabled = consecutiveLossThreshold > 1
                ) {
                    Text("-", style = MaterialTheme.typography.headlineMedium)
                }
                
                // Display current value
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = consecutiveLossThreshold.toString(),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
                
                // Increase button
                Button(
                    onClick = {
                        if (consecutiveLossThreshold < 20) {
                            consecutiveLossThreshold++
                        }
                    },
                    modifier = Modifier
                        .width(56.dp)
                        .height(56.dp),
                    enabled = consecutiveLossThreshold < 20
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }
            
            Text(
                "Số ván skip sau $consecutiveLossThreshold thua liên tiếp",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decrease button
                Button(
                    onClick = {
                        if (skipRounds > 0) {
                            skipRounds--
                        }
                    },
                    modifier = Modifier
                        .width(56.dp)
                        .height(56.dp),
                    enabled = skipRounds > 0
                ) {
                    Text("-", style = MaterialTheme.typography.headlineMedium)
                }
                
                // Display current value
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = skipRounds.toString(),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
                
                // Increase button
                Button(
                    onClick = {
                        if (skipRounds < 5) {
                            skipRounds++
                        }
                    },
                    modifier = Modifier
                        .width(56.dp)
                        .height(56.dp),
                    enabled = skipRounds < 5
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
                
                // Save button
                Button(
                    onClick = {
                        Log.d(logTag, "Save button pressed: consecutiveLossThreshold=$consecutiveLossThreshold, skipRounds=$skipRounds, deviceName=$deviceName")
                        with(sharedPref.edit()) {
                            putString("deviceName", deviceName)
                            putInt("consecutiveLossThreshold", consecutiveLossThreshold)
                            putInt("skipRoundsAfter5Losses", skipRounds)
                            apply()
                        }
                        Toast.makeText(context, "Đã lưu cấu hình (Bắt đầu skip sau $consecutiveLossThreshold thua, Skip: $skipRounds ván)", Toast.LENGTH_SHORT).show()
                        Log.d(logTag, "Configuration saved to SharedPreferences: deviceName=$deviceName, consecutiveLossThreshold=$consecutiveLossThreshold, skipRoundsAfter5Losses=$skipRounds")
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
