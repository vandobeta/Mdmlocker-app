package com.example.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.MainActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ScheduleEntity
import com.example.data.MdmPolicyEntity
import com.example.viewmodel.MainViewModel
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val isProvisioned by viewModel.isProvisioned.collectAsState()
    val challengeToken by viewModel.challengeToken.collectAsState()
    val bypassError by viewModel.bypassError.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val policies by viewModel.policies.collectAsState()

    val isParentLocked = remember(policies) {
        policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
    }

    val customLockMessage = remember(policies) {
        policies.find { it.key == "parentLockMessage" }?.value ?: "Device has been restricted by your parent"
    }

    val isLockActive = !isProvisioned || isParentLocked
    val syncStatus by viewModel.syncStatus.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (isLockActive) {
            // Render a stylized high-end Android Home Screen in the background
            MockAndroidHomeScreen()
        } else {
            // RENDER THE SECURED PARENT / CHILD CONSOLE DASHBOARD
            MainDashboardConsole(viewModel, modifier = Modifier.fillMaxSize())
        }

        if (!isProvisioned) {
            DeviceLockScreen(
                title = "Device Locked",
                message = "Device cannot be used without authorisation. To activate this device, please insert a valid sim card with a data plan, so app can connect and refresh policies automatically.",
                isProvisioned = false,
                deviceId = deviceId,
                challengeToken = challengeToken,
                bypassError = bypassError,
                onAttemptUnlock = { pin -> viewModel.attemptOfflineUnlock(pin) },
                onForceUnlock = { viewModel.setProvisionedState(true) },
                onRefreshStatus = { viewModel.fetchFromFirebase() },
                syncStatus = syncStatus
            )
        } else if (isParentLocked) {
            DeviceLockScreen(
                title = "Device Locked",
                message = customLockMessage.ifBlank { "Device has been restricted by your parent" },
                isProvisioned = true,
                deviceId = deviceId,
                challengeToken = challengeToken,
                bypassError = bypassError,
                onAttemptUnlock = { pin -> viewModel.attemptOfflineUnlock(pin) },
                onForceUnlock = { viewModel.setProvisionedState(true) },
                onRefreshStatus = { viewModel.fetchFromFirebase() },
                syncStatus = syncStatus,
                onUnlockParent = {
                    viewModel.addOrUpdatePolicy(
                        MdmPolicyEntity(
                            key = "parentLockActive",
                            value = "false",
                            valueType = "boolean",
                            permissionFlag = "GREY_OUT"
                        )
                    )
                    viewModel.addManualAuditLog(
                        "POLICY_ENFORCEMENT",
                        "Device lock released via parental PIN override."
                    )
                }
            )
        }

        // 1-Second real-time responsive "Blink Screen" custom message overlay
        val activeBlink by viewModel.activeBlink.collectAsState()
        activeBlink?.let { blink ->
            AlertDialog(
                onDismissRequest = { /* Enforce visual reading, do not dismiss on tap outside */ },
                confirmButton = {
                    Button(
                        onClick = { viewModel.dismissBlink() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("blink_ok_button")
                    ) {
                        Text("OK", style = MaterialTheme.typography.labelLarge)
                    }
                },
                title = {
                    Text(
                        text = blink.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        text = blink.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.testTag("blink_dialog")
            )
        }
    }
}

/**
 * Clean, non-technical device lock screen. Shown if not provisioned or if parent-locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLockScreen(
    title: String,
    message: String,
    isProvisioned: Boolean,
    deviceId: String,
    challengeToken: String,
    bypassError: String?,
    onAttemptUnlock: (String) -> Boolean,
    onForceUnlock: () -> Unit,
    onRefreshStatus: () -> Unit,
    syncStatus: String,
    onUnlockParent: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var activeSubScreen by remember { mutableStateOf(1) } // 1 = Main Lock Screen, 2 = Parent PIN Override Screen
    var bypassPinInput by remember { mutableStateOf("") }
    var showEmergencyDialog by remember { mutableStateOf(false) }

    val isSyncing = remember(syncStatus) {
        syncStatus.contains("Syncing", ignoreCase = true) || syncStatus.contains("Pushing", ignoreCase = true)
    }
    val isSuccess = remember(syncStatus) {
        syncStatus.contains("Success", ignoreCase = true)
    }
    val isFailed = remember(syncStatus) {
        syncStatus.contains("Failed", ignoreCase = true)
    }
    
    val todayDateStr = remember {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {} // Block touch events to background dashboard
            .background(Color(0xD9080505)) // Beautiful dark translucent frosted glass theme (85% opacity)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Giant Lock Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF330C0C)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Device locked",
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(44.dp)
                )
            }

            Text(
                text = "PROTECTION ACTIVE",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color(0xFFEF5350)
            )

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xBF1C1212)), // Semi-translucent dark glass card (75% opacity)
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (activeSubScreen == 1) {
                        // SCREEN 1: LOCKED NOTIFICATION & IDENTIFIERS
                        Text(
                            text = "SECURITY STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF5350),
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD3C5C5)
                        )

                        HorizontalDivider(color = Color(0xFF3A2E2E))

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "DEVICE IDENTIFIER",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(
                                text = deviceId,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "RESTRICTION DATE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(
                                text = todayDateStr,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.LightGray
                            )
                        }

                        // Emergency Dialer Button
                        Button(
                            onClick = { showEmergencyDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("emergency_dialer_button"),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Phone, contentDescription = "Emergency Call", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Emergency Call", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }

                        Button(
                            onClick = { activeSubScreen = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("challenge_bypass_switch"),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.VpnKey, contentDescription = "Key", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Unlock with PIN", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }

                        // Refresh Status Button (Background Red, Dynamic Spinner Animation + Text)
                        Button(
                            onClick = { if (!isSyncing) onRefreshStatus() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSyncing) Color(0x99D32F2F) else Color(0xFFD32F2F),
                                disabledContainerColor = Color(0x66D32F2F)
                            ),
                            enabled = !isSyncing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("refresh_status_button"),
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Getting latest policy...", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Status", tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refresh Status", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        // Dynamic Policy Status Banner depending on sync outcome
                        AnimatedVisibility(
                            visible = syncStatus.isNotBlank() && syncStatus != "Local Mode Active",
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            val statusInfo = when {
                                isSyncing -> Triple(Color(0x1F2196F3), Color(0xFF90CAF9), "Checking server for policies...")
                                isSuccess -> Triple(Color(0x1F4CAF50), Color(0xFFA5D6A7), "Policies successfully updated!")
                                isFailed -> Triple(Color(0x1FEF5350), Color(0xFFEF9A9A), "Sync failed. Offline mode active.")
                                else -> Triple(Color(0x1F9E9E9E), Color(0xFFE0E0E0), syncStatus)
                            }
                            val statusBg = statusInfo.first
                            val statusTextCol = statusInfo.second
                            val statusLabel = statusInfo.third

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(statusBg)
                                    .border(1.dp, statusTextCol.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isSyncing) {
                                    CircularProgressIndicator(
                                        color = statusTextCol,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    val statusIcon = when {
                                        isSuccess -> Icons.Default.CheckCircle
                                        isFailed -> Icons.Default.Error
                                        else -> Icons.Default.Info
                                    }
                                    Icon(
                                        imageVector = statusIcon,
                                        contentDescription = null,
                                        tint = statusTextCol,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = statusLabel,
                                    color = statusTextCol,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        // SCREEN 2: OFFLINE OVERRIDE PIN ENTRY
                        Text(
                            text = "UNLOCK WITH PIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFEF5350),
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Please provide this code to your parent to receive your unlock PIN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD3C5C5)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2C1E1E))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "AUTHORIZATION CODE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                                Text(
                                    text = challengeToken,
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp
                                    ),
                                    color = Color(0xFFEF5350)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = bypassPinInput,
                            onValueChange = { if (it.length <= 6) bypassPinInput = it },
                            label = { Text("6-Digit Override PIN", color = Color.LightGray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedBorderColor = Color(0xFFEF5350),
                                unfocusedBorderColor = Color(0xFF3A2E2E)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bypass_pin_field")
                        )

                        if (bypassError != null) {
                            Text(
                                text = if (bypassError.contains("Math Signature")) "Incorrect Override PIN." else bypassError,
                                color = Color(0xFFEF5350),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                if (isProvisioned && onUnlockParent != null) {
                                    val expectedPin = challengeToken.reversed()
                                    if (bypassPinInput == expectedPin) {
                                        onUnlockParent()
                                        bypassPinInput = ""
                                    } else {
                                        Log.w("LockScreen", "Parent Lock Screen: PIN verify fail")
                                    }
                                } else {
                                    val success = onAttemptUnlock(bypassPinInput)
                                    if (success) {
                                        bypassPinInput = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("verify_challenge_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Verify PIN", fontWeight = FontWeight.Bold)
                        }

                        // Emergency Dialer Button inside PIN screen
                        Button(
                            onClick = { showEmergencyDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("emergency_dialer_button_pin"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Phone, contentDescription = "Emergency Call", tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Emergency Call", fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        TextButton(
                            onClick = { activeSubScreen = 1 },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("< Back to Lock Screen", color = Color.Gray)
                        }
                    }
                }
            }

            // Quick bypass shortcut for testing / simulation
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151010)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            if (isProvisioned && onUnlockParent != null) {
                                onUnlockParent()
                            } else {
                                onForceUnlock() 
                            }
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.SettingsSuggest, contentDescription = "Sim", tint = Color.LightGray)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Development Override Bypass", style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Bypasses lock screen for testing", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go", tint = Color.Gray)
                }
            }
        }

        if (showEmergencyDialog) {
            AlertDialog(
                onDismissRequest = { showEmergencyDialog = false },
                title = {
                    Text(
                        text = "Global Emergency Numbers",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "To make an emergency call, select one of the global lines below. The lock restriction will suspend to make the call and automatically re-engage when you return.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        val emergencyNumbers = listOf(
                            "112" to "Universal European / Global",
                            "911" to "North America",
                            "999" to "United Kingdom",
                            "000" to "Australia",
                            "995" to "Singapore (Fire/Ambulance)",
                            "111" to "New Zealand"
                        )

                        emergencyNumbers.forEach { (number, region) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showEmergencyDialog = false
                                        (context as? MainActivity)?.performEmergencyCall(number)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1E1E)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(text = number, color = Color(0xFFEF5350), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text(text = region, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(imageVector = Icons.Default.Phone, contentDescription = "Dial", tint = Color(0xFF4CAF50))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEmergencyDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1717),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

/**
 * Main dashboard layout supporting both Schedules rules and the Experimental MDM Control Tables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardConsole(
    viewModel: MainViewModel,
    modifier: Modifier
) {
    val schedules by viewModel.schedules.collectAsState()
    val policies by viewModel.policies.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val isEnforcing by viewModel.isEnforcing.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Schedules, 1 = Experimental MDM Tables, 2 = Audit Logs

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Column {
                            Text(
                                text = "DBS FamilyGuard",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Active DO Node & Policy Sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.fetchFromFirebase() },
                        modifier = Modifier.testTag("sync_fetch_button")
                    ) {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = "Sync Fetch")
                    }
                    IconButton(
                        onClick = { viewModel.pushToFirebase() },
                        modifier = Modifier.testTag("sync_push_button")
                    ) {
                        Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Sync Push")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(imageVector = Icons.Default.CalendarToday, contentDescription = "Schedules") },
                    label = { Text("App Schedules") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(imageVector = Icons.Default.SettingsApplications, contentDescription = "MDM Tables") },
                    label = { Text("MDM Controls") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = "Audit Logs") },
                    label = { Text("Audit Logs") }
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_schedule_fab")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Schedule")
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val screenShareActive by viewModel.screenShareActive.collectAsState()
            val screenControlActive by viewModel.screenControlActive.collectAsState()

            if (screenShareActive || screenControlActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFD32F2F))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = "Cast Connected",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (screenControlActive) "🔴 REMOTE CONTROL CONSOLE ACTIVE" else "🔴 REMOTE SCREEN SHARING ACTIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Enterprise Sync Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "PROVISIONED MOBILE INSTANCE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = deviceId,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isEnforcing) Color(0xFF4CAF50) else Color(0xFFF44336))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isEnforcing) "Enforcing 1-Sec" else "Paused",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Icon",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Local Protection Guard",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = isEnforcing,
                            onCheckedChange = { viewModel.toggleService(it) },
                            modifier = Modifier.testTag("enforcement_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val parentLocked = remember(policies) {
                        policies.any { it.key == "parentLockActive" && it.value.lowercase() == "true" }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Parent Lock / Device Restriction",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = parentLocked,
                            onCheckedChange = { checked ->
                                viewModel.addOrUpdatePolicy(
                                    MdmPolicyEntity(
                                        key = "parentLockActive",
                                        value = checked.toString(),
                                        valueType = "boolean",
                                        permissionFlag = "GREY_OUT"
                                    )
                                )
                                viewModel.addManualAuditLog(
                                    "POLICY_ENFORCEMENT",
                                    if (checked) "Parent triggered immediate remote device lock." else "Parent released immediate remote device lock."
                                )
                            },
                            modifier = Modifier.testTag("parent_lock_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.setProvisionedState(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.LockOpen, contentDescription = "Lock Device")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Simulate Device Unprovisioned Block")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "SIMULATE BLINK SCREEN OVERLAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.triggerBlink(
                                    title = "Posture & Eye Rest Check",
                                    message = "This is an automatic 1-second visual guard. Take a deep breath, blink 5 times, and relax your eyes."
                                )
                                viewModel.addManualAuditLog(
                                    "BLINK_SCREEN",
                                    "Simulated Posture & Eye Rest Check blink screen triggered."
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).testTag("simulate_blink_health"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = "Eye Icon", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Health Break", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        
                        Button(
                            onClick = {
                                viewModel.triggerBlink(
                                    title = "Corporate Guideline Review",
                                    message = "Enterprise Policy Reminder: All active tasks must strictly conform to administrative security guidelines."
                                )
                                viewModel.addManualAuditLog(
                                    "BLINK_SCREEN",
                                    "Simulated Corporate Guideline Review blink screen triggered."
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.weight(1f).testTag("simulate_blink_corp"),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Security, contentDescription = "Security Icon", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Corp Notice", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                // SCHEDULES VIEW
                Text(
                    text = "Access Protection Schedule Rules",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (schedules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = "No rules",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No schedules defined",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Create schedule windows for apps or categories to automatically lock access during designated times.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(schedules) { schedule ->
                            ScheduleItem(
                                schedule = schedule,
                                onToggle = { isEnabled ->
                                    viewModel.addOrUpdateSchedule(schedule.copy(isEnabled = isEnabled))
                                },
                                onDelete = {
                                    viewModel.deleteSchedule(schedule)
                                }
                            )
                        }
                    }
                }
            } else if (selectedTab == 1) {
                // EXPERIMENTAL MDM CONTROL TABLES TAB
                Text(
                    text = "⚠️ EXPERIMENTAL MDM CONTROL TABLES",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        MdmDeviceQuickControls(viewModel)
                    }

                    item {
                        MdmRemoteScreenSimulator(viewModel)
                    }

                    item {
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "✍️ INJECT EMM SYSTEM POLICY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        // Injection form
                        MdmPolicyInjectionForm(
                            onInject = { key, value, type, flag ->
                                viewModel.addOrUpdatePolicy(
                                    MdmPolicyEntity(key, value, type, flag)
                                )
                            }
                        )
                    }

                    item {
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "📋 Active EMM Database Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    if (policies.isEmpty()) {
                        item {
                            Text(
                                text = "No active EMM policies found. Reseed policies in ViewModel.",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        items(policies) { policy ->
                            MdmPolicyRow(
                                policy = policy,
                                onUpdateValue = { newVal ->
                                    viewModel.addOrUpdatePolicy(policy.copy(value = newVal))
                                },
                                onUpdateFlag = { newFlag ->
                                    viewModel.addOrUpdatePolicy(policy.copy(permissionFlag = newFlag))
                                },
                                onDelete = {
                                    viewModel.deletePolicy(policy)
                                }
                            )
                        }
                    }

                    item {
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        MdmKidUserModeSimulator(policies, viewModel)
                    }
                }
            } else {
                // AUDIT LOGS VIEW
                AuditLogsView(viewModel)
            }
        }
    }

    if (showAddDialog) {
        AddScheduleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { schedule ->
                viewModel.addOrUpdateSchedule(schedule)
                showAddDialog = false
            }
        )
    }
}

/**
 * Renders a row within the Experimental MDM Control Tables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdmPolicyRow(
    policy: MdmPolicyEntity,
    onUpdateValue: (String) -> Unit,
    onUpdateFlag: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("policy_row_${policy.key}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = policy.key,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("type: ${policy.valueType}") }
                        )
                        SuggestionChip(
                            onClick = {},
                            label = { Text(policy.permissionFlag) },
                            colors = ChipColors(
                                containerColor = if (policy.permissionFlag == "GREY_OUT") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                labelColor = if (policy.permissionFlag == "GREY_OUT") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                leadingIconContentColor = Color.Transparent,
                                trailingIconContentColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                disabledLabelColor = Color.Transparent,
                                disabledLeadingIconContentColor = Color.Transparent,
                                disabledTrailingIconContentColor = Color.Transparent
                            )
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Delete Policy",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Render selector/input based on policy valueType
            if (policy.valueType == "boolean") {
                val checked = policy.value.lowercase() == "true"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Boolean Enforcement",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { onUpdateValue(it.toString()) },
                        modifier = Modifier.testTag("policy_toggle_${policy.key}")
                    )
                }
            } else {
                OutlinedTextField(
                    value = policy.value,
                    onValueChange = { onUpdateValue(it) },
                    label = { Text("Enforced Value") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("policy_input_${policy.key}")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enforce On Kid Mode:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = policy.permissionFlag == "GREY_OUT",
                        onClick = { onUpdateFlag("GREY_OUT") },
                        label = { Text("🔒 Grey Out") }
                    )
                    FilterChip(
                        selected = policy.permissionFlag == "PERMIT_KID",
                        onClick = { onUpdateFlag("PERMIT_KID") },
                        label = { Text("🔓 Allow user change") }
                    )
                }
            }
        }
    }
}

/**
 * Injection form enabling custom MDM parameters.
 */
@Composable
fun MdmPolicyInjectionForm(
    onInject: (String, String, String, String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("boolean") } // "boolean", "int", "string"
    var flag by remember { mutableStateOf("GREY_OUT") } // "GREY_OUT", "PERMIT_KID"

    var expandedType by remember { mutableStateOf(false) }
    var expandedFlag by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Inject Custom MDM Parameter",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Policy Key") },
                placeholder = { Text("e.g. install_non_market_apps") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inject_key_field")
            )

            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Default Value") },
                placeholder = { Text("e.g. false, 12, SecureMode") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inject_value_field")
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expandedType = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Type: $type")
                    }
                    DropdownMenu(
                        expanded = expandedType,
                        onDismissRequest = { expandedType = false }
                    ) {
                        listOf("boolean", "int", "string").forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = {
                                    type = it
                                    expandedType = false
                                }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expandedFlag = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Flag: $flag")
                    }
                    DropdownMenu(
                        expanded = expandedFlag,
                        onDismissRequest = { expandedFlag = false }
                    ) {
                        listOf("GREY_OUT", "PERMIT_KID").forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = {
                                    flag = it
                                    expandedFlag = false
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (key.isNotBlank() && value.isNotBlank()) {
                        onInject(key.trim(), value.trim(), type, flag)
                        key = ""
                        value = ""
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("inject_submit_button"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(imageVector = Icons.Default.Publish, contentDescription = "Inject")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Inject Custom Policy")
            }
        }
    }
}

