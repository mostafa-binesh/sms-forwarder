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
import com.mostafa.smsforwarder.R
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.filter.FilterMode
import com.mostafa.smsforwarder.sender.WebhookSender
import com.mostafa.smsforwarder.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingInflatedId")
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var db: AppDatabase

    // Views — Webhook Config
    private lateinit var etWebhookUrl: EditText
    private lateinit var etWebhookApiKey: EditText
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnSaveConfig: MaterialButton

    // Views — Filter Mode
    private lateinit var rgFilterMode: RadioGroup

    // Views — Sender Numbers
    private lateinit var chipGroupSenders: ChipGroup
    private lateinit var tvNoSenders: TextView
    private lateinit var etAddSender: EditText
    private lateinit var btnAddSender: MaterialButton

    // Views — Keywords
    private lateinit var chipGroupKeywords: ChipGroup
    private lateinit var tvNoKeywords: TextView
    private lateinit var etAddKeyword: EditText
    private lateinit var btnAddKeyword: MaterialButton

    // Views — Negative Keywords
    private lateinit var chipGroupNegative: ChipGroup
    private lateinit var tvNoNegative: TextView
    private lateinit var etAddNegative: EditText
    private lateinit var btnAddNegative: MaterialButton

    // Views — Other
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var tvAppVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = SettingsManager(this)
        db = AppDatabase.getInstance(this)

        initViews()
        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        etWebhookUrl = findViewById(R.id.et_webhook_url)
        etWebhookApiKey = findViewById(R.id.et_webhook_api_key)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnSaveConfig = findViewById(R.id.btn_save_config)

        rgFilterMode = findViewById(R.id.rg_filter_mode)

        chipGroupSenders = findViewById(R.id.chip_group_senders)
        tvNoSenders = findViewById(R.id.tv_no_senders)
        etAddSender = findViewById(R.id.et_add_sender)
        btnAddSender = findViewById(R.id.btn_add_sender)

        chipGroupKeywords = findViewById(R.id.chip_group_keywords)
        tvNoKeywords = findViewById(R.id.tv_no_keywords)
        etAddKeyword = findViewById(R.id.et_add_keyword)
        btnAddKeyword = findViewById(R.id.btn_add_keyword)

        chipGroupNegative = findViewById(R.id.chip_group_negative_keywords)
        tvNoNegative = findViewById(R.id.tv_no_negative_keywords)
        etAddNegative = findViewById(R.id.et_add_negative_keyword)
        btnAddNegative = findViewById(R.id.btn_add_negative_keyword)

        btnClearLogs = findViewById(R.id.btn_clear_history)
        tvAppVersion = findViewById(R.id.tv_app_version)

        // Set version
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            tvAppVersion.text = "${getString(R.string.app_name)} v${packageInfo.versionName}"
        } catch (e: Exception) {
            tvAppVersion.text = getString(R.string.app_name)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        etWebhookUrl.setText(settings.webhookUrl)
        etWebhookApiKey.setText(settings.webhookApiKey)

        when (settings.filterMode) {
            FilterMode.ALL -> rgFilterMode.check(R.id.rb_filter_all)
            FilterMode.WHITELIST -> rgFilterMode.check(R.id.rb_filter_senders)
            FilterMode.AUTO -> rgFilterMode.check(R.id.rb_filter_keywords)
        }

        refreshChips(chipGroupSenders, tvNoSenders, settings.senderNumbers.toMutableList()) { settings.senderNumbers = it }
        refreshChips(chipGroupKeywords, tvNoKeywords, settings.keywords.toMutableList()) { settings.keywords = it }
        refreshChips(chipGroupNegative, tvNoNegative, settings.negativeKeywords.toMutableList()) { settings.negativeKeywords = it }
    }

    private fun setupListeners() {
        btnSaveConfig.setOnClickListener { saveSettings() }
        btnTestConnection.setOnClickListener { testWebhookConnection() }

        rgFilterMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_filter_senders -> FilterMode.WHITELIST
                R.id.rb_filter_keywords -> FilterMode.AUTO
                R.id.rb_filter_both -> FilterMode.AUTO
                else -> FilterMode.ALL
            }
            settings.filterMode = mode
        }

        btnAddSender.setOnClickListener {
            val text = etAddSender.text.toString().trim()
            if (text.isNotBlank()) {
                val list = settings.senderNumbers.toMutableList()
                if (text !in list) {
                    list.add(text)
                    settings.senderNumbers = list
                    refreshChips(chipGroupSenders, tvNoSenders, list) { settings.senderNumbers = it }
                } else {
                    Toast.makeText(this, R.string.msg_sender_exists, Toast.LENGTH_SHORT).show()
                }
                etAddSender.text.clear()
            } else {
                Toast.makeText(this, R.string.msg_sender_empty, Toast.LENGTH_SHORT).show()
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
                } else {
                    Toast.makeText(this, R.string.msg_keyword_exists, Toast.LENGTH_SHORT).show()
                }
                etAddKeyword.text.clear()
            } else {
                Toast.makeText(this, R.string.msg_keyword_empty, Toast.LENGTH_SHORT).show()
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
                } else {
                    Toast.makeText(this, R.string.msg_negative_keyword_exists, Toast.LENGTH_SHORT).show()
                }
                etAddNegative.text.clear()
            } else {
                Toast.makeText(this, R.string.msg_negative_keyword_empty, Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLogs.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_clear_history)
                .setMessage(R.string.dialog_confirm_delete)
                .setPositiveButton(R.string.btn_yes) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.smsLogDao().deleteAll()
                        }
                        Toast.makeText(this@SettingsActivity, R.string.msg_history_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.btn_no, null)
                .show()
        }
    }

    private fun saveSettings() {
        settings.webhookUrl = etWebhookUrl.text.toString().trim()
        settings.webhookApiKey = etWebhookApiKey.text.toString().trim()

        val selectedMode = when (rgFilterMode.checkedRadioButtonId) {
            R.id.rb_filter_senders -> FilterMode.WHITELIST
            R.id.rb_filter_keywords -> FilterMode.AUTO
            R.id.rb_filter_both -> FilterMode.AUTO
            else -> FilterMode.ALL
        }
        settings.filterMode = selectedMode

        Toast.makeText(this, R.string.msg_config_saved, Toast.LENGTH_SHORT).show()
    }

    private fun testWebhookConnection() {
        val url = etWebhookUrl.text.toString().trim()
        val apiKey = etWebhookApiKey.text.toString().trim()

        if (url.isBlank() || apiKey.isBlank()) {
            Toast.makeText(this, R.string.msg_fill_all_fields, Toast.LENGTH_SHORT).show()
            return
        }

        btnTestConnection.isEnabled = false
        btnTestConnection.text = getString(R.string.loading)

        lifecycleScope.launch {
            val result = WebhookSender.testConnection(url, apiKey)
            btnTestConnection.isEnabled = true
            btnTestConnection.text = getString(R.string.btn_test_connection)

            result.onSuccess { status ->
                Toast.makeText(this@SettingsActivity, "✅ $status", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(this@SettingsActivity, "❌ ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
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
}
