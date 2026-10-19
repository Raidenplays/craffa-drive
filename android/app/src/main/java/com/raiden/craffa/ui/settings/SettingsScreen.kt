package com.raiden.craffa.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.raiden.craffa.AppContainer
import com.raiden.craffa.BuildConfig
import com.raiden.craffa.data.api.CraffaApi
import com.raiden.craffa.data.api.OkHttpTransport
import com.raiden.craffa.data.api.ServerConfig
import com.raiden.craffa.data.settings.DEFAULT_BASE_URL
import com.raiden.craffa.io
import com.raiden.craffa.reminders.ReminderScheduler
import kotlinx.coroutines.launch

sealed interface TestResult {
    data object Idle : TestResult
    data object Testing : TestResult
    data class Ok(val version: String, val apiVersion: String?, val compatible: Boolean) : TestResult
    data class Failed(val message: String) : TestResult
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var baseUrl by mutableStateOf(c.settings.config.baseUrl.ifBlank { DEFAULT_BASE_URL })
    var token by mutableStateOf(c.settings.config.token)
    var result by mutableStateOf<TestResult>(TestResult.Idle); private set

    val hasSaved get() = c.settings.config.isConfigured

    /** Test the entered values; save them only if the server accepts the token. */
    fun saveAndTest(onConnected: () -> Unit) {
        val cfg = ServerConfig(baseUrl, token)
        result = TestResult.Testing
        viewModelScope.launch {
            try {
                val probe = CraffaApi({ cfg }, OkHttpTransport(c.okHttp))
                val health = io { probe.health() }
                io { probe.folders() }  // proves the token works
                c.settings.save(baseUrl, token)
                val compatible = health.apiVersion?.substringBefore('.') == CraffaApi.SUPPORTED_API_MAJOR
                result = TestResult.Ok(health.version, health.apiVersion, compatible)
                ReminderScheduler.syncSoon(c)
                onConnected()
            } catch (e: Exception) {
                result = TestResult.Failed(e.message ?: "Couldn't connect")
            }
        }
    }
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onboarding: Boolean,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    var showToken by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Welcome" else "Settings") },
                navigationIcon = {
                    if (!onboarding) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onboarding) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Box(
                        Modifier.size(64.dp).clip(MaterialShapes.Cookie7Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Craffa Drive talks straight to your homelab. Point it at your server and paste the API token it printed on first run.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Text("Server", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = vm.baseUrl,
                onValueChange = { vm.baseUrl = it.trim() },
                label = { Text("Server address") },
                supportingText = { Text("Your Tailscale address, e.g. $DEFAULT_BASE_URL") },
                leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.token,
                onValueChange = { vm.token = it.trim() },
                label = { Text("API token") },
                supportingText = { Text("Printed by the server on first run · saved in data/token.txt") },
                leadingIcon = { Icon(Icons.Outlined.Key, null) },
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showToken) "Hide token" else "Show token")
                    }
                },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveAndTest(onConnected = { if (onboarding) onConnected() }) },
                enabled = vm.baseUrl.isNotBlank() && vm.token.isNotBlank() && vm.result != TestResult.Testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (onboarding) "Connect" else "Save & test connection")
            }
            ConnectionResult(vm.result)

            if (!onboarding) {
                Spacer(Modifier.height(8.dp))
                Text("Reminders", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                ReminderPermissions(context)
                Spacer(Modifier.height(8.dp))
                Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Craffa Drive ${BuildConfig.VERSION_NAME} · notes API ${CraffaApi.SUPPORTED_API_MAJOR}.x",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConnectionResult(result: TestResult) {
    AnimatedVisibility(result != TestResult.Idle) {
        val (container, content) = when (result) {
            is TestResult.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            is TestResult.Ok -> if (result.compatible) {
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            }
            else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                when (result) {
                    TestResult.Testing -> {
                        LoadingIndicator(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Knocking on the server's door…")
                    }
                    is TestResult.Ok -> {
                        Icon(if (result.compatible) Icons.Outlined.CheckCircle else Icons.Outlined.Warning, null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (result.compatible) {
                                "Connected · server ${result.version}, API ${result.apiVersion}"
                            } else {
                                "Connected, but the server speaks API ${result.apiVersion ?: "?"} and this app expects " +
                                    "${CraffaApi.SUPPORTED_API_MAJOR}.x. Some things may not work — update the app."
                            },
                        )
                    }
                    is TestResult.Failed -> {
                        Icon(Icons.Outlined.ErrorOutline, null)
                        Spacer(Modifier.width(12.dp))
                        Text(result.message)
                    }
                    TestResult.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun ReminderPermissions(context: Context) {
    fun notifGranted() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun exactGranted() = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    var notif by remember { mutableStateOf(notifGranted()) }
    var exact by remember { mutableStateOf(exactGranted()) }
    // Re-check when coming back from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notif = notifGranted()
        exact = exactGranted()
    }
    val requestNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }

    ListItem(
        headlineContent = { Text("Notifications") },
        supportingContent = { Text(if (notif) "On — reminders will notify you" else "Off — reminders can't notify you") },
        leadingContent = { Icon(Icons.Outlined.Notifications, null) },
        trailingContent = {
            if (!notif) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Allow") }
        },
    )
    if (Build.VERSION.SDK_INT >= 31) {
        ListItem(
            headlineContent = { Text("On-time reminders") },
            supportingContent = {
                Text(if (exact) "Reminders fire at the exact minute" else "Without this, Android may deliver reminders a few minutes late")
            },
            leadingContent = { Icon(Icons.Outlined.AlarmOn, null) },
            trailingContent = {
                if (!exact) TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Allow") }
            },
        )
    }
}
