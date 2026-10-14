package com.example.ttsreminder

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    ) { /* Permission result handled gracefully */ }

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
                                    onClick = null // Handled by parent Row for a larger touch target
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
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${reminder.category} reminder: ${reminder.title}. Scheduled for ${reminder.formattedTime()}."
                customActions = listOf(
                    CustomAccessibilityAction("Speak reminder aloud now") {
                        onSpeakNow()
                        true
                    },
                    CustomAccessibilityAction("Delete reminder") {
                        onDelete()
                        true
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${reminder.category}: ${reminder.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Scheduled for ${reminder.formattedTime()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSpeakNow,
                    modifier = Modifier.semantics {
                        contentDescription = "Speak ${reminder.title} aloud now"
                    }
                ) {
                    Text("Speak Now")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Delete reminder ${reminder.title}"
                    }
                ) {
                    Text("Delete")
                }
            }
        }
    }
}
