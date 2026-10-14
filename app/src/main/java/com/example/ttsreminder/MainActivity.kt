package com.example.ttsreminder

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Calendar
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpokenRemindersTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0: Reminders, 1: Timers
    var showSetupScreen by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddReminderDialog by remember { mutableStateOf(false) }
    var showAddTimerDialog by remember { mutableStateOf(false) }

    // State list for auto-refreshing when SharedPreferences change or polled
    var remindersList by remember { mutableStateOf(ReminderRepository.getReminders(context)) }
    var timersList by remember { mutableStateOf(TimerRepository.getTimers(context)) }

    // Live refresh ticker & SharedPreferences listener
    DisposableEffect(context) {
        val prefsReminders = context.getSharedPreferences("tts_reminders_prefs", Context.MODE_PRIVATE)
        val prefsTimers = context.getSharedPreferences("tts_timers_prefs", Context.MODE_PRIVATE)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            remindersList = ReminderRepository.getReminders(context)
            timersList = TimerRepository.getTimers(context)
        }
        prefsReminders.registerOnSharedPreferenceChangeListener(listener)
        prefsTimers.registerOnSharedPreferenceChangeListener(listener)

        onDispose {
            prefsReminders.unregisterOnSharedPreferenceChangeListener(listener)
            prefsTimers.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Lightweight ticker to update timer countdowns and check list changes on resume
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000L)
            remindersList = ReminderRepository.getReminders(context)
            timersList = TimerRepository.getTimers(context)
        }
    }

    // Snackbar host for undo actions
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spoken Reminders", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.semantics { contentDescription = "Settings" }
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    label = { Text("Reminders") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    label = { Text("Timers") }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedTab == 0) showAddReminderDialog = true
                    else showAddTimerDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = if (selectedTab == 0) "Add Reminder" else "Add Timer"
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (showSetupScreen) {
                SetupContent(
                    onFinishedOrSkipped = { showSetupScreen = false },
                    showContinueButton = true
                )
            } else {
                if (selectedTab == 0) {
                    RemindersScreen(
                        reminders = remindersList,
                        onAddClick = { showAddReminderDialog = true },
                        onDelete = { reminder ->
                            ReminderRepository.deleteReminder(context, reminder.id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Reminder deleted",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    ReminderRepository.addReminder(context, reminder)
                                }
                            }
                        },
                        onToggleRecurring = { reminder, newRecurring ->
                            val updated = reminder.copy(recurring = newRecurring)
                            if (newRecurring) {
                                val nextTime = ReminderScheduler.calculateNextTriggerMillis(
                                    Calendar.getInstance().apply { timeInMillis = updated.triggerTimeMillis }.get(Calendar.HOUR_OF_DAY),
                                    Calendar.getInstance().apply { timeInMillis = updated.triggerTimeMillis }.get(Calendar.MINUTE)
                                )
                                ReminderRepository.updateReminder(context, updated.copy(triggerTimeMillis = nextTime))
                            } else {
                                ReminderRepository.updateReminder(context, updated)
                            }
                        }
                    )
                } else {
                    TimersScreen(
                        timers = timersList,
                        onAddClick = { showAddTimerDialog = true },
                        onDelete = { timer ->
                            TimerRepository.deleteTimer(context, timer.id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Timer deleted",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    TimerRepository.addTimer(context, timer)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onOpenSetup = {
                showSettingsDialog = false
                showSetupScreen = true
            }
        )
    }

    if (showAddReminderDialog) {
        AddReminderDialog(
            onDismiss = { showAddReminderDialog = false },
            onSave = { title, category, triggerTime, recurring ->
                val newReminder = Reminder(
                    id = Random.nextInt(100000, 999999),
                    title = title,
                    category = category,
                    triggerTimeMillis = triggerTime,
                    recurring = recurring
                )
                ReminderRepository.addReminder(context, newReminder)
                showAddReminderDialog = false
            }
        )
    }

    if (showAddTimerDialog) {
        AddTimerDialog(
            onDismiss = { showAddTimerDialog = false },
            onSave = { label, durationMillis ->
                val newTimer = TimerItem(
                    id = Random.nextInt(100000, 999999),
                    label = label,
                    durationMillis = durationMillis,
                    endTimeMillis = System.currentTimeMillis() + durationMillis
                )
                TimerRepository.addTimer(context, newTimer)
                showAddTimerDialog = false
            }
        )
    }
}

