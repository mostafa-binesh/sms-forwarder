@file:SuppressLint("SetTextI18n")

package com.mostafa.smsforwarder.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mostafa.smsforwarder.R
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.filter.FilterMode
import com.mostafa.smsforwarder.sender.TelegramSender
import com.mostafa.smsforwarder.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingInflatedId")
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var db: AppDatabase

    // Views
    private lateinit var etBotToken: EditText
    private lateinit var etChatId: EditText
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var rgFilterMode: RadioGroup
    private lateinit var tvStatus: TextView
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnSaveConfig: MaterialButton
    private lateinit var chipGroupSenders: ChipGroup
    private lateinit var chipGroupKeywords: ChipGroup
    private lateinit var chipGroupNegative: ChipGroup
    private lateinit var tvNoSenders: TextView
    private lateinit var tvNoKeywords: TextView
    private lateinit var tvNoNegative: TextView
    private lateinit var etAddSender: EditText
    private lateinit var btnAddSender: MaterialButton
    private lateinit var etAddKeyword: EditText
    private lateinit var btnAddKeyword: MaterialButton
    private lateinit var etAddNegative: EditText
    private lateinit var btnAddNegative: MaterialButton
    private lateinit var tvLogs: TextView
    private lateinit var btnClearLogs: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)
        db = AppDatabase.getInstance(this)

        initViews()
        loadSettings()
        setupListeners()
        observeLogs()
    }

    private fun initViews() {
        etBotToken = findViewById(R.id.et_bot_token)
        etChatId = findViewById(R.id.et_chat_id)
        switchEnabled = findViewById(R.id.switch_enable)
        rgFilterMode = findViewById(R.id.rg_filter_mode)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnSaveConfig = findViewById(R.id.btn_save_config)
        chipGroupSenders = findViewById(R.id.chip_group_senders)
        chipGroupKeywords = findViewById(R.id.chip_group_keywords)
        chipGroupNegative = findViewById(R.id.chip_group_negative_keywords)
        tvNoSenders = findViewById(R.id.tv_no_senders)
        tvNoKeywords = findViewById(R.id.tv_no_keywords)
        tvNoNegative = findViewById(R.id.tv_no_negative_keywords)
        etAddSender = findViewById(R.id.et_add_sender)
        btnAddSender = findViewById(R.id.btn_add_sender)
        etAddKeyword = findViewById(R.id.et_add_keyword)
        btnAddKeyword = findViewById(R.id.btn_add_keyword)
        etAddNegative = findViewById(R.id.et_add_negative_keyword)
        btnAddNegative = findViewById(R.id.btn_add_negative_keyword)
        tvLogs = findViewById(R.id.tv_logs)
        btnClearLogs = findViewById(R.id.btn_clear_history)
        tvStatus = findViewById(R.id.tv_status)
    }

    private fun loadSettings() {
        etBotToken.setText(settings.botToken)
        etChatId.setText(settings.chatId)
        switchEnabled.isChecked = settings.isEnabled

        when (settings.filterMode) {
            FilterMode.ALL -> rgFilterMode.check(R.id.rb_filter_all)
            FilterMode.WHITELIST -> rgFilterMode.check(R.id.rb_filter_senders)
            FilterMode.AUTO -> rgFilterMode.check(R.id.rb_filter_keywords)
        }

        refreshChips(chipGroupSenders, tvNoSenders, settings.senderNumbers.toMutableList()) { settings.senderNumbers = it }
        refreshChips(chipGroupKeywords, tvNoKeywords, settings.keywords.toMutableList()) { settings.keywords = it }
        refreshChips(chipGroupNegative, tvNoNegative, settings.negativeKeywords.toMutableList()) { settings.negativeKeywords = it }
        updateStatus()
    }

    private fun setupListeners() {
        btnSaveConfig.setOnClickListener { saveSettings() }
        btnTestConnection.setOnClickListener { testTelegramConnection() }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            settings.isEnabled = isChecked
            updateStatus()
        }

        rgFilterMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_filter_senders -> FilterMode.WHITELIST
                R.id.rb_filter_keywords -> FilterMode.AUTO
                R.id.rb_filter_both -> FilterMode.AUTO
                else -> FilterMode.ALL
            }
            settings.filterMode = mode
            updateStatus()
        }

        btnAddSender.setOnClickListener {
            val text = etAddSender.text.toString().trim()
            if (text.isNotBlank()) {
                val list = settings.senderNumbers.toMutableList()
                if (text !in list) {
                    list.add(text)
                    settings.senderNumbers = list
                    refreshChips(chipGroupSenders, tvNoSenders, list) { settings.senderNumbers = it }
                }
                etAddSender.text.clear()
            }
        }

        btnAddKeyword.setOnClickListener {
            val text = etAddKeyword.text.toString().trim()
            if (text.isNotBlank()) {
                val list = settings.keywords.toMutableList()
                if (text !in list) {
                    list.add(text)
                    settings.keywords = list
                    refreshChips(chipGroupKeywords, tvNoKeywords, list) { settings.keywords = it }
                }
                etAddKeyword.text.clear()
            }
        }

        btnAddNegative.setOnClickListener {
            val text = etAddNegative.text.toString().trim()
            if (text.isNotBlank()) {
                val list = settings.negativeKeywords.toMutableList()
                if (text !in list) {
                    list.add(text)
                    settings.negativeKeywords = list
                    refreshChips(chipGroupNegative, tvNoNegative, list) { settings.negativeKeywords = it }
                }
                etAddNegative.text.clear()
            }
        }

        btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("پاک کردن لاگ‌ها")
                .setMessage("آیا مطمئنید می‌خواهید تمام لاگ‌ها را پاک کنید؟")
                .setPositiveButton("پاک کردن") { _, _ ->
                    lifecycleScope.launch {
                        db.smsLogDao().deleteAll()
                        Toast.makeText(this@MainActivity, "لاگ‌ها پاک شد", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("لغو", null)
                .show()
        }
    }

    private fun saveSettings() {
        settings.botToken = etBotToken.text.toString().trim()
        settings.chatId = etChatId.text.toString().trim()

        val selectedMode = when (rgFilterMode.checkedRadioButtonId) {
            R.id.rb_filter_senders -> FilterMode.WHITELIST
            R.id.rb_filter_keywords -> FilterMode.AUTO
            R.id.rb_filter_both -> FilterMode.AUTO
            else -> FilterMode.ALL
        }
        settings.filterMode = selectedMode

        updateStatus()
        Toast.makeText(this, "ذخیره شد ✅", Toast.LENGTH_SHORT).show()
    }

    private fun testTelegramConnection() {
        val token = etBotToken.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(this, "ابتدا توکن بات را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestConnection.isEnabled = false
        btnTestConnection.text = "در حال تست..."

        lifecycleScope.launch {
            val result = TelegramSender.testBotConnection(token)
            btnTestConnection.isEnabled = true
            btnTestConnection.text = getString(R.string.btn_test_connection)

            result.onSuccess { botInfo ->
                Toast.makeText(this@MainActivity, "✅ متصل شد: $botInfo", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "❌ خطا: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus() {
        val isConfigured = settings.isTelegramConfigured()
        val isEnabled = settings.isEnabled

        val statusText = buildString {
            append("ارسال: ${if (isEnabled) "فعال ✅" else "غیرفعال ❌"}")
            append("\nبات: ${if (isConfigured) "تنظیم شده ✅" else "تنظیم نشده ❌"}")
            append("\nحالت فیلتر: ${settings.filterMode.name}")
            append("\nفرستنده‌ها: ${settings.senderNumbers.size}")
            append("\nکلمات کلیدی: ${settings.keywords.size}")
            append("\nکلمات حذفی: ${settings.negativeKeywords.size}")
        }

        tvStatus.text = statusText
    }

    private fun refreshChips(chipGroup: ChipGroup, emptyText: TextView, items: MutableList<String>, onSave: (List<String>) -> Unit) {
        chipGroup.removeAllViews()
        if (items.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            chipGroup.visibility = View.GONE
            return
        }

        emptyText.visibility = View.GONE
        chipGroup.visibility = View.VISIBLE

        for (item in items) {
            val chip = Chip(this).apply {
                text = item
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    items.remove(item)
                    onSave(items)
                    refreshChips(chipGroup, emptyText, items, onSave)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun observeLogs() {
        val dao = db.smsLogDao()
        lifecycleScope.launch {
            dao.getRecent(20).collectLatest { logs ->
                if (logs.isEmpty()) {
                    tvLogs.text = "هنوز لاگی ثبت نشده."
                    return@collectLatest
                }

                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()

                for (log in logs) {
                    val date = dateFormat.format(Date(log.timestamp))
                    val status = when (log.forwardStatus) {
                        "SUCCESS" -> "✅"
                        "FAILED" -> "❌"
                        "FILTERED" -> "🔇"
                        else -> "❓"
                    }

                    sb.appendLine("$status [$date] ${log.sender}")
                    sb.appendLine("   ${log.messageBody.take(80)}${if (log.messageBody.length > 80) "..." else ""}")
                    if (log.errorMessage != null) {
                        sb.appendLine("   ⚠️ ${log.errorMessage}")
                    }
                    sb.appendLine()
                }

                tvLogs.text = sb.toString()
            }
        }
    }
}
