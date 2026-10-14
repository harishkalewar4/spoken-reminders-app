package com.example.ttsreminder

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {

private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* Handled gracefully */ }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    setContent {
        SpokenRemindersTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainAppRoot()
            }
        }
    }
}


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppRoot() {
val context = LocalContext.current
val prefs = remember { context.getSharedPreferences("tts_reminders_prefs", Context.MODE_PRIVATE) }
var setupDone by remember { mutableStateOf(prefs.getBoolean("setup_done", false)) }

if (!setupDone) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Initial Setup",
                        modifier = Modifier.semantics { heading() }
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            SetupContent(
                onFinishedOrSkipped = {
                    prefs.edit().putBoolean("setup_done", true).apply()
                    setupDone = true
                },
                showContinueButton = true
            )
        }
    }
} else {
    ReminderAppScreen()
}


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderAppScreen() {
val context = LocalContext.current
val localView = LocalView.current
val scope = rememberCoroutineScope()
val snackbarHostState = remember { SnackbarHostState() }

var selectedTab by remember { mutableIntStateOf(0) } // 0: Reminders, 1: Timers

var reminders by remember { mutableStateOf(ReminderRepository.getReminders(context)) }
var timers by remember { mutableStateOf(TimerRepository.getTimers(context)) }

var showAddReminderSheet by remember { mutableStateOf(false) }
var showAddTimerSheet by remember { mutableStateOf(false) }
var showSettingsSheet by remember { mutableStateOf(false) }

var missingPermissions by remember { mutableStateOf(false) }

fun checkPermissionsLive() {
    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val hasAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else true

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val hasBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else true

    missingPermissions = !hasNotif || !hasAlarm || !hasBattery
}

LifecycleResumeEffect(Unit) {
    checkPermissionsLive()
    reminders = ReminderRepository.getReminders(context)
    timers = TimerRepository.getTimers(context)
    onPauseOrDispose {}
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
            },
            actions = {
                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Settings and setup"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                }
            }
        )
    },
    floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = {
                if (selectedTab == 0) showAddReminderSheet = true else showAddTimerSheet = true
            },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(if (selectedTab == 0) "Add reminder" else "Start timer") },
            modifier = Modifier.semantics {
                contentDescription = if (selectedTab == 0) "Add reminder, button" else "Start timer, button"
            }
        )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) }
) { paddingValues ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (missingPermissions) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Finish setup so alerts work when your phone is locked.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        TextButton(onClick = { showSettingsSheet = true }) {
                            Text("Open Setup")
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Reminders (${reminders.size})") },
                    modifier = Modifier.semantics { contentDescription = "Reminders tab, ${reminders.size} items" }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Timers (${timers.size})") },
                    modifier = Modifier.semantics { contentDescription = "Timers tab, ${timers.size} running" }
                )
            }

            when (selectedTab) {
                0 -> {
                    if (reminders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.extraLarge,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(96.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsNone,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Text(
                                    text = "No reminders yet",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.semantics { heading() }
                                )
                                Text(
                                    text = "Add your first reminder to get started with spoken alerts.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { showAddReminderSheet = true },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Add your first reminder, button" }
                                ) {
                                    Text("Add your first reminder")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(
                                items = reminders,
                                key = { it.id }
                            ) { reminder ->
                                ReminderCard(
                                    reminder = reminder,
                                    onDelete = {
                                        val deletedItem = reminder
                                        ReminderRepository.deleteReminder(context, reminder.id)
                                        reminders = ReminderRepository.getReminders(context)
                                        localView.announceForAccessibility("Deleted ${deletedItem.title} reminder")

                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Deleted \"${deletedItem.title}\"",
                                                actionLabel = "Undo",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                ReminderRepository.addReminder(context, deletedItem)
                                                reminders = ReminderRepository.getReminders(context)
                                                localView.announceForAccessibility("Restored ${deletedItem.title} reminder")
                                            }
                                        }
                                    },
                                    onSpeakNow = {
                                        triggerTtsSpeechNow(reminder.title, reminder.category)
                                        localView.announceForAccessibility("Speaking ${reminder.title}")
                                    }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    if (timers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.extraLarge,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(96.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                                Text(
                                    text = "No active timers",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.semantics { heading() }
                                )
                                Text(
                                    text = "Start a countdown timer to get a spoken alert when it finishes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { showAddTimerSheet = true },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = "Start timer, button" }
                                ) {
                                    Text("Start timer")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(
                                items = timers,
                                key = { it.id }
                            ) { timerItem ->
                                TimerCard(
                                    timerItem = timerItem,
                                    onCancel = {
                                        TimerRepository.deleteTimer(context, timerItem.id)
                                        timers = TimerRepository.getTimers(context)
                                        localView.announceForAccessibility("Timer cancelled")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

if (showAddReminderSheet) {
    AddReminderBottomSheet(
        onDismiss = { showAddReminderSheet = false },
        onReminderAdded = { newReminder ->
            ReminderRepository.addReminder(context, newReminder)
            reminders = ReminderRepository.getReminders(context)
            showAddReminderSheet = false
            localView.announceForAccessibility("Added reminder ${newReminder.title} for${newReminder.formattedTime()}")
        }
    )
}

if (showAddTimerSheet) {
    AddTimerBottomSheet(
        onDismiss = { showAddTimerSheet = false },
        onTimerStarted = { newTimer ->
            TimerRepository.addTimer(context, newTimer)
            timers = TimerRepository.getTimers(context)
            showAddTimerSheet = false
            localView.announceForAccessibility("Timer started")
        }
    )
}

if (showSettingsSheet) {
    ModalBottomSheet(
        onDismissRequest = { showSettingsSheet = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings & Voice Test",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                OutlinedButton(
                    onClick = {
                        triggerTtsSpeechNow("Test speech alert", "General")
                        localView.announceForAccessibility("Testing voice output")
                    },
                    modifier = Modifier.semantics { contentDescription = "Test voice action" }
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test voice")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                SetupContent(
                    onFinishedOrSkipped = { showSettingsSheet = false },
                    showContinueButton = false
                )
            }
        }
    }
}


}

@Composable
fun ReminderCard(
reminder: Reminder,
onDelete: () -> Unit,
onSpeakNow: () -> Unit
) {
val spokenSummary = "${reminder.category} reminder, 
𝑟
𝑒
𝑚
𝑖
𝑛
𝑑
𝑒
𝑟
.
𝑡
𝑖
𝑡
𝑙
𝑒
,
𝑠
𝑐
ℎ
𝑒
𝑑
𝑢
𝑙
𝑒
𝑑
𝑓
𝑜
𝑟
reminder.title,scheduledfor{reminder.formattedTime()}"

Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ),
    modifier = Modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) {
            contentDescription = spokenSummary
        }
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
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = reminder.category,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onSpeakNow,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Speak now for ${reminder.title}" }
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Delete ${reminder.title} reminder" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text(
            text = reminder.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = reminder.formattedTime(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Scheduled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}


}

@Composable
fun TimerCard(
timerItem: TimerItem,
onCancel: () -> Unit
) {
var remainingMillis by remember { mutableLongStateOf(timerItem.endTimeMillis - System.currentTimeMillis()) }

LaunchedEffect(timerItem.id) {
    while (true) {
        remainingMillis = timerItem.endTimeMillis - System.currentTimeMillis()
        if (remainingMillis <= 0) break
        delay(1000L)
    }
}

val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60

val timeString = if (hours > 0) {
    String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
} else {
    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

val displayText = if (timerItem.label.isNotBlank()) timerItem.label else "Timer"
val spokenSummary = "$displayText timer,$timeString remaining"

Card(
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ),
    modifier = Modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) {
            contentDescription = spokenSummary
        }
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
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "Timer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Cancel timer $displayText" }
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Cancel")
            }
        }

        if (timerItem.label.isNotBlank()) {
            Text(
                text = timerItem.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            text = timeString,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReminderBottomSheet(
onDismiss: () -> Unit,
onReminderAdded: (Reminder) -> Unit
) {
val context = LocalContext.current
val localView = LocalView.current

var reminderTitle by remember { mutableStateOf("") }
val categories = listOf("General", "Medication", "Work", "Personal")
var selectedCategory by remember { mutableStateOf(categories[0]) }

val initialCal = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 5) } }
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

ModalBottomSheet(
    onDismissRequest = onDismiss
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Add New Reminder",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )

        OutlinedTextField(
            value = reminderTitle,
            onValueChange = { reminderTitle = it },
            label = { Text("What should I remind you about?") },
            placeholder = { Text("e.g., Take medication or meeting") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Category",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(4.dp))
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
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (category == selectedCategory),
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

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
                    contentDescription = "Selected time: $formattedSelectedTime. Tap to change time."
                }
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Time: $formattedSelectedTime (Change)")
        }

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
                onReminderAdded(newReminder)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { contentDescription = "Save and schedule reminder, button" }
        ) {
            Text("Save Reminder")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTimerBottomSheet(
onDismiss: () -> Unit,
onTimerStarted: (TimerItem) -> Unit
) {
val localView = LocalView.current

var timerLabel by remember { mutableStateOf("") }
var minutesInput by remember { mutableIntStateOf(5) }
var secondsInput by remember { mutableIntStateOf(0) }

val presets = listOf(1, 5, 10, 15, 30)

ModalBottomSheet(
    onDismissRequest = onDismiss
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Start Countdown Timer",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )

        OutlinedTextField(
            value = timerLabel,
            onValueChange = { timerLabel = it },
            label = { Text("Timer label (optional)") },
            placeholder = { Text("e.g., Eggs boiled or Laundry") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Quick Presets",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { presetMins ->
                FilterChip(
                    selected = (minutesInput == presetMins && secondsInput == 0),
                    onClick = {
                        minutesInput = presetMins
                        secondsInput = 0
                        localView.announceForAccessibility("$presetMins minutes preset selected")
                    },
                    label = { Text("$presetMins min") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Minutes", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (minutesInput > 0) minutesInput-- },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Decrease minutes" }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    Text(
                        text = "$minutesInput",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { minutesInput++ },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Increase minutes" }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Seconds", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (secondsInput >= 10) secondsInput -= 10 else secondsInput = 0 },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Decrease seconds by 10" }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                    }
                    Text(
                        text = String.format(Locale.getDefault(), "%02d", secondsInput),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { if (secondsInput <= 50) secondsInput += 10 else secondsInput = 59 },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Increase seconds by 10" }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            }
        }

        Button(
            onClick = {
                val totalDurationMillis = (minutesInput * 60L + secondsInput) * 1000L
                if (totalDurationMillis <= 0L) {
                    localView.announceForAccessibility("Please set a duration greater than zero.")
                    return@Button
                }
                val endTime = System.currentTimeMillis() + totalDurationMillis
                val newTimer = TimerItem(
                    id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    label = timerLabel.trim(),
                    durationMillis = totalDurationMillis,
                    endTimeMillis = endTime
                )
                onTimerStarted(newTimer)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics { contentDescription = "Start timer button" }
        ) {
            Text("Start Timer")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}


}
