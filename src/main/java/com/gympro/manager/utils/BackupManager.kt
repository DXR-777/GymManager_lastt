package com.gympro.manager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.model.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * نسخ احتياطي واستعادة كاملة لبيانات التطبيق بصيغة JSON قابلة للقراءة والمشاركة.
 *
 * ملاحظة: بيانات Room (SQLite) لا تُفقد أبداً عند إغلاق التطبيق أو إعادة تشغيل
 * الهاتف بشكل طبيعي؛ هذه الميزة هي طبقة حماية إضافية تحفظ نسخة خارجية يمكن
 * مشاركتها (واتساب، بريد، تخزين سحابي) لاستخدامها عند تغيير الهاتف أو حذف التطبيق.
 */
object BackupManager {

    private const val BACKUP_FOLDER = "GymManagerBackup"

    private fun backupDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), BACKUP_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * البند 26: لم يكن هناك أي طريقة لمعرفة تقدّم النسخ الاحتياطي الفعلي — فقط
     * مؤشر دوّار عام. [onProgress] اختياري (لا يكسر أي استدعاء قديم بلا Callback)،
     * يُستدعى دورياً أثناء تسلسل الأعضاء/الاشتراكات (الحلقتان اللتان يتناسب زمنهما
     * فعلياً مع حجم البيانات) بعدد العناصر المعالَجة من الإجمالي، بحيث تُبنى نسبة
     * مئوية حقيقية على الواجهة بدل تخمين. يُستدعى على Dispatchers.Main كي يستدعي
     * المستدعي (SettingsFragment) تحديث الواجهة مباشرة بأمان دون withContext إضافي.
     */
    suspend fun createBackupFile(
        context: Context,
        repository: GymRepository,
        onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null
    ): File =
        withContext(Dispatchers.IO) {
            val members = repository.getAllMembersRaw()
            val subscriptions = repository.getAllSubscriptionsRaw()
            val settings = repository.getSettingsOnce()
            val total = (members.size + subscriptions.size).coerceAtLeast(1)
            var processed = 0
            // يُبلَّغ كل 20 عنصراً أو عند آخر عنصر فقط (لا عند كل عنصر) — تحديث الواجهة
            // مئات المرات في الثانية لمليون عنصر مبالغة تُبطئ العملية نفسها بلا فائدة
            // مرئية؛ 20 عنصراً كافية لإحساس سلس بالتقدّم حتى مع آلاف الأعضاء.
            suspend fun tick() {
                processed++
                if (onProgress != null && (processed % 20 == 0 || processed == total)) {
                    withContext(Dispatchers.Main) { onProgress(processed, total) }
                }
            }

            val json = JSONObject().apply {
                put("backupVersion", 1)
                put("createdAt", System.currentTimeMillis())
                put("settings", settingsToJson(settings))
                put("members", JSONArray().apply { members.forEach { put(memberToJson(it)); tick() } })
                put("subscriptions", JSONArray().apply { subscriptions.forEach { put(subscriptionToJson(it)); tick() } })
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(System.currentTimeMillis())
            val file = File(backupDir(context), "gym_backup_$timestamp.json")
            file.writeText(json.toString(2))
            file
        }

    fun shareBackupFile(context: Context, file: File) = shareFile(context, file, "application/json")

    /** تنسيق حجم ملف مقروء للبشر (بايت/كيلوبايت/ميغابايت) — راجع البند 26: كان
     *  حجم الملف الناتج غير معروض إطلاقاً أثناء أو بعد النسخ الاحتياطي/الاستعادة. */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes بايت"
        bytes < 1024 * 1024 -> "%.1f كيلوبايت".format(bytes / 1024.0)
        else -> "%.2f ميغابايت".format(bytes / (1024.0 * 1024.0))
    }

    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    suspend fun restoreFromUri(
        context: Context,
        uri: Uri,
        repository: GymRepository,
        onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null
    ) {
        val (members, subscriptions, settings) = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("لا يمكن قراءة الملف")
            val json = JSONObject(text)

            val settings = jsonToSettings(json.getJSONObject("settings"))
            val membersArray = json.getJSONArray("members")
            val subsArray = json.getJSONArray("subscriptions")
            val total = (membersArray.length() + subsArray.length()).coerceAtLeast(1)
            var processed = 0
            suspend fun tick() {
                processed++
                if (onProgress != null && (processed % 20 == 0 || processed == total)) {
                    withContext(Dispatchers.Main) { onProgress(processed, total) }
                }
            }

            val members = mutableListOf<MemberEntity>()
            for (i in 0 until membersArray.length()) { members.add(jsonToMember(membersArray.getJSONObject(i))); tick() }

            val subscriptions = mutableListOf<SubscriptionEntity>()
            for (i in 0 until subsArray.length()) { subscriptions.add(jsonToSubscription(subsArray.getJSONObject(i))); tick() }

            Triple(members, subscriptions, settings)
        }

        repository.restoreAll(members, subscriptions, settings)
    }

    /**
     * البند 25: كان التصدير يقتصر على `observeActiveMembers` فقط، فالأعضاء
     * المؤرشفون/المحذوفون لا يظهرون في CSV إطلاقاً بلا أي خيار لتضمينهم.
     * الآن [archivedMembers] معامل اختياري (فارغ افتراضياً كي لا يكسر أي استدعاء
     * قديم)؛ عند تمريره تُضاف صفوفهم مع عمود "الحالة" إضافي (نشط/مؤرشف) حتى لا
     * يختلط الأعضاء الحاليون بالمحذوفين بصمت داخل نفس الملف.
     */
    /**
     * البند: أحرف تُعامَل كبداية صيغة (Formula) في Excel/Google Sheets/LibreOffice إن
     * وُجدت في أول خانة الحقل — راجع csvField أدناه.
     */
    private val CSV_FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * البند 1 (حقن CSV/CSV Injection، CWE-1236): كانت appendRow في exportMembersCsv تكتب
     * حقول المستخدم (الاسم، اسم/رقم المُرسِل...) مباشرة بين علامتي اقتباس بلا أي تعقيم،
     * بثغرتين حقيقيتين معاً:
     * 1) أي علامة اقتباس (") داخل القيمة نفسها (اسم كُتب فعلياً بهذا الشكل، أو نُسخ من
     *    مصدر خارجي) كانت تكسر بنية الصف وتُزيح كل الأعمدة التالية بصمت — CSV غير صالح
     *    دون أي خطأ ظاهر عند التصدير.
     * 2) الأخطر: أي قيمة تبدأ بـ =/+/-/@ (اسم مُرسِل نُسخ ولصق من واتساب مثلاً، قد يحتوي
     *    نصاً كهذا بالصدفة أو عمداً) يُفسِّره Excel/Google Sheets/LibreOffice كصيغة فعلية
     *    تُنفَّذ تلقائياً عند فتح الملف (مثل =HYPERLINK(...) أو صيغ أخطر) — لا علاقة لهذا
     *    بصلاحيات نظام التشغيل إطلاقاً؛ يكفي فتح الملف المصدَّر في برنامج جداول بيانات.
     * الحل القياسي (OWASP): تهريب علامات الاقتباس الداخلية بمضاعفتها ("" بدل ")، وإضافة
     * علامة اقتباس مفردة (') قبل أي قيمة تبدأ بأحد أحرف الصيغة أعلاه — تُجبر برنامج
     * الجداول على معاملتها كنص حرفي بدل صيغة، بلا أي أثر مرئي على القيمة المعروضة.
     */
    private fun csvField(value: String): String {
        val safeValue = if (value.isNotEmpty() && value[0] in CSV_FORMULA_TRIGGER_CHARS) {
            "'$value"
        } else {
            value
        }
        val escaped = safeValue.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    suspend fun exportMembersCsv(
        context: Context,
        members: List<com.gympro.manager.model.MemberListItem>,
        archivedMembers: List<com.gympro.manager.model.MemberListItem> = emptyList()
    ): File =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.append("الاسم,رقم الجوال,نوع الاشتراك,تاريخ البدء,تاريخ الانتهاء,السعر,حالة الدفع,طريقة الدفع,اسم المُرسِل,رقم جوال المُرسِل,الحالة\n")
            fun appendRow(m: com.gympro.manager.model.MemberListItem, statusLabel: String) {
                val type = m.type?.name ?: "-"
                val start = m.startDate?.let { DateUtils.formatDateShort(it) } ?: "-"
                val end = m.endDate?.let { DateUtils.formatDateShort(it) } ?: "-"
                val price = m.price?.toString() ?: "-"
                val paid = when (m.lastSubscriptionStatus()) {
                    com.gympro.manager.model.PaymentStatus.PAID -> "مدفوع"
                    com.gympro.manager.model.PaymentStatus.PARTIAL -> "جزئي"
                    com.gympro.manager.model.PaymentStatus.UNPAID -> "غير مدفوع"
                    null -> "-"
                }
                val paymentMethod = m.paymentMethod?.label(context) ?: "-"
                val senderName = m.senderName ?: "-"
                val senderPhone = m.senderPhone ?: "-"
                val fields = listOf(
                    m.name, m.phone, type, start, end, price, paid,
                    paymentMethod, senderName, senderPhone, statusLabel
                )
                sb.append(fields.joinToString(",") { csvField(it) }).append("\n")
            }
            members.forEach { appendRow(it, "نشط") }
            archivedMembers.forEach { appendRow(it, "مؤرشف/محذوف") }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(System.currentTimeMillis())
            val file = File(backupDir(context), "members_export_$timestamp.csv")
            file.writeText(sb.toString())
            file
        }

    /**
     * تصدير أي تقرير مُجهَّز مسبقاً (صفوف/خلايا) إلى ملف .xlsx حقيقي — راجع البند 24:
     * مشاركة تقرير الإيرادات كانت نصاً عادياً فقط بلا أي ملف قابل للفتح في Excel.
     * عام (لا يخصّ الإيرادات وحدها) كي يُعاد استخدامه لأي تقرير جدولي لاحقاً؛
     * بناء محتوى الصفوف مسؤولية المستدعي (مثلاً RevenueFragment) لإبقاء هذا الملف
     * بلا اعتماد على طبقة الـ UI.
     */
    suspend fun exportXlsx(
        context: Context,
        fileNamePrefix: String,
        sheetName: String,
        rows: List<List<XlsxWriter.Cell>>,
        columnWidths: List<Int> = emptyList()
    ): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(System.currentTimeMillis())
        val safePrefix = fileNamePrefix.ifBlank { "report" }.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val file = File(backupDir(context), "${safePrefix}_$timestamp.xlsx")
        XlsxWriter.write(file, sheetName, rows, columnWidths)
        file
    }

    // ---------- تحويل JSON ----------

    private fun memberToJson(m: MemberEntity) = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("phone", m.phone)
        put("notes", m.notes)
        put("photoPath", m.photoPath ?: JSONObject.NULL)
        put("isDeleted", m.isDeleted)
        put("deletedAt", m.deletedAt ?: JSONObject.NULL)
        put("createdAt", m.createdAt)
    }

    private fun jsonToMember(o: JSONObject) = MemberEntity(
        id = o.getLong("id"),
        name = o.getString("name"),
        phone = o.getString("phone"),
        notes = o.optString("notes", ""),
        photoPath = if (o.isNull("photoPath")) null else o.getString("photoPath"),
        isDeleted = o.optBoolean("isDeleted", false),
        deletedAt = if (o.isNull("deletedAt")) null else o.getLong("deletedAt"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )

    private fun subscriptionToJson(s: SubscriptionEntity) = JSONObject().apply {
        put("id", s.id)
        // memberId أصبح قابلاً لأن يكون NULL (اشتراك مدفوع بقي بعد حذف عضوه نهائياً —
        // راجع SubscriptionEntity/MIGRATION_3_4)، فيجب تسلسله بأمان بدل افتراضه دائماً موجوداً.
        put("memberId", s.memberId ?: JSONObject.NULL)
        put("type", s.type.name)
        put("price", s.price)
        put("startDate", s.startDate)
        put("endDate", s.endDate)
        put("isPaid", s.isPaid)
        put("createdAt", s.createdAt)
        // تاريخ التحصيل الفعلي للدفعة — لازم للمحافظة على دقة تقارير الإيرادات بعد
        // استعادة نسخة احتياطية. بدونه، أي استعادة كانت ستُعيد ضبط كل الدفعات المُحصَّلة
        // سابقاً إلى paidAt = null (افتراضي الحقل)، فتختفي من كل تقارير الإيرادات الماضية
        // دون أي تنبيه لصاحب الجيم رغم أن الاشتراك ما زال isPaid = true ظاهرياً.
        put("paidAt", s.paidAt ?: JSONObject.NULL)
        put("billingAnchorDay", s.billingAnchorDay)
        put("paymentMethod", s.paymentMethod.name)
        // اسم ورقم جوال المُرسِل في حالة الدفع الإلكتروني — حقلان اختياريان بحتان لا علاقة
        // لهما بأي حساب إيراد، راجع الشرح الكامل في SubscriptionEntity.senderName/senderPhone.
        put("senderName", s.senderName ?: JSONObject.NULL)
        put("senderPhone", s.senderPhone ?: JSONObject.NULL)
        // مبلغ الدفع الجزئي (ميزة "الدفع الجزئي" الجديدة) — بدون تسلسله هنا، كانت أي
        // استعادة لنسخة احتياطية ستُفقد تماماً حالة "جزئي" لكل الاشتراكات (تعود isPaid=0
        // بلا paidAmount، أي UNPAID بالكامل) رغم أن جزءاً حقيقياً منها كان قد دخل الإيراد
        // فعلاً قبل النسخ الاحتياطي — راجع SubscriptionEntity.paidAmount وPaymentStatus.kt.
        put("paidAmount", s.paidAmount ?: JSONObject.NULL)
    }

    private fun jsonToSubscription(o: JSONObject) = SubscriptionEntity(
        id = o.getLong("id"),
        // نسخ احتياطية قديمة (قبل هذا الإصلاح) لن تحتوي قيمة NULL أصلاً؛ ندعم الحالتين معاً.
        memberId = if (!o.has("memberId") || o.isNull("memberId")) null else o.getLong("memberId"),
        type = SubscriptionType.fromString(o.getString("type")),
        price = o.getDouble("price"),
        startDate = o.getLong("startDate"),
        endDate = o.getLong("endDate"),
        isPaid = o.optBoolean("isPaid", false),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        // نسخ احتياطية قديمة (قبل هذا الإصلاح) لن تحتوي هذا الحقل؛ في هذه الحالة فقط
        // (وليس عند null صريحة) نستخدم createdAt كأفضل تقدير بدل فقدان الإيراد تماماً —
        // نفس منطق ترقية قاعدة البيانات MIGRATION_2_3 تماماً، حفاظاً على الاتساق.
        paidAt = when {
            !o.has("paidAt") -> if (o.optBoolean("isPaid", false)) o.optLong("createdAt", System.currentTimeMillis()) else null
            o.isNull("paidAt") -> null
            else -> o.getLong("paidAt")
        },
        billingAnchorDay = o.optInt("billingAnchorDay", 0),
        // نسخ احتياطية قديمة (قبل إضافة ميزة طريقة الدفع) لن تحتوي هذا الحقل إطلاقاً؛
        // PaymentMethod.fromString تُرجع PALPAY افتراضياً لأي قيمة مفقودة أو غير معروفة،
        // فتتم الاستعادة بنجاح دائماً دون استثناء (نفس متطلب "الافتراضي PALPAY" في الإضافة).
        paymentMethod = PaymentMethod.fromString(o.optString("paymentMethod")),
        // نسخ احتياطية قديمة (قبل إضافة اسم/رقم جوال المُرسِل) لن تحتوي هذين الحقلين إطلاقاً؛
        // isNull/has تتعامل مع الحالتين معاً (حقل غائب تماماً، أو حقل موجود بقيمة NULL صريحة)
        // وتُرجع null بأمان في كلتا الحالتين — بلا أي استثناء أو Crash.
        senderName = if (!o.has("senderName") || o.isNull("senderName")) null else o.getString("senderName"),
        senderPhone = if (!o.has("senderPhone") || o.isNull("senderPhone")) null else o.getString("senderPhone"),
        // نسخ احتياطية قديمة (قبل إضافة ميزة "الدفع الجزئي") لن تحتوي هذا الحقل إطلاقاً؛
        // isNull/has تتعامل مع الحالتين معاً وتُرجع null بأمان (أي UNPAID أو PAID بحسب
        // isPaid المُستعاد، كما كان الحال بالضبط قبل وجود هذه الميزة) — لا استثناء ولا Crash.
        paidAmount = if (!o.has("paidAmount") || o.isNull("paidAmount")) null else o.getDouble("paidAmount")
    )

    private fun settingsToJson(s: GymSettingsEntity) = JSONObject().apply {
        put("gymName", s.gymName)
        put("dailyPrice", s.dailyPrice)
        put("weeklyPrice", s.weeklyPrice)
        put("monthlyPrice", s.monthlyPrice)
        put("whatsappPrefix", s.whatsappPrefix ?: JSONObject.NULL)
        put("currencySymbol", s.currencySymbol)
        put("logoPath", s.logoPath ?: JSONObject.NULL)
        put("notifyExpiry", s.notifyExpiry)
        put("notifyUnpaid", s.notifyUnpaid)
        put("isSetupComplete", s.isSetupComplete)
    }

    private fun jsonToSettings(o: JSONObject) = GymSettingsEntity(
        id = 1,
        gymName = o.optString("gymName", ""),
        dailyPrice = o.optDouble("dailyPrice", 3.0),
        weeklyPrice = o.optDouble("weeklyPrice", 20.0),
        monthlyPrice = o.optDouble("monthlyPrice", 60.0),
        whatsappPrefix = if (o.isNull("whatsappPrefix")) null else o.optString("whatsappPrefix"),
        currencySymbol = o.optString("currencySymbol", "₪"),
        logoPath = if (o.isNull("logoPath")) null else o.optString("logoPath"),
        notifyExpiry = o.optBoolean("notifyExpiry", true),
        notifyUnpaid = o.optBoolean("notifyUnpaid", true),
        isSetupComplete = o.optBoolean("isSetupComplete", true)
    )
}
