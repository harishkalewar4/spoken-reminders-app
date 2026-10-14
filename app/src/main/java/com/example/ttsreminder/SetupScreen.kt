package com.example.ttsreminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

object PermissionStatusHelper {
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasExactAlarmPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun areAllCriticalPermissionsGranted(context: Context): Boolean {
        return hasNotificationPermission(context) &&
            hasExactAlarmPermission(context) &&
            isIgnoringBatteryOptimizations(context)
    }
}

@Composable
fun SetupContent(
    onFinishedOrSkipped: () -> Unit,
    showContinueButton: Boolean
) {
    val context = LocalContext.current

    var hasNotificationPermission by remember {
        mutableStateOf(PermissionStatusHelper.hasNotificationPermission(context))
    }
    var hasExactAlarmPermission by remember {
        mutableStateOf(PermissionStatusHelper.hasExactAlarmPermission(context))
    }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(PermissionStatusHelper.isIgnoringBatteryOptimizations(context))
    }

    fun checkPermissions() {
        val prevExact = hasExactAlarmPermission
        hasNotificationPermission = PermissionStatusHelper.hasNotificationPermission(context)
        hasExactAlarmPermission = PermissionStatusHelper.hasExactAlarmPermission(context)
        isIgnoringBatteryOptimizations = PermissionStatusHelper.isIgnoringBatteryOptimizations(context)

        // Reschedule existing reminders & timers whenever permissions are refreshed
        if (!prevExact && hasExactAlarmPermission) {
            ReminderRepository.getReminders(context).forEach { ReminderScheduler.schedule(context, it) }
            TimerRepository.getTimers(context).forEach { TimerScheduler.schedule(context, it) }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        if (!granted) {
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                openAppSettingsFallback(context)
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        checkPermissions()
        onPauseOrDispose {}
    }

    val allGranted = hasNotificationPermission && hasExactAlarmPermission && isIgnoringBatteryOptimizations

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Initial Setup",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "To ensure alarms fire precisely on time and your reminders are spoken aloud reliably while the screen is off, please complete each step below.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Step 1: Battery Optimization
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                item {
                    PermissionCard(
                        title = "Step 1: Background Running (Unrestricted Battery)",
                        description = "Required so the system does not silence speech or block the alarm service when the screen is off.",
                        isGranted = isIgnoringBatteryOptimizations,
                        buttonText = "Disable Battery Restrictions",
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                openAppSettingsFallback(context)
                            }
                        }
                    )
                }
            }

            // Step 2: Exact Alarm Permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    PermissionCard(
                        title = "Step 2: Exact Alarms",
                        description = "Required so your reminders and timers trigger at the exact scheduled second even when the screen is off.",
                        isGranted = hasExactAlarmPermission,
                        buttonText = "Grant Exact Alarm Permission",
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                openAppSettingsFallback(context)
                            }
                        }
                    )
                }
            }

            // Step 3: Notification Permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    PermissionCard(
                        title = "Step 3: Notifications",
                        description = "Required to display ongoing alerts while reminders are spoken.",
                        isGranted = hasNotificationPermission,
                        buttonText = "Grant Notification Permission",
                        onClick = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }

            if (showContinueButton) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            ReminderRepository.setSetupCompleted(context, true)
                            onFinishedOrSkipped()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = if (allGranted) "Finish Setup" else "Continue to Reminders",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

private fun openAppSettingsFallback(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_SETTINGS)
        context.startActivity(intent)
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    val statusText = if (isGranted) "Completed" else "Action Required"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$title, Status: $statusText"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = if (isGranted) "Granted" else "Not Granted",
                    tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isGranted) {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = buttonText)
                }
            } else {
                Text(
                    text = "Permission granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