@Composable
fun ScheduleItem(
    schedule: ScheduleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_item_${schedule.targetName.replace(" ", "_").lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (schedule.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (schedule.isCategory) Icons.Default.Category else Icons.Default.Android,
                        contentDescription = "Target type icon",
                        tint = if (schedule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = schedule.targetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (schedule.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = if (schedule.isCategory) "Category Limit" else "Specific Application",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = schedule.isEnabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.testTag("toggle_${schedule.targetName.lowercase()}")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_${schedule.targetName.lowercase()}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Rule",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Schedule Time Icon",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val startTimeFormatted = String.format("%02d:%02d", schedule.startHour, schedule.startMinute)
                    val endTimeFormatted = String.format("%02d:%02d", schedule.endHour, schedule.endMinute)
                    Text(
                        text = "Allowed window: $startTimeFormatted - $endTimeFormatted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = "Days Active Icon",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Days: ${schedule.daysOfWeek}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScheduleEntity) -> Unit
) {
    var targetName by remember { mutableStateOf("") }
    var isCategory by remember { mutableStateOf(false) }
    var startHourStr by remember { mutableStateOf("09") }
    var startMinStr by remember { mutableStateOf("00") }
    var endHourStr by remember { mutableStateOf("21") }
    var endMinStr by remember { mutableStateOf("00") }

    val daysList = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selectedDays = remember { mutableStateMapOf<String, Boolean>().apply { daysList.forEach { put(it, true) } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add Access Schedule") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = targetName,
                    onValueChange = { targetName = it },
                    label = { Text("App Name or Category") },
                    placeholder = { Text("e.g. YouTube, Games, TikTok") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_target_name")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isCategory,
                        onCheckedChange = { isCategory = it },
                        modifier = Modifier.testTag("dialog_is_category")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "This is a Category Limit (not specific App)")
                }

                Text(
                    text = "Time Window (Allowed Access)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startHourStr,
                        onValueChange = { if (it.length <= 2) startHourStr = it },
                        label = { Text("Start Hour") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_start_hour")
                    )
                    OutlinedTextField(
                        value = startMinStr,
                        onValueChange = { if (it.length <= 2) startMinStr = it },
                        label = { Text("Start Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_start_min")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = endHourStr,
                        onValueChange = { if (it.length <= 2) endHourStr = it },
                        label = { Text("End Hour") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_end_hour")
                    )
                    OutlinedTextField(
                        value = endMinStr,
                        onValueChange = { if (it.length <= 2) endMinStr = it },
                        label = { Text("End Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_end_min")
                    )
                }

                Text(
                    text = "Schedules Days Active",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 4,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    daysList.forEach { day ->
                        FilterChip(
                            selected = selectedDays[day] == true,
                            onClick = { selectedDays[day] = !(selectedDays[day] ?: false) },
                            label = { Text(day) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (targetName.isNotBlank()) {
                        val activeDays = selectedDays.filter { it.value }.keys.joinToString(",")
                        val daysString = if (activeDays.split(",").size == 7) "Daily" else activeDays.ifEmpty { "Daily" }

                        onConfirm(
                            ScheduleEntity(
                                targetName = targetName.trim(),
                                isCategory = isCategory,
                                startHour = startHourStr.toIntOrNull() ?: 9,
                                startMinute = startMinStr.toIntOrNull() ?: 0,
                                endHour = endHourStr.toIntOrNull() ?: 21,
                                endMinute = endMinStr.toIntOrNull() ?: 0,
                                daysOfWeek = daysString,
                                isEnabled = true
                            )
                        )
                    }
                },
                modifier = Modifier.testTag("dialog_confirm")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_dismiss")
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AuditLogsView(
    viewModel: MainViewModel
) {
    val auditLogs by viewModel.auditLogs.collectAsState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Device Policy Enforcement Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = { viewModel.addManualAuditLog("MANUAL_EVENT", "Parent triggered manual verification event check.") },
                    modifier = Modifier.testTag("add_manual_log_button")
                ) {
                    Icon(imageVector = Icons.Default.AddComment, contentDescription = "Add Test Log", tint = MaterialTheme.colorScheme.secondary)
                }
                IconButton(
                    onClick = { viewModel.clearAuditLogs() },
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear All Logs", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        
        if (auditLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClearAll,
                        contentDescription = "No logs",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Audit Log is Clean",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "No policy enforcement actions, access blocks, or anti-tamper events have occurred yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(auditLogs.sortedByDescending { log -> log.timestamp }) { log ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audit_log_item_${log.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = when (log.eventType) {
                                "ACCESS_BLOCKED" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                "HARDWARE_ROLLBACK" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                "ADMIN_SECURITY_ALERT" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                "ADMIN_RECEIVER" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                "POLICY_ENFORCEMENT" -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when (log.eventType) {
                                            "ACCESS_BLOCKED" -> Icons.Default.Block
                                            "HARDWARE_ROLLBACK" -> Icons.Default.WifiTetheringError
                                            "ADMIN_SECURITY_ALERT" -> Icons.Default.NewReleases
                                            "ADMIN_SECURITY" -> Icons.Default.VpnKey
                                            "ADMIN_RECEIVER" -> Icons.Default.AdminPanelSettings
                                            "POLICY_ENFORCEMENT" -> Icons.Default.Security
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = "Log Type Icon",
                                        modifier = Modifier.size(16.dp),
                                        tint = when (log.eventType) {
                                            "ACCESS_BLOCKED", "HARDWARE_ROLLBACK", "ADMIN_SECURITY_ALERT" -> MaterialTheme.colorScheme.error
                                            "ADMIN_RECEIVER" -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = log.eventType,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = when (log.eventType) {
                                            "ACCESS_BLOCKED", "HARDWARE_ROLLBACK", "ADMIN_SECURITY_ALERT" -> MaterialTheme.colorScheme.error
                                            "ADMIN_RECEIVER" -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                }
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (log.isSynced) {
                                        Icon(
                                            imageVector = Icons.Default.CloudDone,
                                            contentDescription = "Synced to Cloud Dashboard",
                                            modifier = Modifier.size(14.dp),
                                            tint = Color(0xFF4CAF50)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Synced",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF4CAF50)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = "Pending Cloud Sync",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Local",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                            
                            Text(
                                text = log.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Text(
                                text = dateFormat.format(Date(log.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MockAndroidHomeScreen() {
    val dateStr = remember {
        val sdf = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        sdf.format(Date())
    }
    val timeStr = remember {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        sdf.format(Date())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF141E30), // Deep blue
                        Color(0xFF243B55), // Steel blue
                        Color(0xFF0F2027)  // Dark carbon slate
                    )
                )
            )
    ) {
        // Soft glowing light orb in the background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x22EF5350), Color.Transparent),
                        radius = 1000f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOP SYSTEM BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeStr.split(" ")[0], // Just the time digits
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📶 5G",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "🔋 88%",
                        color = Color(0xFF81C784),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. LARGE CLOCK AND DATE WIDGET (Centered near top)
            Column(
                modifier = Modifier.padding(top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = timeStr.split(" ")[0],
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.W300,
                        letterSpacing = (-1).sp
                    ),
                    color = Color.White.copy(alpha = 0.95f)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. MAIN APP GRID (2 Rows of 4 Apps)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val appRows = listOf(
                    listOf(
                        Triple(Icons.Default.Phone, "Phone", Color(0xFF2E7D32)),
                        Triple(Icons.Default.Email, "Messages", Color(0xFF1565C0)),
                        Triple(Icons.Default.Search, "Browser", Color(0xFFF2A600)),
                        Triple(Icons.Default.PlayArrow, "Media", Color(0xFFC62828))
                    ),
                    listOf(
                        Triple(Icons.Default.Person, "Contacts", Color(0xFF00838F)),
                        Triple(Icons.Default.Settings, "Settings", Color(0xFF37474F)),
                        Triple(Icons.Default.Star, "Favorites", Color(0xFFEF6C00)),
                        Triple(Icons.Default.Notifications, "Alerts", Color(0xFF6A1B9A))
                    )
                )

                appRows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { (icon, name, color) ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(72.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    color.copy(alpha = 0.85f),
                                                    color
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = Color.White.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = name,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = name,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // 4. BOTTOM PERSISTENT DOCK CONTAINER
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
                border = BorderStroke(1.dp, Color(0x0AFFFFFF))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dockApps = listOf(
                        Triple(Icons.Default.Phone, "Phone", Color(0xFF2E7D32)),
                        Triple(Icons.Default.Email, "Messages", Color(0xFF1565C0)),
                        Triple(Icons.Default.Search, "Browser", Color(0xFFF2A600)),
                        Triple(Icons.Default.Settings, "Settings", Color(0xFF37474F))
                    )
                    dockApps.forEach { (icon, name, color) ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = name,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MdmDeviceQuickControls(viewModel: MainViewModel) {
    val isTorchOn by viewModel.isTorchOn.collectAsState()
    val isTorchBlinking by viewModel.isTorchBlinking.collectAsState()
    val isBluetoothOn by viewModel.isBluetoothOn.collectAsState()
    val isWifiOn by viewModel.isWifiOn.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "⚡ MDM DEVICE QUICK CONTROLS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Administrators can run hardware commands instantly on the child/employee device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // Row 1: Torch & Wifi
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Torch", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = isTorchOn && !isTorchBlinking,
                            onCheckedChange = { viewModel.toggleTorch(it) }
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Torch Blink", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = isTorchBlinking,
                            onCheckedChange = { viewModel.toggleTorchBlinking(it) }
                        )
                    }
                }
            }

            // Row 2: Bluetooth & Wifi
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bluetooth", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = isBluetoothOn,
                            onCheckedChange = { viewModel.toggleBluetooth(it) }
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Wi-Fi", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = isWifiOn,
                            onCheckedChange = { viewModel.toggleWifi(it) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // Navigation Actions: Go Home / Go Back
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.triggerGoHome() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Go Home", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { viewModel.triggerGoBack() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Go Back", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun MdmRemoteScreenSimulator(viewModel: MainViewModel) {
    val screenShareActive by viewModel.screenShareActive.collectAsState()
    val screenControlActive by viewModel.screenControlActive.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "🖥️ REMOTE SCREEN SUPPORT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "Initiate real-time screen share or remote screen control to help employees and kids troubleshoot device issues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Screen Share Toggle Button
                Button(
                    onClick = { viewModel.toggleScreenShare(!screenShareActive) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (screenShareActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (screenShareActive) Icons.Default.CancelPresentation else Icons.Default.ScreenShare,
                        contentDescription = "Screen Share"
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (screenShareActive) "Stop Share" else "Screen Share",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Screen Control Toggle Button
                Button(
                    onClick = { viewModel.toggleScreenControl(!screenControlActive) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (screenControlActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (screenControlActive) Icons.Default.CancelPresentation else Icons.Default.SettingsRemote,
                        contentDescription = "Screen Control"
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (screenControlActive) "Stop Control" else "Remote Control",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Visual Simulation Frame
            AnimatedVisibility(visible = screenShareActive || screenControlActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color(0xFFF44336), RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Simulated device view
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.Red)
                            )
                            Text(
                                text = if (screenControlActive) "LIVE REMOTE DEVICE CONTROL ACTIVE" else "LIVE REMOTE STREAMING ACTIVE",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Admin Node: dev_console_v2",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            text = if (screenControlActive) "🕹️ Dispatched administrative drag & click gesture packets" else "🎥 Streaming compressed VP8 frame buffers to administrative database",
                            color = MaterialTheme.colorScheme.primaryContainer,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MdmKidUserModeSimulator(
    policies: List<MdmPolicyEntity>,
    viewModel: MainViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "👶 KID / EMPLOYEE SETTINGS PANELS (SIMULATION)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "Below is how the settings appear to the user. Greyed-out policies cannot be modified. Allowed policies can be modified locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            if (policies.isEmpty()) {
                Text(
                    text = "No active policies to simulate.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                policies.forEach { policy ->
                    val isGreyedOut = policy.permissionFlag == "GREY_OUT"
                    val checked = policy.value.lowercase() == "true"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isGreyedOut) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isGreyedOut) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock State",
                                    tint = if (isGreyedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = policy.key,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isGreyedOut) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (isGreyedOut) "🔒 Locked by Admin" else "🔓 Allowed to change back",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isGreyedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }

                        if (policy.valueType == "boolean") {
                            Switch(
                                checked = checked,
                                onCheckedChange = { newVal ->
                                    if (!isGreyedOut) {
                                        viewModel.addOrUpdatePolicy(policy.copy(value = newVal.toString()))
                                        viewModel.addManualAuditLog(
                                            "KID_MODIFICATION",
                                            "Kid modified allowed policy '${policy.key}' to $newVal"
                                        )
                                    }
                                },
                                enabled = !isGreyedOut
                            )
                        } else {
                            OutlinedTextField(
                                value = policy.value,
                                onValueChange = { newVal ->
                                    if (!isGreyedOut) {
                                        viewModel.addOrUpdatePolicy(policy.copy(value = newVal))
                                        viewModel.addManualAuditLog(
                                            "KID_MODIFICATION",
                                            "Kid modified allowed policy '${policy.key}' to $newVal"
                                        )
                                    }
                                },
                                enabled = !isGreyedOut,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(48.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
