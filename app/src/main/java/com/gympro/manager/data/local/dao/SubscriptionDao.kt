package com.gympro.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.gympro.manager.data.local.entities.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Insert
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Delete
    suspend fun delete(subscription: SubscriptionEntity)

    /**
     * تحذف فقط الاشتراكات غير المدفوعة *بالكامل* (isPaid = 0 AND paidAmount IS NULL) لعضو
     * معيّن — تُستخدم قبل حذف العضو نهائياً لتفريغ ديونه (لا قيمة إيرادية لها أصلاً)، بينما
     * تبقى اشتراكاته المدفوعة (بالكامل أو جزئياً) محفوظة (تُفصَل عنه فقط عبر
     * onDelete = SET_NULL عند حذف صف العضو نفسه). راجع GymRepository.permanentDeleteMember().
     *
     * الاشتراكات الجزئية (isPaid = 0 لكن paidAmount > 0) تُستثنى عمداً من الحذف: جزء من
     * سعرها دخل فعلياً في تقارير الإيرادات التاريخية (عبر paidAt)، فحذفها كان سيُقلّص إجمالي
     * الإيرادات بصمت بنفس فئة الخطأ الموثّقة في MIGRATION_3_4 لاشتراكات isPaid = 1.
     */
    @Query("DELETE FROM subscriptions WHERE memberId = :memberId AND isPaid = 0 AND paidAmount IS NULL")
    suspend fun deleteUnpaidForMember(memberId: Long)

    @Query("SELECT * FROM subscriptions WHERE memberId = :memberId ORDER BY startDate DESC")
    fun getForMember(memberId: Long): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE memberId = :memberId ORDER BY endDate DESC LIMIT 1")
    suspend fun getCurrentForMember(memberId: Long): SubscriptionEntity?

    @Query("SELECT COUNT(*) FROM subscriptions WHERE memberId = :memberId AND type = 'MONTHLY'")
    suspend fun countMonthlySubscriptions(memberId: Long): Int

    /**
     * آخر اشتراك شهري (type = MONTHLY) لعضو معيّن، بحسب تاريخ البدء.
     * يُستخدم لتوريث "يوم الفوترة المرجعي" (billingAnchorDay) عند التجديد، بدل اشتقاقه من
     * تاريخ بدء قد يكون هو نفسه ناتجاً عن انزلاق شهر قصير سابق (انظر الشرح في DateUtils).
     */
    @Query("SELECT * FROM subscriptions WHERE memberId = :memberId AND type = 'MONTHLY' ORDER BY startDate DESC LIMIT 1")
    suspend fun getLastMonthlySubscription(memberId: Long): SubscriptionEntity?

    /**
     * إجمالي ما دخل فعلياً في إيراد هذا العضو عبر تاريخه الكامل: السعر الكامل لكل اشتراك
     * مدفوع بالكامل (isPaid = 1)، بالإضافة إلى المبلغ الجزئي المُحصَّل فعلياً (paidAmount)
     * لكل اشتراك بدفع جزئي — راجع PaymentStatus.kt/collectedAmount() لنفس الصيغة بالضبط
     * على مستوى اشتراك واحد.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN isPaid = 1 THEN price ELSE COALESCE(paidAmount, 0) END), 0)
        FROM subscriptions WHERE memberId = :memberId
        """
    )
    suspend fun totalPaidByMember(memberId: Long): Double

    /**
     * إجمالي الدَّين الحقيقي المتبقي على هذا العضو: لكل اشتراك غير مدفوع بالكامل
     * (isPaid = 0)، الفرق (price - paidAmount) إن كان مدفوعاً جزئياً، أو السعر كاملاً إن لم
     * يُدفع منه شيء بعد — راجع PaymentStatus.kt/remainingAmount() لنفس الصيغة على مستوى
     * اشتراك واحد.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
        FROM subscriptions WHERE memberId = :memberId AND isPaid = 0
        """
    )
    suspend fun totalOutstandingByMember(memberId: Long): Double

    /**
     * عدد سجلات الاشتراك غير المدفوعة بالكامل لعضو معيّن (أي نوع: يومي/أسبوعي/شهري)، بما
     * فيها الاشتراكات المدفوعة جزئياً (لا تزال isPaid = 0 طالما لم تُسدَّد بالكامل) — يُستخدم
     * في بانر الدَّين بالملف الشخصي.
     */
    @Query("SELECT COUNT(*) FROM subscriptions WHERE memberId = :memberId AND isPaid = 0")
    suspend fun countUnpaidByMember(memberId: Long): Int

    // ---------- إيرادات عامة (تُحسب وفق paidAt = تاريخ التحصيل الفعلي للدفعة، وليس
    // تاريخ إنشاء السجل. راجع الشرح الكامل في SubscriptionEntity.paidAt: دَين يُنشأ في
    // يناير ويُحصَّل في مارس يجب أن يظهر ضمن إيرادات مارس، لا يناير) ----------
    //
    // ملاحظة: هذان الاستعلامان لا يُصفّيان حسب m.isDeleted عمداً. isDeleted يتحكم فقط
    // بظهور العضو في قائمة الأعضاء النشطين (وهو قابل للتراجع عبر أرشفة → استعادة)، ولا
    // علاقة له بصحة معاملة مالية تاريخية تمت فعلاً (isPaid = 1). أرشفة/حذف عضو بعد انتهاء
    // اشتراكه يجب ألا يغيّر أرقام إيرادات مُغلقة وأُبلغ بها صاحب النادي سابقاً (Today/Week/
    // Month/Year/Last Month/By Type)، بنفس مبدأ عدم انزلاق الأرقام التاريخية المطبّق في
    // paidAt أعلاه.

    /**
     * الإيراد الفعلي المُحصَّل خلال فترة زمنية: السعر الكامل لأي اشتراك مدفوع بالكامل
     * (isPaid = 1)، بالإضافة إلى المبلغ الجزئي المُحصَّل فعلياً (paidAmount) لأي اشتراك بدفع
     * جزئي — كلاهما مُصفّى بحسب paidAt (تاريخ التحصيل الفعلي، راجع الشرح الكامل في
     * SubscriptionEntity.paidAt وPaymentStatus.kt). اشتراك غير مدفوع بالكامل (paidAmount
     * أيضاً NULL) لا قيمة إيرادية له بعد فيُستبعد تلقائياً بشرط الـ WHERE.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN s.isPaid = 1 THEN s.price ELSE s.paidAmount END), 0) FROM subscriptions s
        WHERE (s.isPaid = 1 OR s.paidAmount IS NOT NULL)
        AND s.paidAt BETWEEN :start AND :end
        """
    )
    suspend fun revenueBetween(start: Long, end: Long): Double

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN s.isPaid = 1 THEN s.price ELSE s.paidAmount END), 0) FROM subscriptions s
        WHERE (s.isPaid = 1 OR s.paidAmount IS NOT NULL) AND s.type = :type
        AND s.paidAt BETWEEN :start AND :end
        """
    )
    suspend fun revenueBetweenByType(start: Long, end: Long, type: String): Double

    /**
     * عدد الأعضاء الذين عليهم دَين حقيقي (لديهم اشتراك واحد غير مدفوع على الأقل عبر تاريخهم
     * الكامل)، وليس فقط من كان آخر اشتراك له غير مدفوع. هذا الاستعلام مرتبط رياضياً
     * بـ getUnpaidTotal() أدناه (نفس شرط WHERE تماماً: s.isPaid = 0 AND m.isDeleted = 0)
     * عمداً — لمنع ظهور عدد أعضاء وإجمالي مبلغ غير متطابقين في شاشة التقارير المالية القادمة
     * (مثال: "3 أعضاء متأخرين" بينما الإجمالي يعكس ديون 7 أعضاء فعلياً).
     */
    @Query(
        """
        SELECT COUNT(DISTINCT s.memberId) FROM subscriptions s
        INNER JOIN members m ON m.id = s.memberId
        WHERE s.isPaid = 0 AND m.isDeleted = 0
        """
    )
    fun getUnpaidCount(): Flow<Int>

    /**
     * إجمالي الدَّين الحقيقي المتبقي على كل الأعضاء النشطين — لكل اشتراك غير مدفوع بالكامل،
     * الفرق (price - paidAmount) إن كان مدفوعاً جزئياً، أو السعر كاملاً إن لم يُدفع منه شيء
     * بعد. نفس صيغة totalOutstandingByMember أعلاه لكن عبر كل الأعضاء بدل عضو واحد.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN s.paidAmount IS NOT NULL THEN s.price - s.paidAmount ELSE s.price END), 0)
        FROM subscriptions s
        INNER JOIN members m ON m.id = s.memberId
        WHERE s.isPaid = 0 AND m.isDeleted = 0
        """
    )
    fun getUnpaidTotal(): Flow<Double>

    @Query(
        """
        SELECT COUNT(*) FROM members m WHERE m.isDeleted = 0 AND EXISTS (
            SELECT 1 FROM subscriptions s WHERE s.memberId = m.id
            AND s.id = (SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1)
            AND s.startDate <= :now AND s.endDate >= :now AND s.endDate <= :soonThreshold
        )
        """
    )
    fun getExpiringSoonCount(now: Long, soonThreshold: Long): Flow<Int>

    // ---------- نسخ احتياطي / استعادة ----------

    @Query("SELECT * FROM subscriptions")
    suspend fun getAllRaw(): List<SubscriptionEntity>

    /**
     * تُفرِّغ جدول الاشتراكات بالكامل قبل استعادة نسخة احتياطية كاملة. لازمة الآن لأن حذف
     * الأعضاء (memberDao.deleteAllRaw) لم يعد يحذف الاشتراكات تلقائياً عبر CASCADE بعد
     * تغيير onDelete إلى SET_NULL — راجع GymRepository.restoreAll().
     */
    @Query("DELETE FROM subscriptions")
    suspend fun deleteAllRaw()

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(subscriptions: List<SubscriptionEntity>)
}
