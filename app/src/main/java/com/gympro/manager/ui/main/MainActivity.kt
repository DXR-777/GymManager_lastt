package com.gympro.manager.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityMainBinding
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.ui.dashboard.DashboardFragment
import com.gympro.manager.ui.members.AddEditMemberActivity
import com.gympro.manager.ui.members.MembersFragment
import com.gympro.manager.ui.revenue.RevenueFragment
import com.gympro.manager.ui.settings.SettingsFragment

private const val PREFS_NAME = "gym_manager_main_prefs"
private const val KEY_NOTIFICATION_RATIONALE_SHOWN = "notification_rationale_shown"

class MainActivity : AppCompatActivity() {

    /**
     * البند 4: يُستخدم لتذكّر أن حوار شرح إذن الإشعارات (requestNotificationPermissionIfNeeded)
     * قد عُرض على المستخدم بالفعل ولو مرة واحدة — بدون هذا، الحوار (setCancelable(false)) كان
     * يظهر إجبارياً في كل فتح للتطبيق طالما لم يُفعَّل الإذن، حتى لمن يضغط "لاحقاً" صراحة، بلا
     * أي وسيلة لإيقافه نهائياً من داخل التطبيق. تُقرأ/تُكتب مباشرة عبر getSharedPreferences
     * بدل مخزن إعدادات الغرفة (GymSettingsEntity) لأنها تفضيل واجهة بحت على مستوى الجهاز، لا
     * بيانات نادٍ تُنسَخ احتياطياً أو تُشارَك بين الأجهزة.
     */
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var binding: ActivityMainBinding

    // فلتر مُعلَّق يُستهلك مرة واحدة فقط عند إنشاء تبويب الأعضاء التالي —
    // يُستخدم من openMembersFiltered() عند الضغط على بطاقات لوحة التحكم (انظر البند 12).
    private var pendingMembersFilter: MemberFilter? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* لا حاجة لمعالجة خاصة */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            showFragment(DashboardFragment())
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showFragment(createFragment(item.itemId))
            updateFabVisibility(item.itemId)
            true
        }
        // يضبط حالة الزر العائم الصحيحة فور فتح التطبيق (وليس فقط بعد ضغط تبويب يدوياً)
        updateFabVisibility(binding.bottomNav.selectedItemId)

        binding.fabAddMember.setOnClickListener {
            it.isEnabled = false
            startActivity(Intent(this, AddEditMemberActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // العودة من AddEditMemberActivity عبر زر الرجوع تُعيد استخدام نفس نسخة هذه الشاشة
        // دون المرور بـ onCreate مجدداً، لذا يجب إعادة تفعيل الزر هنا صراحة.
        binding.fabAddMember.isEnabled = true
    }

    /**
     * الزر العائم يظهر في تبويبي الرئيسية والأعضاء معاً، لا في الأعضاء فقط — الشاشة
     * الرئيسية هي أول ما يراه المستخدم عند فتح التطبيق يومياً، فحرمانها من وصول سريع
     * لإضافة عضو يجبره على التنقّل لتبويب الأعضاء أولاً لأبسط عملية يقوم بها يومياً.
     * يبقى مخفياً في الإيرادات والإعدادات حيث لا معنى له.
     */
    private fun updateFabVisibility(selectedItemId: Int) {
        val showFab = selectedItemId == R.id.nav_dashboard || selectedItemId == R.id.nav_members
        binding.fabAddMember.visibility =
            if (showFab) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun createFragment(itemId: Int): Fragment {
        val fragment: Fragment = when (itemId) {
            R.id.nav_dashboard -> DashboardFragment()
            R.id.nav_members -> MembersFragment.newInstance(pendingMembersFilter)
            R.id.nav_revenue -> RevenueFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> DashboardFragment()
        }
        pendingMembersFilter = null
        return fragment
    }

    /**
     * يفتح تبويب الأعضاء مُطبَّقاً عليه فلتر جاهز — تُستدعى من بطاقات لوحة التحكم
     * (عدد الأعضاء/غير المدفوعين/القريبين من الانتهاء) بدلاً من تركها غير قابلة للنقر.
     */
    fun openMembersFiltered(filter: MemberFilter) {
        pendingMembersFilter = filter
        if (binding.bottomNav.selectedItemId == R.id.nav_members) {
            // التبويب مفتوح أصلاً، فلن يُطلق onItemSelectedListener تلقائياً؛ نطبّق يدوياً.
            showFragment(createFragment(R.id.nav_members))
            updateFabVisibility(R.id.nav_members)
        } else {
            // سيُطلق onItemSelectedListener تلقائياً ويتكفّل بإنشاء الفرجمنت وتحديث الـ FAB.
            binding.bottomNav.selectedItemId = R.id.nav_members
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        // البند 4: لا يُعرض الحوار إطلاقاً إن سبق عرضه من قبل (أي اختيار سابق من المستخدم،
        // "لاحقاً" أو "تفعيل") — بدل عرضه إجبارياً في كل فتح للتطبيق طالما الإذن غير مُفعَّل.
        if (prefs.getBoolean(KEY_NOTIFICATION_RATIONALE_SHOWN, false)) return

        // البند 19: كان يُطلب إذن الإشعارات مباشرة من النظام فور فتح الشاشة الرئيسية لأول
        // مرة بلا أي شرح مسبق (Rationale)، ما يقلل معدل الموافقة عادة (المستخدم يرى حوار
        // نظام مفاجئاً بلا سياق). نعرض الآن حواراً توضيحياً بلغة التطبيق أولاً يشرح فائدة
        // الإشعارات، ولا نستدعي حوار النظام الفعلي إلا بعد أن يضغط المستخدم "تفعيل".
        //
        // البند 4: العلَم يُسجَّل هنا — قبل show() مباشرة — بغض النظر عن الزر الذي سيختاره
        // المستخدم لاحقاً (تفعيل أو لاحقاً)، لأن مجرد عرض الحوار مرة واحدة يكفي لتحقيق
        // هدفه (شرح الفائدة قبل حوار النظام)؛ تكراره بعد أول ظهور له لا يضيف شيئاً ويُزعج
        // فقط. من يضغط "تفعيل" ثم يرفض حوار النظام يبقى بإمكانه تفعيل الإذن يدوياً من
        // إعدادات النظام لاحقاً، بلا حاجة لعودة هذا الحوار للإلحاح عليه من جديد.
        prefs.edit().putBoolean(KEY_NOTIFICATION_RATIONALE_SHOWN, true).apply()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_rationale_title)
            .setMessage(R.string.notification_rationale_message)
            .setCancelable(false)
            .setNegativeButton(R.string.notification_rationale_later, null)
            .setPositiveButton(R.string.notification_rationale_allow) { _, _ ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .show()
    }
}
