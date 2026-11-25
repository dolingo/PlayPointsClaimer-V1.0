package com.playpoints.manager

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStats: TextView
    private lateinit var etSearch: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AccountAdapter

    private var allAccounts = mutableListOf<AccountItem>()
    private var filteredAccounts = mutableListOf<AccountItem>()
    private var isClaimingAll = false

    private val tickerHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            updateHeaderStats()
            tickerHandler.postDelayed(this, 1000)
        }
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reloadData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.parseColor("#0E1619")

        requestNotificationPermissionIfNeeded()
        buildUi()
        reloadData()
    }

    override fun onResume() {
        super.onResume()
        reloadData()
        tickerHandler.post(tickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        tickerHandler.removeCallbacks(tickerRunnable)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fillHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1619"))
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }

        // البطاقة العلوية (الصلاة على النبي + العداد + زر الإعدادات)
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#162228", 16, "#23353E")
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvBlessing = TextView(this).apply {
            text = "اللهم صل وسلم على نبينا محمد وعلى آله وصحبه أجمعين"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
        }

        tvStats = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }

        headerTextCol.addView(tvBlessing)
        headerTextCol.addView(tvStats)

        val btnSettings = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { showSettingsDialog() }
        }

        headerCard.addView(headerTextCol)
        headerCard.addView(btnSettings)

        // شريط البحث
        etSearch = EditText(this).apply {
            hint = "بحث بالاسم أو البريد أو التصنيف..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = roundedBg("#162228", 14, "#23353E")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    applyFilter()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // قائمة الحسابات
        adapter = AccountAdapter()
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // الشريط السفلي (إضافة حساب + استلام الكل)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        val btnAdd = Button(this).apply {
            text = "+ إضافة حساب"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#082026"))
            background = roundedBg("#28D7B8", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java))
            }
        }

        val btnClaimAll = Button(this).apply {
            text = "استلام الكل"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg("#1B2A32", 14, "#2F4754")
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { confirmClaimAll() }
        }

        bottomBar.addView(btnAdd)
        bottomBar.addView(btnClaimAll)

        root.addView(headerCard)
        root.addView(etSearch)
        root.addView(recyclerView)
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun reloadData() {
        allAccounts = AccountStore.getAccounts(this)
        applyFilter()
        updateHeaderStats()
        MrPlayWidget.updateAll(this)
    }

    private fun applyFilter() {
        val q = etSearch.text?.toString()?.trim()?.lowercase() ?: ""
        filteredAccounts = if (q.isEmpty()) {
            allAccounts.toMutableList()
        } else {
            allAccounts.filter {
                it.name.lowercase().contains(q) ||
                    it.email.lowercase().contains(q) ||
                    it.category.lowercase().contains(q) ||
                    it.note.lowercase().contains(q)
            }.toMutableList()
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateHeaderStats() {
        val claimed = allAccounts.count { it.isClaimedThisWeek() }
        val total = allAccounts.size
        val countdown = AccountStore.countdownText()
        tvStats.text = "$countdown • تم استلام $claimed / $total هذا الأسبوع"
    }

    private fun showSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val swAuto = Switch(this).apply {
            text = "الاستلام التلقائي يوم الجمعة (في الخلفية)"
            isChecked = AccountStore.isAutoClaimEnabled(this@MainActivity)
            setPadding(0, dp(8), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                AccountStore.setAutoClaimEnabled(this@MainActivity, checked)
                AutoClaimWorker.syncSchedule(this@MainActivity, checked)
            }
        }

        val tvPrivacy = TextView(this).apply {
            text = "إخلاء المسؤولية والخصوصية:\nالتطبيق يعمل محلياً بالكامل على جهازك دون أي خادم خارجي، ولا يتم إرسال كلمات المرور أو الجلسات لأي جهة."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        layout.addView(swAuto)
        layout.addView(tvPrivacy)

        AlertDialog.Builder(this)
            .setTitle("الإعدادات")
            .setView(layout)
            .setPositiveButton("حسناً", null)
            .show()
    }

    private fun claimSingleAccount(account: AccountItem) {
        if (isClaimingAll) return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("جارٍ الاستلام...")
            .setMessage("حساب: ${account.name}")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val res = ClaimEngine.claimAccount(this@MainActivity, account)
            progressDialog.dismiss()
            reloadData()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (res.success) "مبروك!" else "تنبيه")
                .setMessage("حساب ${account.name}: ${res.message}")
                .setPositiveButton("حسناً", null)
                .show()
        }
    }

    private fun confirmClaimAll() {
        if (isClaimingAll) return
        val pending = allAccounts.filter { !it.isClaimedThisWeek() }
        if (pending.isEmpty()) {
            Toast.makeText(this, "جميع الحسابات تم الاستلام منها هذا الأسبوع ✓", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("استلام الكل")
            .setMessage("سيتم محاولة الاستلام من ${pending.size} حساب بفاصل 10 ثوانٍ بين كل حساب.")
            .setPositiveButton("نعم، استلم الكل") { _, _ -> runClaimAll(pending) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runClaimAll(pending: List<AccountItem>) {
        isClaimingAll = true
        val tvStatus = TextView(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            textSize = 15f
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("جارٍ استلام الكل...")
            .setView(tvStatus)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            var successCount = 0
            var totalPoints = 0

            for ((index, acc) in pending.withIndex()) {
                tvStatus.text = "جارٍ الاستلام (${index + 1}/${pending.size})\nالحساب: ${acc.name}"
                val res = ClaimEngine.claimAccount(this@MainActivity, acc)
                if (res.success && !res.alreadyClaimed) {
                    successCount++
                    totalPoints += res.pointsCount
                }
                reloadData()
                if (index < pending.lastIndex) {
                    tvStatus.text = "انتظار 10 ثوانٍ قبل الحساب التالي..."
                    delay(10_000)
                }
            }

            dlg.dismiss()
            isClaimingAll = false
            AlertDialog.Builder(this@MainActivity)
                .setTitle("اكتمل الاستلام ✓")
                .setMessage("تم الاستلام من $successCount حساب\nمجموع النقاط الجديدة: $totalPoints نقطة")
                .setPositiveButton("حسناً", null)
                .show()
        }
    }

    private fun showEditDialog(account: AccountItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }

        fun makeInput(hintText: String, initial: String): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(initial)
                setSingleLine(true)
            }
        }

        val etEmail = makeInput("بريد Google (مثال: name@gmail.com)", account.email)
        val etName = makeInput("اسم اختياري للتذكير (مثال: فضي، A8)", account.name)
        val etCategory = makeInput("تصنيف / مجموعة (اختياري)", account.category)
        val etNote = makeInput("ملاحظة (اختياري)", account.note)

        val tvCountry = TextView(this).apply {
            text = "دولة Play للحساب:"
            setPadding(0, dp(8), 0, dp(4))
        }
        val spinner = Spinner(this).apply {
            val labels = AccountStore.TIMEZONES.map { it.first }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            val curIdx = AccountStore.TIMEZONES.indexOfFirst { it.second == account.timezone }
            if (curIdx >= 0) setSelection(curIdx)
        }

        val tvSession = TextView(this).apply {
            text = if (account.hasValidSession()) "حالة الجلسة: محفوظة ✓" else "حالة الجلسة: غير محفوظة ✕"
            setTextColor(Color.parseColor(if (account.hasValidSession()) "#22C55E" else "#EF4444"))
            setPadding(0, dp(10), 0, dp(10))
        }

        val btnDelete = Button(this).apply {
            text = "حذف الحساب"
            setTextColor(Color.parseColor("#FCA5A5"))
            background = roundedBg("#3B1519", 10)
        }

        layout.addView(etEmail)
        layout.addView(etName)
        layout.addView(etCategory)
        layout.addView(etNote)
        layout.addView(tvCountry)
        layout.addView(spinner)
        layout.addView(tvSession)
        layout.addView(btnDelete)

        val dialog = AlertDialog.Builder(this)
            .setTitle("تعديل الحساب")
            .setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                account.email = etEmail.text.toString().trim()
                account.name = etName.text.toString().trim().ifEmpty { account.name }
                account.category = etCategory.text.toString().trim()
                account.note = etNote.text.toString().trim()
                account.timezone = AccountStore.TIMEZONES[spinner.selectedItemPosition].second
                AccountStore.addOrUpdate(this, account)
                reloadData()
            }
            .setNeutralButton("إعادة تسجيل الدخول") { _, _ ->
                val i = Intent(this, LoginActivity::class.java)
                i.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, account.id)
                loginLauncher.launch(i)
            }
            .setNegativeButton("إلغاء", null)
            .create()

        btnDelete.setOnClickListener {
            AccountStore.delete(this, account.id)
            dialog.dismiss()
            reloadData()
        }

        dialog.show()
    }

    inner class AccountAdapter : RecyclerView.Adapter<AccountAdapter.VH>() {
        inner class VH(
            val card: LinearLayout,
            val tvAvatar: TextView,
            val tvName: TextView,
            val tvSub: TextView,
            val tvStatus: TextView,
            val btnClaim: Button
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg("#162228", 16, "#22333B")
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            val tvAvatar = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#22C55E"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }

            val infoCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                    marginEnd = dp(8)
                }
            }

            val tvName = TextView(this@MainActivity).apply {
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val tvSub = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            }

            val tvStatus = TextView(this@MainActivity).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(3), 0, 0)
            }

            infoCol.addView(tvName)
            infoCol.addView(tvSub)
            infoCol.addView(tvStatus)

            val btnClaim = Button(this@MainActivity).apply {
                text = "استلام"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                background = roundedBg("#122633", 12, "#1E3A4C")
                layoutParams = LinearLayout.LayoutParams(dp(78), dp(40))
            }

            card.addView(tvAvatar)
            card.addView(infoCol)
            card.addView(btnClaim)
            return VH(card, tvAvatar, tvName, tvSub, tvStatus, btnClaim)
        }

        override fun getItemCount(): Int = filteredAccounts.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val acc = filteredAccounts[position]
            val claimed = acc.isClaimedThisWeek()

            holder.tvAvatar.text = acc.name.firstOrNull()?.uppercase() ?: "A"
            (holder.tvAvatar.background as GradientDrawable).setColor(
                Color.parseColor(if (claimed) "#22C55E" else "#0EA5E9")
            )

            holder.tvName.text = acc.name
            val label = acc.category.ifBlank { acc.email.ifBlank { acc.name } }
            holder.tvSub.text = "$label • ${AccountStore.nextResetShortLabel(acc.timezone)}"

            if (claimed) {
                holder.tvStatus.text = "تم الاستلام ✓ ${acc.lastPointsText}".trim()
                holder.tvStatus.setTextColor(Color.parseColor("#22C55E"))
            } else {
                holder.tvStatus.text = "بانتظار الاستلام"
                holder.tvStatus.setTextColor(Color.parseColor("#F59E0B"))
            }

            holder.card.setOnClickListener { showEditDialog(acc) }
            holder.btnClaim.setOnClickListener { claimSingleAccount(acc) }
        }
    }
}
