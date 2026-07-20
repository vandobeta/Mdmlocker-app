package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.service.ForegroundTaskMonitorService
import com.example.service.MyAccessibilityService
import com.example.ui.DashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var startupCrashThrowable: Throwable? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    try {
      super.onCreate(savedInstanceState)
      enableEdgeToEdge()

      // Show over system lock screen (Keyguard) and wake screen on startup
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
      } else {
        @Suppress("DEPRECATION")
        window.addFlags(
          android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
      }
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

      // 1. Set up the robust UI Thread Uncaught Exception Handler crash receiver safely without redundant nesting
      val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
      if (defaultHandler !is com.example.receiver.MdmCrashHandler) {
        Thread.setDefaultUncaughtExceptionHandler(com.example.receiver.MdmCrashHandler(applicationContext, defaultHandler))
      }

      // 2. Request ignoring battery optimizations to prevent background policy enforcement termination
      requestIgnoreBatteryOptimizations()

      // 2b. Request WRITE_SETTINGS permission (required to enforce global/system settings tables).
      //      WRITE_SECURE_SETTINGS is a signature-level permission and can only be granted
      //      when the app is a Device Owner / profile owner; otherwise it remains unavailable.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
          !android.provider.Settings.System.canWrite(applicationContext)) {
          try {
              val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                  data = Uri.parse("package:$packageName")
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              startActivity(intent)
          } catch (e: Exception) {
              Log.e("MainActivity", "Failed to launch WRITE_SETTINGS intent", e)
          }
      }

      // 2c. Request exact alarm permission on Android 12+
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
          if (!alarmManager.canScheduleExactAlarms()) {
              try {
                  val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                      data = Uri.parse("package:$packageName")
                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                  startActivity(intent)
              } catch (e: Exception) {
                  Log.e("MainActivity", "Failed to launch SCHEDULE_EXACT_ALARM intent", e)
              }
          }
      }

      // 3. Start the Foreground Task Monitor Service to continuously enforce overlays
      ForegroundTaskMonitorService.start(applicationContext)

      // Dynamically lock/unlock application task screen into Kiosk Mode based on Lock Screen status
      lifecycleScope.launch {
        viewModel.isLockScreenActive.collectLatest { isLocked ->
          // Sync lock state directly to accessibility handler
          MyAccessibilityService.isLockScreenActive = isLocked

          if (isLocked) {
            try {
              Log.i("MainActivity", "Device Locked: Initiating Lock Task Mode (Kiosk Mode).")
              startLockTask()
            } catch (e: Exception) {
              Log.w("MainActivity", "startLockTask failed (device is not provisioned as Device Owner). Falling back to secure overlay and accessibility blockers: ${e.message}")
            }
          } else {
            try {
              Log.i("MainActivity", "Device Unlocked: Exiting Lock Task Mode.")
              stopLockTask()
            } catch (e: Exception) {
              Log.v("MainActivity", "Failed to stop Lock Task or not in locked state: ${e.message}")
            }
          }
        }
      }

      setContent {
        MyApplicationTheme {
          Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            DashboardScreen(
              viewModel = viewModel,
              modifier = Modifier.padding(innerPadding)
            )
          }
        }
      }
    } catch (t: Throwable) {
      startupCrashThrowable = t
      Log.e("MainActivity", "CRASH ON LAUNCH DETECTED!", t)
      showDiagnosticScreen(t)
    }
  }

  fun performEmergencyCall(number: String) {
    try {
      Log.i("MainActivity", "Emergency dialer trigger: stopping Lock Task to allow call.")
      stopLockTask()
    } catch (e: Exception) {
      Log.e("MainActivity", "Failed to stop Lock Task Mode", e)
    }

    try {
      val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      startActivity(intent)
    } catch (e: Exception) {
      Log.e("MainActivity", "Failed to start dialer activity for $number", e)
    }
  }

  override fun onResume() {
    super.onResume()
    if (startupCrashThrowable != null) return
    if (viewModel.isLockScreenActive.value) {
      try {
        Log.i("MainActivity", "Activity resumed: lock active, re-engaging Lock Task Mode.")
        startLockTask()
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to re-engage Lock Task in onResume", e)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (startupCrashThrowable != null) return
    if (viewModel.isLockScreenActive.value) {
      try {
        Log.i("MainActivity", "Activity received new intent: lock active, re-engaging Lock Task Mode.")
        startLockTask()
      } catch (e: Exception) {
        Log.e("MainActivity", "Failed to re-engage Lock Task in onNewIntent", e)
      }
    }
  }

  private fun requestIgnoreBatteryOptimizations() {
    try {
      val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
          val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
          }
          startActivity(intent)
        }
      }
    } catch (e: Exception) {
      Log.e("MainActivity", "Failed to request battery optimization exclusion: ${e.message}")
    }
  }

  private fun showDiagnosticScreen(t: Throwable) {
    try {
      setContent {
        MyApplicationTheme {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.errorContainer
          ) {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              Text(
                text = "Application Diagnostics",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
              )

              Text(
                text = "A startup crash was intercepted. You can view the technical details below or attempt an automatic database and preferences reset to restore functionality.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
              )

              Card(
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.padding(16.dp)
                ) {
                  Text(
                    text = "Exception: ${t.javaClass.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                  )
                  Text(
                    text = t.localizedMessage ?: "No error message provided.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }

              Text(
                text = "Technical Stack Trace:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
              )

              Card(
                colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = t.stackTraceToString(),
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                  fontFamily = FontFamily.Monospace
                )
              }

              Spacer(modifier = Modifier.weight(1f))

              Button(
                onClick = {
                  try {
                    deleteDatabase("familyguard_database")
                    getSharedPreferences("familyguard_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                    val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                    Runtime.getRuntime().exit(0)
                  } catch (e: Exception) {
                    Log.e("MainActivity", "Reset action failed", e)
                  }
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.align(Alignment.End)
              ) {
                Text("Reset Application & DB")
              }
            }
          }
        }
      }
    } catch (inner: Throwable) {
      Log.e("MainActivity", "Failed to render diagnostics UI", inner)
    }
  }
}