@Composable
fun RemindersScreen(
    reminders: List<Reminder>,
    onAddClick: () -> Unit,
    onDelete: (Reminder) -> Unit,
    onToggleRecurring: (Reminder, Boolean) -> Unit
) {
    if (reminders.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AlarmOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "No reminders scheduled",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onAddClick) {
                    Text("Add Reminder")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Scheduled Reminders",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(reminders, key = { it.id }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onDelete = { onDelete(reminder) },
                    onToggleRecurring = { checked -> onToggleRecurring(reminder, checked) }
                )
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun ReminderCard(
    reminder: Reminder,
    onDelete: () -> Unit,
    onToggleRecurring: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Reminder")
                }
            }

            Text(
                text = reminder.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = reminder.formattedTime(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (reminder.recurring) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Daily",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Recurring",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val switchStateDesc = if (reminder.recurring) "Recurring on" else "Recurring off"
                    Switch(
                        checked = reminder.recurring,
                        onCheckedChange = onToggleRecurring,
                        modifier = Modifier.semantics {
                            contentDescription = switchStateDesc
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TimersScreen(
    timers: List<TimerItem>,
    onAddClick: () -> Unit,
    onDelete: (TimerItem) -> Unit
) {
    if (timers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "No active timers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onAddClick) {
                    Text("Add Timer")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Active Timers",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(timers, key = { it.id }) { timer ->
                TimerCard(timer = timer, onDelete = { onDelete(timer) })
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun TimerCard(timer: TimerItem, onDelete: () -> Unit) {
    val remainingMillis = (timer.endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val secondsTotal = remainingMillis / 1000L
    val minutes = secondsTotal / 60L
    val seconds = secondsTotal % 60L
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (timer.label.isNotEmpty()) {
                    Text(
                        text = timer.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (remainingMillis > 0) "Time remaining: $timeFormatted" else "Finished",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Timer")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fn AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, category: String, triggerTimeMillis: Long, recurring: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var recurring by remember { mutableStateOf(false) }

    val calendar = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
    var hour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(calendar.get(Calendar.MINUTE)) }
    var year by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(calendar.get(Calendar.MONTH)) }
    var day by remember { mutableIntStateOf(calendar.get(Calendar.DAY_OF_MONTH)) }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = calendar.timeInMillis
    )
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val timePickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Spoken Reminder") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Reminder Title / Message") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (e.g. Health, Work)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                    val dateStr = datePickerState.selectedDateMillis?.let { sdf.format(java.util.Date(it)) } ?: "Select Date"
                    Text("Date: $dateStr")
                }

                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val amPm = if (timePickerState.hour >= 12) "PM" else "AM"
                    val displayHour = when {
                        timePickerState.hour == 0 -> 12
                        timePickerState.hour > 12 -> timePickerState.hour - 12
                        else -> timePickerState.hour
                    }
                    val displayMin = String.format("%02d", timePickerState.minute)
                    Text("Time: $displayHour:$displayMin $amPm")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = recurring,
                            role = Role.Checkbox,
                            onValueChange = { recurring = it }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = recurring,
                        onCheckedChange = null
                    )
                    Text(
                        text = "Recurring",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedMillis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                    val targetCal = Calendar.getInstance().apply {
                        timeInMillis = selectedMillis
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (targetCal.timeInMillis <= System.currentTimeMillis() && !recurring) {
                        targetCal.add(Calendar.MINUTE, 1)
                    }
                    onSave(
                        title.ifBlank { "Reminder" },
                        category.ifBlank { "General" },
                        targetCal.timeInMillis,
                        recurring
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fn AddTimerDialog(
    onDismiss: () -> Unit,
    onSave: (label: String, durationMillis: Long) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var minutesInput by remember { mutableStateOf("5") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Spoken Timer") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Timer Label (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = minutesInput,
                    onValueChange = { minutesInput = it },
                    label = { Text("Duration in Minutes") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = minutesInput.toLongOrNull() ?: 5L
                    val durationMillis = mins * 60 * 1000L
                    onSave(label, durationMillis)
                }
            ) {
                Text("Start Timer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings & Permissions") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Review permission setup or open system settings for Spoken Reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        onDismiss()
                        onOpenSetup()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-run Setup Wizard")
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open App System Settings")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
