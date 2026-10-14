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

@Composable
fun SetupContent(
onFinishedOrSkipped: () -> Unit,
showContinueButton: Boolean
) {
val context = LocalContext.current

var hasNotificationPermission by remember { mutableStateOf(true) }
var hasExactAlarmPermission by remember { mutableStateOf(true) }
var isIgnoringBattery by remember { mutableStateOf(true) }

fun refreshStatus() {
    hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

LifecycleResumeEffect(Unit) {
    refreshStatus()
    onPauseOrDispose {}
}

LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    contentPadding = PaddingValues(bottom = 24.dp)
) {
    item {
        Text(
            text = "Welcome to Spoken Reminders",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "To make sure your reminders and timers speak reliably even when your phone is locked, please complete these 3 quick setup steps:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Step 1: Notifications
    item {
        SetupCard(
            stepNumber = 1,
            title = "Allow Notifications",
            description = "Permits alerts to show an active notification when spoken reminders or timers trigger.",
            isDone = hasNotificationPermission,
            doneText = "Done",
            neededText = "Allow Notifications",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }
            }
        )
    }

    // Step 2: Alarms & Reminders
    item {
        SetupCard(
            stepNumber = 2,
            title = "Allow Alarms & Reminders",
            description = "Grants exact alarm permissions so your reminders and timers fire precisely on time.",
            isDone = hasExactAlarmPermission,
            doneText = "Done",
            neededText = "Allow Alarms",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        )
    }

    // Step 3: Background Activity
    item {
        SetupCard(
            stepNumber = 3,
            title = "Keep App Running in Background",
            description = "Exempts the app from battery optimizations so alerts won't be silenced when the screen is locked.",
            isDone = isIgnoringBattery,
            doneText = "Done",
            neededText = "Disable Battery Restrictions",
            onAction = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )
    }

    item {
        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("Open Phone Settings for this App")
        }
    }

    if (showContinueButton) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onFinishedOrSkipped() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Text(if (hasNotificationPermission && hasExactAlarmPermission && isIgnoringBattery) "Continue to App" else "Skip for Now")
            }
        }
    }
}


}

@Composable
fun SetupCard(
stepNumber: Int,
title: String,
description: String,
isDone: Boolean,
doneText: String,
neededText: String,
onAction: () -> Unit
) {
Card(
colors = CardDefaults.cardColors(
containerColor = if (isDone) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
else MaterialTheme.colorScheme.surfaceVariant
),
modifier = Modifier.fillMaxWidth()
) {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(16.dp),
verticalArrangement = Arrangement.spacedBy(10.dp)
) {
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.SpaceBetween,
verticalAlignment = Alignment.CenterVertically
) {
Text(
text = "Step $stepNumber: $title",
style = MaterialTheme.typography.titleMedium,
fontWeight = FontWeight.Bold,
modifier = Modifier.semantics { heading() }
)
Surface(
shape = MaterialTheme.shapes.small,
color = if (isDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
) {
Row(
modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
verticalAlignment = Alignment.CenterVertically,
horizontalArrangement = Arrangement.spacedBy(4.dp)
) {
Icon(
imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.Error,
contentDescription = null,
tint = if (isDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
modifier = Modifier.size(16.dp)
)
Text(
text = if (isDone) "Done" else "Needed",
style = MaterialTheme.typography.labelMedium,
color = if (isDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
)
}
}
}

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!isDone) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "$neededText, Step $stepNumber" }
            ) {
                Text(neededText)
            }
        }
    }
}


}
