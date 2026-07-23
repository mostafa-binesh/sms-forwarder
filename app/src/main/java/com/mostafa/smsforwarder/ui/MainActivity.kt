@file:SuppressLint("SetTextI18n")

package com.mostafa.smsforwarder.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mostafa.smsforwarder.R
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.db.SmsLog
import com.mostafa.smsforwarder.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var db: AppDatabase

    // Views — Toggle card
    private lateinit var switchEnabled: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvToggleStatus: TextView
    private lateinit var statusDot: View

    // Views — Status cards
    private lateinit var tvBotStatusIcon: TextView
    private lateinit var tvBotStatusText: TextView
    private lateinit var tvSmsStatusIcon: TextView
    private lateinit var tvSmsStatusText: TextView

    // Views — Stats
    private lateinit var tvStatForwarded: TextView
    private lateinit var tvStatFailed: TextView
    private lateinit var tvStatFiltered: TextView

    // Views — Recent SMS
    private lateinit var tvNoSms: TextView
    private lateinit var llRecentSms: LinearLayout

    // Permission launcher
    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "✅ دسترسی SMS اعطا شد", Toast.LENGTH_SHORT).show()
        } else {
            // Check if permanently denied
            val permanentlyDenied = !shouldShowRequestPermissionRationale(
                android.Manifest.permission.RECEIVE_SMS
            ) && !shouldShowRequestPermissionRationale(
                android.Manifest.permission.READ_SMS
            )
            if (permanentlyDenied) {
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(this, "❌ دسترسی SMS ضروری است", Toast.LENGTH_LONG).show()
            }
        }
        updateDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)
        db = AppDatabase.getInstance(this)

        initViews()
        setupToolbar()
        loadSettings()
        setupListeners()
        observeRecentSms()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateDashboard()
        refreshStats()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun initViews() {
        switchEnabled = findViewById(R.id.switch_enable)
        tvToggleStatus = findViewById(R.id.tv_toggle_status)
        statusDot = findViewById(R.id.status_dot)
        tvBotStatusIcon = findViewById(R.id.tv_bot_status_icon)
        tvBotStatusText = findViewById(R.id.tv_bot_status_text)
        tvSmsStatusIcon = findViewById(R.id.tv_sms_status_icon)
        tvSmsStatusText = findViewById(R.id.tv_sms_status_text)
        tvStatForwarded = findViewById(R.id.tv_stat_forwarded)
        tvStatFailed = findViewById(R.id.tv_stat_failed)
        tvStatFiltered = findViewById(R.id.tv_stat_filtered)
        tvNoSms = findViewById(R.id.tv_no_sms)
        llRecentSms = findViewById(R.id.ll_recent_sms)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun loadSettings() {
        switchEnabled.isChecked = settings.isEnabled
    }

    private fun setupListeners() {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            settings.isEnabled = isChecked
            updateDashboard()
        }
    }

    // ── Permission Handling ─────────────────────────────────────────

    private fun hasSmsPermissions(): Boolean {
        val receiveSms = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readSms = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return receiveSms && readSms
    }

    private fun checkPermissions() {
        if (hasSmsPermissions()) return

        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(android.Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(android.Manifest.permission.READ_SMS)
        }

        // Android 13+ needs POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            // Check if we should show rationale or if permanently denied
            val shouldShowRationale = permissionsNeeded.any { perm ->
                shouldShowRequestPermissionRationale(perm)
            }

            if (shouldShowRationale) {
                showPermissionRationaleDialog(permissionsNeeded)
            } else {
                // First time or permanently denied — just request
                smsPermissionLauncher.launch(permissionsNeeded.toTypedArray())
            }
        }
    }

    private fun showPermissionRationaleDialog(permissions: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_dialog_title)
            .setMessage(R.string.permission_dialog_message)
            .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                smsPermissionLauncher.launch(permissions.toTypedArray())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_dialog_title)
            .setMessage(R.string.permission_dialog_message)
            .setPositiveButton(R.string.btn_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ── Dashboard Updates ───────────────────────────────────────────

    private fun updateDashboard() {
        val isEnabled = settings.isEnabled
        val isConfigured = settings.isTelegramConfigured()
        val hasSms = hasSmsPermissions()

        // Toggle status
        if (isEnabled) {
            tvToggleStatus.text = getString(R.string.status_forwarding_active)
            tvToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
        } else {
            tvToggleStatus.text = getString(R.string.status_forwarding_inactive)
            tvToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            statusDot.setBackgroundResource(R.drawable.bg_status_dot)
        }

        // Bot status
        if (isConfigured) {
            tvBotStatusIcon.text = "✅"
            tvBotStatusText.text = getString(R.string.status_bot_configured)
            tvBotStatusText.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvBotStatusIcon.text = "❌"
            tvBotStatusText.text = getString(R.string.status_bot_not_configured)
            tvBotStatusText.setTextColor(ContextCompat.getColor(this, R.color.error))
        }

        // SMS permission status
        if (hasSms) {
            tvSmsStatusIcon.text = "✅"
            tvSmsStatusText.text = getString(R.string.status_sms_granted)
            tvSmsStatusText.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            tvSmsStatusIcon.text = "❌"
            tvSmsStatusText.text = getString(R.string.status_sms_denied)
            tvSmsStatusText.setTextColor(ContextCompat.getColor(this, R.color.error))
        }
    }

    private fun refreshStats() {
        val dao = db.smsLogDao()
        lifecycleScope.launch {
            val successCount = withContext(Dispatchers.IO) { dao.getSuccessCount() }
            val failedCount = withContext(Dispatchers.IO) { dao.getFailedCount() }
            val filteredCount = withContext(Dispatchers.IO) { dao.getFilteredCount() }

            tvStatForwarded.text = successCount.toString()
            tvStatFailed.text = failedCount.toString()
            tvStatFiltered.text = filteredCount.toString()
        }
    }

    private fun observeRecentSms() {
        val dao = db.smsLogDao()
        lifecycleScope.launch {
            dao.getRecent(4).collectLatest { logs ->
                if (logs.isEmpty()) {
                    tvNoSms.visibility = View.VISIBLE
                    llRecentSms.visibility = View.GONE
                    return@collectLatest
                }

                tvNoSms.visibility = View.GONE
                llRecentSms.visibility = View.VISIBLE
                llRecentSms.removeAllViews()

                val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

                for (log in logs) {
                    val date = dateFormat.format(Date(log.timestamp))
                    val statusEmoji = when (log.forwardStatus) {
                        "SUCCESS" -> "✅"
                        "FAILED" -> "❌"
                        "FILTERED" -> "🔇"
                        else -> "❓"
                    }

                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 6, 0, 6)
                    }

                    val tvStatus = TextView(this@MainActivity).apply {
                        text = statusEmoji
                        textSize = 14f
                    }

                    val tvInfo = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "$date | ${log.sender}"
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        setPadding(8, 0, 0, 0)
                        maxLines = 1
                    }

                    row.addView(tvStatus)
                    row.addView(tvInfo)
                    llRecentSms.addView(row)
                }
            }
        }
    }
}
