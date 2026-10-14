package com.example.ttsreminder

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Catch up any missed reminders/timers and verify scheduled alarms on app launch
        ReminderReceiver.processCatchUpAndReschedule(applicationContext)
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

    var allPermissionsGranted by remember {
        mutableStateOf(PermissionStatusHelper.areAllCriticalPermissionsGranted(context))
    }
    var showSetupScreen by remember {
        mutableStateOf(
            !ReminderRepository.isSetupCompleted(context) ||
                !PermissionStatusHelper.areAllCriticalPermissionsGranted(context)
        )
    }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddReminderDialog by remember { mutableStateOf(false) }
    var showAddTimerDialog by remember { mutableStateOf(false) }

    var remindersList by remember { mutableStateOf(ReminderRepository.getReminders(context)) }
    var timersList by remember { mutableStateOf(TimerRepository.getTimers(context)) }

    LifecycleResumeEffect(Unit) {
        allPermissionsGranted = PermissionStatusHelper.areAllCriticalPermissionsGranted(context)
        remindersList = ReminderRepository.getReminders(context)
        timersList = TimerRepository.getTimers(context)
        onPauseOrDispose {}
    }

    // Live refresh ticker & SharedPreferences listener
    DisposableEffect(context) {
        val prefsReminders = context.getSharedPreferences(ReminderRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val prefsTimers = context.getSharedPreferences(TimerRepository.PREFS_NAME, Context.MODE_PRIVATE)

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

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000L)
            remindersList = ReminderRepository.getReminders(context)
            timersList = TimerRepository.getTimers(context)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spoken Reminders", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.semantics { contentDescription = "Settings and Permissions" }
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
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
            if (!showSetupScreen) {
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
            }
        },
        floatingActionButton = {
            if (!showSetupScreen) {
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
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!showSetupScreen && !allPermissionsGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Action Required: Background speech permissions are missing. Reminders may not speak when the screen is off.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Button(
                            onClick = { showSetupScreen = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            )
                        ) {
                            Text("Fix Permissions Now")
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (showSetupScreen) {
                    SetupContent(
                        onFinishedOrSkipped = {
                            allPermissionsGranted = PermissionStatusHelper.areAllCriticalPermissionsGranted(context)
                            showSetupScreen = false
                        },
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
                                if (newRecurring && updated.triggerTimeMillis <= System.currentTimeMillis()) {
                                    val cal = Calendar.getInstance().apply { timeInMillis = updated.triggerTimeMillis }
                                    val nextTime = ReminderScheduler.calculateNextTriggerMillis(
                                        cal.get(Calendar.HOUR_OF_DAY),
                                        cal.get(Calendar.MINUTE)
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
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Reminder ${reminder.title}"
                    )
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
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
    val hours = secondsTotal / 3600L
    val minutes = (secondsTotal % 3600L) / 60L
    val seconds = secondsTotal % 60L
    val timeFormatted = if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (timer.label.isNotEmpty()) "Delete Timer ${timer.label}" else "Delete Timer"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, category: String, triggerTimeMillis: Long, recurring: Boolean) -> Unit
) {
    val context = LocalContext.current
    val is24HourFormat = remember(context) { DateFormat.is24HourFormat(context) }

    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var recurring by remember { mutableStateOf(false) }

    val initialCal = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 5) } }
    val initialUtcMillis = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, initialCal.get(Calendar.YEAR))
            set(Calendar.MONTH, initialCal.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, initialCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialUtcMillis
    )
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // Default to keyboard/text input mode (true = TimeInput, false = TimePicker dial)
    var isTextInputMode by remember { mutableStateOf(true) }

    var selectedHour24 by remember { mutableIntStateOf(initialCal.get(Calendar.HOUR_OF_DAY)) }
    var selectedMinute by remember { mutableIntStateOf(initialCal.get(Calendar.MINUTE)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Spoken Reminder") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val dateStr = datePickerState.selectedDateMillis?.let { sdf.format(Date(it)) } ?: "Select Date"
                    Text("Date: $dateStr")
                }

                OutlinedButton(
                    onClick = {
                        isTextInputMode = true
                        showTimePicker = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val timeText = if (is24HourFormat) {
                        String.format(Locale.getDefault(), "%02d:%02d", selectedHour24, selectedMinute)
                    } else {
                        val amPm = if (selectedHour24 >= 12) "PM" else "AM"
                        val displayHour = when {
                            selectedHour24 == 0 -> 12
                            selectedHour24 > 12 -> selectedHour24 - 12
                            else -> selectedHour24
                        }
                        val displayMin = String.format(Locale.getDefault(), "%02d", selectedMinute)
                        "$displayHour:$displayMin $amPm"
                    }
                    Text("Time: $timeText")
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
                        text = "Recurring Daily",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedUtcMillis = datePickerState.selectedDateMillis ?: initialUtcMillis
                    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = selectedUtcMillis
                    }
                    val targetCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                        set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, selectedHour24)
                        set(Calendar.MINUTE, selectedMinute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    val now = System.currentTimeMillis()
                    if (targetCal.timeInMillis <= now) {
                        if (recurring) {
                            while (targetCal.timeInMillis <= now) {
                                targetCal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                        } else {
                            if (now - targetCal.timeInMillis < 60_000L) {
                                targetCal.timeInMillis = now + 15_000L
                            } else {
                                targetCal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                        }
                    }

                    onSave(
                        title.trim().ifBlank { "Reminder" },
                        category.trim().ifBlank { "General" },
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
        val pickerState = rememberTimePickerState(
            initialHour = selectedHour24,
            initialMinute = selectedMinute,
            is24Hour = is24HourFormat
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isTextInputMode) "Enter Time" else "Select Time",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(
                        onClick = { isTextInputMode = !isTextInputMode },
                        modifier = Modifier.semantics {
                            contentDescription = if (isTextInputMode) {
                                "Switch to clock dial"
                            } else {
                                "Switch to text input"
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isTextInputMode) Icons.Default.Schedule else Icons.Default.Keyboard,
                            contentDescription = null
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isTextInputMode) {
                        TimeInput(state = pickerState)
                    } else {
                        TimePicker(state = pickerState)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedHour24 = pickerState.hour.coerceIn(0, 23)
                        selectedMinute = pickerState.minute.coerceIn(0, 59)
                        showTimePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { isTextInputMode = !isTextInputMode }
                    ) {
                        Text(if (isTextInputMode) "Clock Dial" else "Type Time")
                    }
                    TextButton(onClick = { showTimePicker = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTimerDialog(
    onDismiss: () -> Unit,
    onSave: (label: String, durationMillis: Long) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var hoursInput by remember { mutableStateOf("0") }
    var minutesInput by remember { mutableStateOf("5") }
    var secondsInput by remember { mutableStateOf("0") }
    var validationError by remember { mutableStateOf(false) }

    val presets = listOf(
        "1 min" to Triple(0, 1, 0),
        "5 min" to Triple(0, 5, 0),
        "10 min" to Triple(0, 10, 0),
        "15 min" to Triple(0, 15, 0),
        "30 min" to Triple(0, 30, 0),
        "1 hr" to Triple(1, 0, 0)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Spoken Timer") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Timer Label (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = "Enter Duration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hoursInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(2)
                            hoursInput = filtered
                            validationError = false
                        },
                        label = { Text("Hours (0-99)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Hours, 0 to 99" },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(2)
                            minutesInput = filtered
                            validationError = false
                        },
                        label = { Text("Minutes (0-59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Minutes, 0 to 59" },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = secondsInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(2)
                            secondsInput = filtered
                            validationError = false
                        },
                        label = { Text("Seconds (0-59)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Seconds, 0 to 59" },
                        singleLine = true
                    )
                }

                if (validationError) {
                    Text(
                        text = "Please enter a duration greater than 0 seconds (hours 0-99, minutes 0-59, seconds 0-59).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.take(3).forEach { (presetLabel, hms) ->
                            OutlinedButton(
                                onClick = {
                                    hoursInput = hms.first.toString()
                                    minutesInput = hms.second.toString()
                                    secondsInput = hms.third.toString()
                                    validationError = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Set timer to $presetLabel" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(presetLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.drop(3).forEach { (presetLabel, hms) ->
                            OutlinedButton(
                                onClick = {
                                    hoursInput = hms.first.toString()
                                    minutesInput = hms.second.toString()
                                    secondsInput = hms.third.toString()
                                    validationError = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Set timer to $presetLabel" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(presetLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = (hoursInput.trim().toLongOrNull() ?: 0L).coerceIn(0L, 99L)
                    val m = (minutesInput.trim().toLongOrNull() ?: 0L).coerceIn(0L, 59L)
                    val s = (secondsInput.trim().toLongOrNull() ?: 0L).coerceIn(0L, 59L)
                    val totalSeconds = (h * 3600L) + (m * 60L) + s
                    if (totalSeconds <= 0L) {
                        validationError = true
                    } else {
                        onSave(label.trim(), totalSeconds * 1000L)
                    }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
