package com.example.orderguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.orderguard.ui.theme.OrderMonitorServiceTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var authManager: AuthManager
    private lateinit var sheetsManager: SheetsManager

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authManager.handleSignInResult(result.data)

        val account = com.google.android.gms.auth.api.signin.GoogleSignIn
            .getLastSignedInAccount(this)

        if (account != null) {
            sheetsManager.createSheet(account)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManager(this)
        sheetsManager = SheetsManager(this)
        enableEdgeToEdge()
        checkRequiredPermissions()

        setContent {
            OrderMonitorServiceTheme {
                val userEmail by authManager.userEmail.collectAsState()

                if (userEmail == null) {
                    LoginScreen()
                } else {
                    MainContent(authManager, sheetsManager)
                }
            }
        }
    }

    @Composable
    fun LoginScreen() {
        val scope = rememberCoroutineScope()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Order Guard", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Automate your delivery workflow", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = { 
                    signInLauncher.launch(authManager.getSignInIntent())
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text("Connect with Google", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(authManager: AuthManager, sheetsManager: SheetsManager) {
        val context = LocalContext.current
        val filterPrefs = remember { context.getSharedPreferences("OrderGuardPrefs", MODE_PRIVATE) }
        val statsPrefs = remember { context.getSharedPreferences("OrderStats", MODE_PRIVATE) }
        val historyPrefs = remember { context.getSharedPreferences("OrderHistory", MODE_PRIVATE) }

        var minPayInput by remember { mutableStateOf(filterPrefs.getFloat("MIN_PAY", 5.0f).toString()) }
        var minRatioInput by remember { mutableStateOf(filterPrefs.getFloat("MIN_RATIO", 2.0f).toString()) }
        var selectedDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
        var showAllOrders by remember { mutableStateOf(false) }

        var isMonitoringEnabled by remember { mutableStateOf(filterPrefs.getBoolean("IS_MONITORING", false)) }
        var isServiceRunning by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
        
        var sheetOrders by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
        val scope = rememberCoroutineScope()

        // Check if Sheet permissions are granted
        var hasSheetAccess by remember { mutableStateOf(sheetsManager.hasRequiredPermissions()) }

        // Fetch orders from Google Sheets whenever date changes
        LaunchedEffect(selectedDate, hasSheetAccess) {
            if (hasSheetAccess) {
                sheetOrders = sheetsManager.getOrdersForDate(selectedDate)
            }
        }

        DisposableEffect(Unit) {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val listener = AccessibilityManager.AccessibilityStateChangeListener {
                isServiceRunning = isAccessibilityServiceEnabled(context)
            }
            manager.addAccessibilityStateChangeListener(listener)
            onDispose {
                manager.removeAccessibilityStateChangeListener(listener)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Order Guard", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { authManager.signOut() }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Log Out")
                        }
                    }
                )
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!hasSheetAccess) {
                    PermissionWarningCard(onGrantClick = {
                        signInLauncher.launch(authManager.getSignInIntent())
                    })
                }

                Spacer(modifier = Modifier.height(16.dp))

                // START / STOP Button
                Button(
                    onClick = {
                        isMonitoringEnabled = !isMonitoringEnabled
                        filterPrefs.edit { putBoolean("IS_MONITORING", isMonitoringEnabled) }
                        
                        val status = if (isMonitoringEnabled) "Started" else "Stopped"
                        Toast.makeText(context, "Monitoring $status", Toast.LENGTH_SHORT).show()
                        
                        if (isMonitoringEnabled && !isServiceRunning) {
                            Toast.makeText(context, "Accessibility Service is OFF!", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoringEnabled) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isMonitoringEnabled) "STOP MONITORING" else "START MONITORING",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (!isServiceRunning) {
                    Text(
                        "Accessibility Service is OFF. Tap below to fix.",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Date: $selectedDate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        showDatePicker(context, selectedDate) { newDate ->
                            selectedDate = newDate
                        }
                    }) {
                        Icon(imageVector = Icons.Default.DateRange, contentDescription = "Select Date")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Checkbox(checked = showAllOrders, onCheckedChange = { showAllOrders = it })
                    Text("Show All Detected Orders", style = MaterialTheme.typography.bodyMedium)
                }

                FinancialSummaryCard(selectedDate, statsPrefs, showAllOrders)

                Spacer(modifier = Modifier.height(12.dp))

                Text("App Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AppBreakdownSection(selectedDate, sheetOrders, showAllOrders)

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("System Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Open Accessibility Settings") }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Enable Notification Access") }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        statsPrefs.edit().clear().apply()
                        historyPrefs.edit().clear().apply()
                        Toast.makeText(context, "All Stats & Logs Cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) { Text("Reset All Stats & History") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                Text("Filter Rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = minPayInput, onValueChange = { minPayInput = it }, label = { Text("Min Pay ($)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = minRatioInput, onValueChange = { minRatioInput = it }, label = { Text("Min Ratio ($/mi)") }, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        filterPrefs.edit {
                            putFloat("MIN_PAY", minPayInput.toFloatOrNull() ?: 5f)
                            putFloat("MIN_RATIO", minRatioInput.toFloatOrNull() ?: 2f)
                        }
                        Toast.makeText(context, "Settings Saved!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Save Rules") }

                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }

    @Composable
    fun PermissionWarningCard(onGrantClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Sheets Permissions Missing", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                Text("To sync your orders to Google Sheets, please grant additional account permissions.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGrantClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Grant Permissions")
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.id.contains(context.packageName)) {
                return true
            }
        }
        return false
    }

    private fun showDatePicker(context: Context, currentDate: String, onDateSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(currentDate)
            if (date != null) calendar.time = date
        } catch (_: Exception) {}

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val cal = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                onDateSelected(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    @Composable
    fun FinancialSummaryCard(date: String, prefs: SharedPreferences, showAll: Boolean) {
        val suffix = if (showAll) "all" else "acc"
        val totalPay = prefs.getFloat("${date}_total_pay_$suffix", 0.0f)
        val totalMiles = prefs.getFloat("${date}_total_miles_$suffix", 0.0f)
        val avgRatio = if (totalMiles > 0) totalPay / totalMiles else 0.0f

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                SummaryItem("Total Pay", String.format(Locale.US, "$%.2f", totalPay))
                SummaryItem("Total Miles", String.format(Locale.US, "%.1f mi", totalMiles))
                SummaryItem("Avg Ratio", String.format(Locale.US, "$%.2f/mi", avgRatio))
            }
        }
    }

    @Composable
    fun SummaryItem(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun AppBreakdownSection(date: String, sheetOrders: List<Map<String, String>>, showAll: Boolean) {
        Column {
            if (sheetOrders.isEmpty()) {
                Text("No data for this date in Google Sheets.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            } else {
                sheetOrders.forEach { order ->
                    var expanded by remember { mutableStateOf(false) }
                    val status = order["Status"] ?: ""
                    val isDeclined = status.contains("DECLINED", ignoreCase = true)
                    
                    if (!showAll && isDeclined) return@forEach

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp))
                            .clickable { expanded = !expanded }
                            .padding(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(order["Business Name"] ?: "Order", fontWeight = FontWeight.Bold, color = Color.Black)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = if(isDeclined) Color.Red else Color(0xFF2E7D32))
                        }
                        Text("${order["Time"]} | $${order["Price ($)"]} | ${order["Miles"]}mi | ${order["App"]}", style = MaterialTheme.typography.bodySmall)

                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text("Pickup: ${order["Pick-up Address"]}", style = MaterialTheme.typography.labelSmall)
                                Text("Dropoff: ${order["Drop-off Address"]}", style = MaterialTheme.typography.labelSmall)
                                Text("Est. Time: ${order["Est. Time"]} | Actual: ${order["Actual Time"]}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkRequiredPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
        }
    }
}