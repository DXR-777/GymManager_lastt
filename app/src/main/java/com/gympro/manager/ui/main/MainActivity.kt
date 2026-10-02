package com.gympro.manager.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.activity.OnBackPressedCallback
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

    /**
     * إصلاح فقدان حالة التبويبات: كانت showFragment() تستدعي replace() في كل ضغطة على
     * شريط التنقّل السفلي، فتُدمَّر نسخة الفراغمنت السابقة (ومعها ViewModel المرتبط بها
     * عبر by viewModels{}) كاملة. الأثر العملي: نص البحث والفلتر المختار في تبويب
     * "الأعضاء"، ونطاق التاريخ المختار في "الإيرادات"، وموضع التمرير في كليهما — كل هذا
     * كان يُصفَّر فوراً بمجرد الانتقال للحظة لتبويب آخر والعودة، رغم أن المستخدم لم
     * يطلب إعادة تعيين أي شيء. الحل: كل تبويب يُنشأ مرة واحدة فقط بعلامة (tag) ثابتة
     * ويبقى حياً طوال عمر النشاط، ويُخفى/يُظهر (hide/show) بدل أن يُستبدل — نفس النمط
     * الموصى به رسمياً لشريط تنقّل سفلي مع أكثر من تبويب.
     */
    private val tabTagByItemId = mapOf(
        R.id.nav_dashboard to "tab:dashboard",
        R.id.nav_members to "tab:members",
        R.id.nav_revenue to "tab:revenue",
        R.id.nav_settings to "tab:settings"
    )

    // فلتر مُعلَّق يُطبَّق على تبويب الأعضاء عند فتحه —
    // يُستخدم من openMembersFiltered() عند الضغط على بطاقات لوحة التحكم (انظر البند 12).
    // بعد إصلاح فقدان الحالة أعلاه: يُستهلك إما كوسيطة إنشاء (نسخة جديدة أول مرة) أو
    // عبر استدعاء مباشر لـ MembersFragment.applyFilter() على النسخة الحيّة أصلاً.
    private var pendingMembersFilter: MemberFilter? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* لا حاجة لمعالجة خاصة */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupBottomNavInset()

        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            showTab(R.id.nav_dashboard)
        }
        // عند إعادة إنشاء النشاط (savedInstanceState != null، مثلاً بعد تدوير الشاشة)،
        // يُعيد FragmentManager تلقائياً إرفاق كل تبويبات tabTagByItemId المُضافة سابقاً
        // بنفس حالة hide/show التي كانت عليها — لا حاجة لاستدعاء showTab() هنا مجدداً.

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            updateFabVisibility(item.itemId)
            true
        }
        // يضبط حالة الزر العائم الصحيحة فور فتح التطبيق (وليس فقط بعد ضغط تبويب يدوياً)
        updateFabVisibility(binding.bottomNav.selectedItemId)

        binding.fabAddMember.setOnClickListener {
            it.isEnabled = false
            startActivity(Intent(this, AddEditMemberActivity::class.java))
        }

        /**
         * البند 2: لم يكن هناك أي معالجة لزر/إيماءة الرجوع في هذا النشاط — يخرج المستخدم
         * من التطبيق فوراً بمجرد ضغطة رجوع واحدة من أي تبويب غير "الرئيسية" (الإعدادات،
         * الإيرادات، الأعضاء)، خلافاً للسلوك المعتاد في كل تطبيقات شريط التنقّل السفلي
         * (يعود أولاً لتبويب "الرئيسية"، ولا يخرج من التطبيق إلا بضغطة ثانية من هناك).
         * نفس نمط OnBackPressedCallback المستخدم في GymSetupActivity لخطوات الإعداد.
         */
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNav.selectedItemId != R.id.nav_dashboard) {
                    binding.bottomNav.selectedItemId = R.id.nav_dashboard
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 4.3: fragmentContainer يمتد خلف القائمة العائمة (ليظهر المحتوى من خلال زجاجها)، فنضيف
     * للعناصر القابلة للتمرير الموسومة بـ "nav_inset_target" حشوة سفلية = ارتفاع القائمة + هامشها،
     * مع clipToPadding=false، فلا يبقى آخر عنصر مخفياً تحت القائمة.
     */
    private var navInsetPx = 0

    private fun setupBottomNavInset() {
        binding.bottomNav.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val margin = (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            val inset = (bottom - top) + margin
            if (inset != navInsetPx) {
                navInsetPx = inset
                applyNavInset(binding.fragmentContainer)
            }
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
                ) {
                    applyNavInset(v)
                }
            }, false
        )
    }

    private fun applyNavInset(root: View) {
        if (root.tag == "nav_inset_target") {
            val base = (root.getTag(R.id.tag_base_padding_bottom) as? Int)
                ?: root.paddingBottom.also { root.setTag(R.id.tag_base_padding_bottom, it) }
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, base + navInsetPx)
            (root as? ViewGroup)?.clipToPadding = false
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyNavInset(root.getChildAt(i))
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

    private fun newFragmentFor(itemId: Int): Fragment = when (itemId) {
        R.id.nav_dashboard -> DashboardFragment()
        R.id.nav_members -> MembersFragment.newInstance(pendingMembersFilter.also { pendingMembersFilter = null })
        R.id.nav_revenue -> RevenueFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> DashboardFragment()
    }

    /**
     * يعرض تبويباً بإخفاء البقية بدل استبدالها — كل تبويب يُنشأ مرة واحدة فقط (بعلامة
     * ثابتة من tabTagByItemId) ثم يبقى حياً مخفياً في الخلفية طوال عمر النشاط، فتُحفَظ
     * حالته (نص بحث، فلتر، نطاق تاريخ، موضع تمرير) تلقائياً بين مرات فتحه. راجع تعليق
     * tabTagByItemId أعلاه لتفاصيل المشكلة السابقة (replace() يُدمِّر كل شيء في كل ضغطة).
     */
    private fun showTab(itemId: Int): Fragment {
        val targetTag = tabTagByItemId[itemId] ?: return newFragmentFor(itemId)
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()

        tabTagByItemId.values.forEach { tag ->
            if (tag != targetTag) {
                fm.findFragmentByTag(tag)?.let { transaction.hide(it) }
            }
        }

        val existing = fm.findFragmentByTag(targetTag)
        val target = if (existing != null) {
            transaction.show(existing)
            existing
        } else {
            val created = newFragmentFor(itemId)
            transaction.add(R.id.fragmentContainer, created, targetTag)
            created
        }
        // commitNow() بدل commit() العادية: ننفّذ المعاملة فوراً (لا ننتظر دورة الرسائل
        // التالية للـ UI thread) لأن openMembersFiltered() أدناه قد يحتاج فوراً بعد هذا
        // الاستدعاء التعامل مع "target" كفراغمنت مُرفَق فعلياً بعرضه (view) جاهزاً —
        // لا كطلب معلَّق قد يُنفَّذ لاحقاً.
        transaction.commitNow()
        return target
    }

    /**
     * يفتح تبويب الأعضاء مُطبَّقاً عليه فلتر جاهز — تُستدعى من بطاقات لوحة التحكم
     * (عدد الأعضاء/غير المدفوعين/القريبين من الانتهاء) بدلاً من تركها غير قابلة للنقر.
     */
    fun openMembersFiltered(filter: MemberFilter) {
        pendingMembersFilter = filter
        val alreadyOnMembersTab = binding.bottomNav.selectedItemId == R.id.nav_members
        if (alreadyOnMembersTab) {
            // شريط التنقّل لن يُطلق onItemSelectedListener لأن نفس التبويب مختار أصلاً؛
            // النسخة موجودة وحيّة غالباً (لم يُعاد إنشاؤها) فنطبّق الفلتر عليها مباشرة
            // بدل newInstance() التي لا تُقرأ إلا عند إنشاء نسخة جديدة من الصفر.
            val fragment = showTab(R.id.nav_members)
            (fragment as? MembersFragment)?.applyFilter(filter)
            pendingMembersFilter = null
            updateFabVisibility(R.id.nav_members)
        } else {
            // سيُطلق onItemSelectedListener تلقائياً ويتكفّل بعرض/إنشاء التبويب وتحديث الـ FAB.
            // إن كانت نسخة "الأعضاء" حيّة أصلاً من قبل (تبويب آخر مفتوح حالياً)، showTab()
            // ستعرضها كما هي بلا استدعاء applyFilter() عندها — لذا نطبّق الفلتر هنا أيضاً
            // صراحة على أي نسخة موجودة مسبقاً، تحسّباً لهذه الحالة بالذات.
            val existing = supportFragmentManager.findFragmentByTag(tabTagByItemId[R.id.nav_members])
            binding.bottomNav.selectedItemId = R.id.nav_members
            (existing as? MembersFragment)?.applyFilter(filter)
            if (existing != null) pendingMembersFilter = null
        }
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
