package com.mostafa.smsforwarder.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mostafa.smsforwarder.R
import com.mostafa.smsforwarder.db.AppDatabase
import com.mostafa.smsforwarder.db.SmsLog
import com.mostafa.smsforwarder.sender.WebhookSender
import com.mostafa.smsforwarder.util.AppLogger
import com.mostafa.smsforwarder.util.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs screen — shows all SMS forwarding history with filtering, search, retry, and error logs.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: LogAdapter
    private lateinit var errorAdapter: ErrorLogAdapter
    private lateinit var rvLogs: RecyclerView
    private lateinit var rvErrors: RecyclerView
    private lateinit var tvNoLogs: TextView
    private lateinit var tvNoErrors: TextView
    private lateinit var tvStatAll: TextView
    private lateinit var tvStatSuccess: TextView
    private lateinit var tvStatFailed: TextView
    private lateinit var tvStatFiltered: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var etSearch: EditText
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var frameSmsLogs: android.widget.FrameLayout
    private lateinit var frameErrorLogs: android.widget.FrameLayout

    private var allLogs: List<SmsLog> = emptyList()
    private var currentFilter: String = "ALL"
    private var currentSearch: String = ""
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        db = AppDatabase.getInstance(this)

        initViews()
        setupToolbar()
        setupFilterChips()
        setupSearch()
        setupAdapters()
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
        loadErrorLogs()
    }

    private fun initViews() {
        rvLogs = findViewById(R.id.rv_logs)
        rvErrors = findViewById(R.id.rv_errors)
        tvNoLogs = findViewById(R.id.tv_no_logs)
        tvNoErrors = findViewById(R.id.tv_no_errors)
        tvStatAll = findViewById(R.id.tv_stat_all)
        tvStatSuccess = findViewById(R.id.tv_stat_success)
        tvStatFailed = findViewById(R.id.tv_stat_failed)
        tvStatFiltered = findViewById(R.id.tv_stat_filtered)
        tvStatPending = findViewById(R.id.tv_stat_pending)
        chipGroupFilter = findViewById(R.id.chip_group_filter)
        etSearch = findViewById(R.id.et_search)
        tabLayout = findViewById(R.id.tab_layout)
        frameSmsLogs = findViewById(R.id.frame_sms_logs)
        frameErrorLogs = findViewById(R.id.frame_error_logs)
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

    private fun setupAdapters() {
        adapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = adapter

        errorAdapter = ErrorLogAdapter()
        rvErrors.layoutManager = LinearLayoutManager(this)
        rvErrors.adapter = errorAdapter

        // Tab switching
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { frameSmsLogs.visibility = View.VISIBLE; frameErrorLogs.visibility = View.GONE }
                    1 -> { frameSmsLogs.visibility = View.GONE; frameErrorLogs.visibility = View.VISIBLE; loadErrorLogs() }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupFilterChips() {
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            currentFilter = when (checkedIds.first()) {
                R.id.chip_success -> "SUCCESS"
                R.id.chip_failed -> "FAILED"
                R.id.chip_filtered -> "FILTERED"
                else -> "ALL"
            }
            applyFilters()
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    currentSearch = s?.toString()?.trim() ?: ""
                    applyFilters()
                }
            }
        })
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                db.smsLogDao().getAllOnce()
            }
            allLogs = logs

            val successCount = logs.count { it.forwardStatus == "SUCCESS" }
            val failedCount = logs.count { it.forwardStatus == "FAILED" }
            val filteredCount = logs.count { it.forwardStatus == "FILTERED" }
            val pendingCount = logs.count { it.forwardStatus == "PENDING" }

            tvStatAll.text = logs.size.toString()
            tvStatSuccess.text = successCount.toString()
            tvStatFailed.text = failedCount.toString()
            tvStatFiltered.text = filteredCount.toString()
            tvStatPending.text = pendingCount.toString()

            applyFilters()
        }
    }

    private fun loadErrorLogs() {
        val errors = AppLogger.getErrors()
        if (errors.isEmpty()) {
            tvNoErrors.visibility = View.VISIBLE
            rvErrors.visibility = View.GONE
        } else {
            tvNoErrors.visibility = View.GONE
            rvErrors.visibility = View.VISIBLE
            errorAdapter.submitList(errors.reversed()) // newest first
        }
    }

    private fun applyFilters() {
        var filtered = allLogs

        if (currentFilter != "ALL") {
            filtered = filtered.filter { it.forwardStatus == currentFilter }
        }

        if (currentSearch.isNotBlank()) {
            val query = currentSearch.lowercase()
            filtered = filtered.filter { log ->
                log.sender.lowercase().contains(query) ||
                log.messageBody.lowercase().contains(query) ||
                (log.errorMessage?.lowercase()?.contains(query) == true)
            }
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty()) {
            tvNoLogs.visibility = View.VISIBLE
            rvLogs.visibility = View.GONE
        } else {
            tvNoLogs.visibility = View.GONE
            rvLogs.visibility = View.VISIBLE
        }
    }

    /**
     * Retry a failed SMS — reset to PENDING and trigger the retry worker.
     */
    private fun retryFailedSms(log: SmsLog) {
        MaterialAlertDialogBuilder(this)
            .setTitle("ارسال مجدد")
            .setMessage("آیا می‌خواهید این پیام را دوباره ارسال کنید؟\n\nفرستنده: ${log.sender}\nپیام: ${log.messageBody.take(100)}...")
            .setPositiveButton("ارسال مجدد") { _, _ ->
                lifecycleScope.launch {
                    val settings = SettingsManager(this@LogsActivity)

                    if (!settings.isWebhookConfigured()) {
                        Toast.makeText(this@LogsActivity, "❌ تنظیمات webhook تکمیل نیست", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Reset to PENDING
                    withContext(Dispatchers.IO) {
                        db.smsLogDao().resetForRetry(log.id, System.currentTimeMillis())
                    }

                    // Trigger the retry worker
                    WebhookSender.startRetryWorker(this@LogsActivity)

                    Toast.makeText(this@LogsActivity, "⏳ پیام در صف ارسال قرار گرفت", Toast.LENGTH_SHORT).show()

                    delay(500)
                    loadLogs()
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    // ── SMS Log Adapter ──────────────────────────────────────────

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        private var items: List<SmsLog> = emptyList()
        private val dateFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.getDefault())

        fun submitList(newItems: List<SmsLog>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvStatusIcon: TextView = itemView.findViewById(R.id.tv_status_icon)
            private val tvStatusText: TextView = itemView.findViewById(R.id.tv_status_text)
            private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
            private val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
            private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
            private val tvError: TextView = itemView.findViewById(R.id.tv_error)
            private val tvRetryInfo: TextView = itemView.findViewById(R.id.tv_retry_info)
            private val btnRetry: MaterialButton = itemView.findViewById(R.id.btn_retry)

            @SuppressLint("SetTextI18n")
            fun bind(log: SmsLog) {
                val (icon, text, colorRes) = when (log.forwardStatus) {
                    "SUCCESS" -> Triple("✅", "ارسال شد", R.color.success)
                    "FAILED" -> Triple("❌", "ناموفق", R.color.error)
                    "FILTERED" -> Triple("🔇", "فیلتر شد", R.color.text_hint)
                    "PENDING" -> Triple("⏳", "در انتظار ارسال", R.color.primary)
                    else -> Triple("❓", log.forwardStatus, R.color.text_secondary)
                }

                tvStatusIcon.text = icon
                tvStatusText.text = text
                tvStatusText.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
                tvTime.text = dateFormat.format(Date(log.timestamp))
                tvSender.text = log.sender

                val bodyPreview = if (log.messageBody.length > 200) {
                    log.messageBody.substring(0, 200) + "..."
                } else {
                    log.messageBody
                }
                tvMessage.text = bodyPreview

                // Error
                if (log.errorMessage != null && log.forwardStatus == "FAILED") {
                    tvError.visibility = View.VISIBLE
                    tvError.text = "خطا: ${log.errorMessage}"
                } else {
                    tvError.visibility = View.GONE
                }

                // Retry info
                if (log.retryCount > 0) {
                    tvRetryInfo.visibility = View.VISIBLE
                    if (log.forwardStatus == "PENDING") {
                        tvRetryInfo.text = "تلاش ${log.retryCount}/${log.maxRetries}"
                    } else if (log.forwardStatus == "FAILED") {
                        tvRetryInfo.text = "تلاش ناموفق: ${log.retryCount} بار"
                    } else {
                        tvRetryInfo.visibility = View.GONE
                    }
                } else {
                    tvRetryInfo.visibility = View.GONE
                }

                // Retry button — only for FAILED messages
                if (log.forwardStatus == "FAILED") {
                    btnRetry.visibility = View.VISIBLE
                    btnRetry.setOnClickListener { retryFailedSms(log) }
                } else {
                    btnRetry.visibility = View.GONE
                }

                // Expand/collapse on click
                itemView.setOnClickListener {
                    if (tvMessage.maxLines == 3) {
                        tvMessage.maxLines = Int.MAX_VALUE
                        tvMessage.ellipsize = null
                    } else {
                        tvMessage.maxLines = 3
                        tvMessage.ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                }
            }
        }
    }

    // ── Error Log Adapter ──────────────────────────────────────────

    inner class ErrorLogAdapter : RecyclerView.Adapter<ErrorLogAdapter.ErrorViewHolder>() {

        private var items: List<AppLogger.ErrorEntry> = emptyList()
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun submitList(newItems: List<AppLogger.ErrorEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ErrorViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_error_log, parent, false)
            return ErrorViewHolder(view)
        }

        override fun onBindViewHolder(holder: ErrorViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ErrorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvErrorTime: TextView = itemView.findViewById(R.id.tv_error_time)
            private val tvErrorTag: TextView = itemView.findViewById(R.id.tv_error_tag)
            private val tvErrorMessage: TextView = itemView.findViewById(R.id.tv_error_message)

            fun bind(entry: AppLogger.ErrorEntry) {
                tvErrorTime.text = dateFormat.format(Date(entry.timestamp))
                tvErrorTag.text = entry.tag
                tvErrorMessage.text = entry.message
            }
        }
    }
}
