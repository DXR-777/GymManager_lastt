package com.gympro.manager.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gympro.manager.R
import com.gympro.manager.ui.archive.ArchiveActivity
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.ui.members.MemberDetailActivity
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.TaskStackBuilder

object NotificationHelper {

    const val CHANNEL_ID = "subscription_alerts"
    private const val EXPIRY_NOTIF_ID = 1000
    private const val UNPAID_NOTIF_ID = 9999
    private const val ARCHIVE_PURGE_NOTIF_ID = 2000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val pendingIntentFlags =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /**
     * بدون memberId: يفتح MainActivity فقط (يُستخدم لملخص "غير المدفوعين" الذي لا
     * يخص عضواً واحداً). مع memberId: يبني Back Stack اصطناعي عبر TaskStackBuilder
     * بحيث تُفتح صفحة العضو مباشرة (MemberDetailActivity) وزر الرجوع يعود إلى
     * MainActivity بدلاً من إغلاق التطبيق مباشرة — راجع البند 13.
     *
     * requestCode لكل PendingIntent مُشتقّ خصيصاً من مُعرِّف كل إشعار (راجع
     * expiryRequestCode) وليس 0 ثابتاً؛ لو تشارك كل الإشعارات نفس requestCode فإن
     * FLAG_UPDATE_CURRENT يستبدل الـ extras الخاصة بأي PendingIntent سابق بنفس
     * المكوّن/الأكشن، فيؤدي الضغط على إشعار عضو قديم إلى فتح صفحة عضو آخر (آخر إشعار
     * أُنشئ) بصمت.
     */
    private fun contentIntent(context: Context, requestCode: Int, memberId: Long?): PendingIntent {
        if (memberId == null) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags)
        }
        // يُبنى الـ Back Stack يدوياً (MainActivity ثم صفحة العضو) بدل
        // addNextIntentWithParentStack، لأن الأخيرة تتطلب تصريح android:parentActivityName
        // في AndroidManifest وهو غير موجود لـ MemberDetailActivity حالياً.
        val mainIntent = Intent(context, MainActivity::class.java)
        val detailIntent = Intent(context, MemberDetailActivity::class.java)
            .putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId)
        return TaskStackBuilder.create(context)
            .addNextIntent(mainIntent)
            .addNextIntent(detailIntent)
            .getPendingIntent(requestCode, pendingIntentFlags)
            ?: PendingIntent.getActivity(context, requestCode, detailIntent, pendingIntentFlags)
    }

    /**
     * وجهة إشعار تحذير الحذف النهائي التلقائي (راجع ArchivePurgeWorker): تفتح شاشة الأرشيف مباشرة
     * (وليس تفاصيل العضو) لأن الإجراء المطلوب من صاحب النادي هنا هو "استعادة العضو إن أراد
     * الاحتفاظ به" — وهذا الزر موجود في شاشة الأرشيف نفسها، لا في MemberDetailActivity.
     * نفس نمط TaskStackBuilder المستخدم في contentIntent أعلاه لضمان رجوع منطقي إلى
     * MainActivity بدل إغلاق التطبيق مباشرة.
     */
    private fun archiveIntent(context: Context, requestCode: Int): PendingIntent {
        val mainIntent = Intent(context, MainActivity::class.java)
        val archiveScreenIntent = Intent(context, ArchiveActivity::class.java)
        return TaskStackBuilder.create(context)
            .addNextIntent(mainIntent)
            .addNextIntent(archiveScreenIntent)
            .getPendingIntent(requestCode, pendingIntentFlags)
            ?: PendingIntent.getActivity(context, requestCode, archiveScreenIntent, pendingIntentFlags)
    }

    /** وسم فريد لكل عضو لإشعار تحذير الحذف النهائي (ArchivePurgeWorker) — راجع توثيق expiryTag أدناه لنفس المبدأ. */
    private fun archivePurgeTag(memberId: Long): String = "archive_purge_member_$memberId"

    /** راجع توثيق expiryRequestCode أدناه لنفس مبدأ اشتقاق requestCode من معرِّف Long كامل. */
    private fun archivePurgeRequestCode(memberId: Long): Int = ("archive_purge_$memberId").hashCode()

    /**
     * وسم فريد لكل عضو (راجع البند 21) — يُستخدم كـ tag في NotificationManagerCompat.notify
     * بدل ترميز memberId ضمن مُعرِّف الإشعار الرقمي (Int). سابقاً كان المعرِّف
     * EXPIRY_NOTIF_BASE_ID + memberId.toInt()، وتحويل Long الكامل إلى Int يُفقِد البتات
     * العليا: عضوان بمعرِّفين مختلفين لكن يتشاركان نفس الـ 32 بت الدنيا (فرق يساوي
     * 2^32 تقريباً، أو ببساطة عند تجاوز memberId حد Int.MAX_VALUE فيصبح سالباً بشكل
     * ملتفّ) ينتهي بهما الأمر بنفس المعرِّف، فيستبدل إشعار أحدهما إشعار الآخر خطأً.
     * الوسم النصي هنا يحمل قيمة memberId (Long) كاملة دون أي تحويل أو فقدان بتات،
     * فلا يوجد أي احتمال تصادم مهما كبر المعرِّف.
     */
    private fun expiryTag(memberId: Long): String = "expiry_member_$memberId"

    /**
     * requestCode الخاص بـ PendingIntent يبقى Int (قيد من واجهة PendingIntent نفسها)،
     * لذا لا يمكن تفادي التحويل هنا كلياً كما فعلنا مع مُعرِّف الإشعار أعلاه. لكن
     * Long.hashCode() (طيّ XOR بين النصف العلوي والسفلي من البتات الـ64) يوزّع القيم
     * على كامل مجال Int بشكل متساوٍ تقريباً، بخلاف toInt() الذي كان يتجاهل النصف
     * العلوي كاملاً ويكرر نفس القيمة دورياً كل 2^32 — احتمال التصادم هنا ضئيل جداً
     * عملياً (يتطلب تصادم Hash فعلياً)، وهذا هو النمط الشائع في تطبيقات أندرويد
     * للحصول على requestCode مستقر من مُعرِّف طويل.
     */
    private fun expiryRequestCode(memberId: Long): Int = memberId.hashCode()

    fun showExpiring(context: Context, memberId: Long, memberName: String, daysLeft: Int) {
        if (!hasPermission(context)) return
        val title = context.getString(R.string.notif_expiry_title)
        val body = context.getString(R.string.notif_expiry_body, memberName, daysLeft)
        notify(context, expiryTag(memberId), EXPIRY_NOTIF_ID, expiryRequestCode(memberId), title, body, memberId)
    }

    fun showExpired(context: Context, memberId: Long, memberName: String) {
        if (!hasPermission(context)) return
        val title = context.getString(R.string.notif_expired_title)
        val body = context.getString(R.string.notif_expired_body, memberName)
        notify(context, expiryTag(memberId), EXPIRY_NOTIF_ID, expiryRequestCode(memberId), title, body, memberId)
    }

    fun showUnpaidSummary(context: Context, count: Int) {
        if (!hasPermission(context) || count <= 0) return
        val title = context.getString(R.string.notif_unpaid_title)
        val body = context.getString(R.string.notif_unpaid_body, count)
        notify(context, tag = null, UNPAID_NOTIF_ID, UNPAID_NOTIF_ID, title, body, memberId = null)
    }

    /**
     * تنبيه بأن عضواً مؤرشفاً سيُحذف نهائياً (بلا أي إمكانية تراجع) خلال [daysLeft] يوماً —
     * راجع ArchivePurgeWorker. لا يستخدم دالة notify() المشتركة أدناه لأن وجهتها ثابتة على
     * MemberDetailActivity عبر memberId، بينما هذا الإشعار يجب أن يفتح شاشة الأرشيف
     * (archiveIntent) حتى يصل صاحب النادي مباشرة إلى زر "استعادة" إن أراد إنقاذ العضو.
     * غير مرتبط بأي مفتاح تفعيل/تعطيل في الإعدادات (خلافاً لـ notifyExpiry/notifyUnpaid)
     * عمداً: منع فقدان بيانات نهائي وبلا رجعة أهم من أن يكون تذكيراً اختيارياً.
     */
    fun showArchivePurgeWarning(context: Context, memberId: Long, memberName: String, daysLeft: Int) {
        if (!hasPermission(context)) return
        val title = context.getString(R.string.notif_archive_purge_title)
        val body = context.getString(R.string.notif_archive_purge_body, memberName, daysLeft)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(archiveIntent(context, archivePurgeRequestCode(memberId)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(archivePurgeTag(memberId), ARCHIVE_PURGE_NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // المستخدم رفض إذن الإشعارات؛ تجاهل بأمان
        }
    }

    private fun notify(
        context: Context,
        tag: String?,
        id: Int,
        requestCode: Int,
        title: String,
        body: String,
        memberId: Long?
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, requestCode, memberId))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            // tag + id معاً هما مُعرِّف الإشعار الفعلي — راجع توثيق expiryTag أعلاه.
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (e: SecurityException) {
            // المستخدم رفض إذن الإشعارات؛ تجاهل بأمان
        }
    }
}
