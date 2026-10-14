package com.example.ttsreminder

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ReminderAppScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderAppScreen() {
    val context = LocalContext.current
    val localView = LocalView.current

    var reminders by remember { mutableStateOf(ReminderRepository.getReminders(context)) }
    var reminderTitle by remember { mutableStateOf("") }

    val categories = listOf("General", "Medication", "Work", "Personal")
    var selectedCategory by remember { mutableStateOf(categories[0]) }

    val initialCal = remember {
        Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
    }
    var selectedHour by remember { mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember { mutableIntStateOf(initialCal.get(Calendar.MINUTE)) }

    val formattedSelectedTime = remember(selectedHour, selectedMinute) {
        val amPm = if (selectedHour >= 12) "PM" else "AM"
        val hour12 = when {
            selectedHour == 0 -> 12
            selectedHour > 12 -> selectedHour - 12
            else -> selectedHour
        }
        String.format(Locale.getDefault(), "%02d:%02d %s", hour12, selectedMinute, amPm)
    }

    // Permission status checks
    val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val hasExactAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }

    fun triggerTtsSpeechNow(title: String, category: String) {
        val serviceIntent = Intent(context, TtsAlertService::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_CATEGORY, category)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Spoken Reminders",
                        modifier = Modifier.semantics { heading() }
                    )
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // SETUP CHECKLIST SECTION FOR OXYGENOS / ANDROID 14
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "OxygenOS Setup Checklist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Text(
                            text = "To ensure reminders speak with the screen OFF, verify and fix any missing permissions below:",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // 1. Notifications
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• Notifications: ${if (hasNotificationPermission) "Allowed" else "Missing"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasNotificationPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Button(onClick = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }) {
                                    Text("Fix")
                                }
                            }
                        }

                        // 2. Exact Alarms
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• Exact Alarms: ${if (hasExactAlarmPermission) "Allowed" else "Restricted"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasExactAlarmPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (!hasExactAlarmPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Button(onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }) {
                                    Text("Fix")
                                }
                            }
                        }

                        // 3. Battery Optimization
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• Battery Optimization: ${if (isIgnoringBatteryOptimizations) "Unrestricted" else "Optimized"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isIgnoringBatteryOptimizations) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (!isIgnoringBatteryOptimizations) {
                                Button(onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }) {
                                    Text("Fix")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "OxygenOS Tip: Long-press app icon -> App info -> Battery -> Set to 'Unrestricted' and enable 'Allow background activity' / 'Auto-launch'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open App Settings")
                        }
                    }
                }
            }

            // SECTION 1: Create New Reminder
            item {
                Text(
                    text = "Create a New Reminder",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }

            item {
                OutlinedTextField(
                    value = reminderTitle,
                    onValueChange = { reminderTitle = it },
                    label = { Text("What should I remind you about?") },
                    placeholder = { Text("e.g., Drink water or Call mom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Reminder Type (Spoken Before Message)",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        categories.forEach { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = (category == selectedCategory),
                                        onClick = { selectedCategory = category },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (category == selectedCategory),
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "$category Reminder",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                selectedHour = hourOfDay
                                selectedMinute = minute
                                localView.announceForAccessibility("Time set to $formattedSelectedTime")
                            },
                            selectedHour,
                            selectedMinute,
                            false
                        ).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Scheduled Time: $formattedSelectedTime"
                            onClick(label = "Change reminder time", action = null)
                        }
                ) {
                    Text("Scheduled Time: $formattedSelectedTime (Tap to change)")
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val cleanTitle = reminderTitle.trim()
                            if (cleanTitle.isEmpty()) {
                                localView.announceForAccessibility("Please enter what to remind you about first.")
                                return@Button
                            }
                            val triggerMillis = ReminderScheduler.calculateNextTriggerMillis(
                                selectedHour,
                                selectedMinute
                            )
                            val newReminder = Reminder(
                                id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                                title = cleanTitle,
                                category = selectedCategory,
                                triggerTimeMillis = triggerMillis
                            )
                            ReminderRepository.addReminder(context, newReminder)
                            reminders = ReminderRepository.getReminders(context)
                            reminderTitle = ""
                            localView.announceForAccessibility(
                                "Saved $selectedCategory reminder: $cleanTitle for $formattedSelectedTime"
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                    ) {
                        Text("Save Reminder")
                    }

                    OutlinedButton(
                        onClick = {
                            val sampleText = reminderTitle.trim().ifEmpty {
                                "This is a test of your spoken reminder alert."
                            }
                            triggerTtsSpeechNow(sampleText, selectedCategory)
                        },
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .semantics {
                                contentDescription = "Test System Text To Speech Alert Now"
                            }
                    ) {
                        Text("Test TTS")
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Upcoming Spoken Reminders (${reminders.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }

            if (reminders.isEmpty()) {
                item {
                    Text(
                        text = "No upcoming reminders scheduled.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(reminders, key = { it.id }) { reminder ->
                    ReminderItemCard(
                        reminder = reminder,
                        onSpeakNow = {
                            triggerTtsSpeechNow(reminder.title, reminder.category)
                        },
                        onDelete = {
                            ReminderRepository.deleteReminder(context, reminder.id)
                            reminders = ReminderRepository.getReminders(context)
                            localView.announceForAccessibility("Deleted reminder: ${reminder.title}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ReminderItemCard(
    reminder: Reminder,
    onSpeakNow: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = reminder.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = reminder.formattedTime(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSpeakNow,
                    modifier = Modifier.semantics {
                        contentDescription = "Test speak reminder: ${reminder.title}"
                    }
                ) {
                    Text("Speak Now")
                }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "Delete reminder: ${reminder.title}"
                    }
                ) {
                    Text("Delete")
                }
            }
        }
    }
}
