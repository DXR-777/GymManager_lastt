package com.gympro.manager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.model.ArchivePurgeCandidate
import com.gympro.manager.model.MemberListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(member: MemberEntity): Long

    @Update
    suspend fun update(member: MemberEntity)

    @Query("SELECT * FROM members WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: Long): MemberEntity?

    @Query("SELECT * FROM members WHERE id = :id LIMIT 1")
    fun getById(id: Long): Flow<MemberEntity?>

    @Query("UPDATE members SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long)

    @Query("UPDATE members SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM members WHERE id = :id")
    suspend fun permanentDelete(id: Long)

    /**
     * يجلب كل عضو نشط (غير محذوف) مع آخر اشتراك مسجَّل له (الأحدث بحسب تاريخ الانتهاء).
     * يدعم البحث بالاسم أو رقم الجوال عبر نفس الباراميتر (مرّر سلسلة فارغة لجلب الجميع).
     *
     * outstandingBalance: الدَّين الحقيقي المتراكم على العضو عبر تاريخه الكامل
     * (مجموع price لكل اشتراكاته غير المدفوعة، وليس فقط آخر اشتراك). هذا مختلف عمداً
     * عن isPaid/price اللذين يصفان حالة آخر دورة فقط — انظر التوثيق في MemberListItem.kt.
     */
    @Query(
        """
        SELECT m.id as id, m.name as name, m.phone as phone, m.photoPath as photoPath, m.notes as notes,
               s.type as type, s.startDate as startDate, s.endDate as endDate, s.price as price, s.isPaid as isPaid,
               s.paidAmount as paidAmount,
               s.paymentMethod as paymentMethod,
               s.senderName as senderName, s.senderPhone as senderPhone,
               m.deletedAt as deletedAt,
               (SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
                FROM subscriptions WHERE memberId = m.id AND isPaid = 0) as outstandingBalance
        FROM members m
        LEFT JOIN subscriptions s ON s.id = (
            SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1
        )
        WHERE m.isDeleted = 0
        AND (m.name LIKE '%' || :query || '%' OR m.phone LIKE '%' || :query || '%')
        ORDER BY m.name COLLATE NOCASE ASC
        """
    )
    fun getActiveMembers(query: String = ""): Flow<List<MemberListItem>>

    /**
     * نفس استعلام getActiveMembers أعلاه بالضبط (نفس الإسقاط ونفس شرط WHERE) لكن
     * بإرجاع PagingSource بدل Flow<List<...>> — راجع البند 24: getActiveMembers يحمّل
     * كل الصفوف دفعة واحدة في الذاكرة، ما قد يؤثر على الأداء مع نادٍ كبير (600+ عضو).
     * Room يتولى بنفسه ترجمة هذا إلى استعلامات LIMIT/OFFSET مجزّأة عند الاستخدام عبر
     * Pager (راجع GymRepository.getActiveMembersPaged).
     *
     * ثلاث دوال منفصلة (بالاسم/الأقرب انتهاءً/الأحدث) بدل ORDER BY ديناميكي واحد لأن
     * Room يتطلب استعلام SQL ثابتاً نصياً حرفياً لكل @Query — لا يمكن تمرير عمود/اتجاه
     * الترتيب كباراميتر عادي. الفرز حسب endDate/id مباشرة في SQL آمن هنا (مقارنة رقمية
     * بسيطة بدون أي حساسية لمنطقة زمنية)، بخلاف فلاتر الحالة (نشط/منتهي/قريب الانتهاء)
     * التي تبقى محسوبة في Kotlin عبر DateUtils عمداً (راجع MembersViewModel.pagedMembers)
     * لأنها تعتمد على حساب يوم يولياني مصمَّم خصيصاً لتفادي أخطاء التوقيت الصيفي —
     * إعادة تنفيذها في SQL خطر حقيقي بإعادة نفس أخطاء DST الموثَّقة في DateUtils.
     */
    @Query(
        """
        SELECT m.id as id, m.name as name, m.phone as phone, m.photoPath as photoPath, m.notes as notes,
               s.type as type, s.startDate as startDate, s.endDate as endDate, s.price as price, s.isPaid as isPaid,
               s.paidAmount as paidAmount,
               s.paymentMethod as paymentMethod,
               s.senderName as senderName, s.senderPhone as senderPhone,
               m.deletedAt as deletedAt,
               (SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
                FROM subscriptions WHERE memberId = m.id AND isPaid = 0) as outstandingBalance
        FROM members m
        LEFT JOIN subscriptions s ON s.id = (
            SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1
        )
        WHERE m.isDeleted = 0
        AND (m.name LIKE '%' || :query || '%' OR m.phone LIKE '%' || :query || '%')
        ORDER BY m.name COLLATE NOCASE ASC
        """
    )
    fun getActiveMembersPagedByName(query: String = ""): PagingSource<Int, MemberListItem>

    /** نفس getActiveMembersPagedByName لكن بترتيب "الأقرب انتهاءً" (بلا اشتراك بعد يُدفع لآخر القائمة). */
    @Query(
        """
        SELECT m.id as id, m.name as name, m.phone as phone, m.photoPath as photoPath, m.notes as notes,
               s.type as type, s.startDate as startDate, s.endDate as endDate, s.price as price, s.isPaid as isPaid,
               s.paidAmount as paidAmount,
               s.paymentMethod as paymentMethod,
               s.senderName as senderName, s.senderPhone as senderPhone,
               m.deletedAt as deletedAt,
               (SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
                FROM subscriptions WHERE memberId = m.id AND isPaid = 0) as outstandingBalance
        FROM members m
        LEFT JOIN subscriptions s ON s.id = (
            SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1
        )
        WHERE m.isDeleted = 0
        AND (m.name LIKE '%' || :query || '%' OR m.phone LIKE '%' || :query || '%')
        ORDER BY (s.endDate IS NULL) ASC, s.endDate ASC
        """
    )
    fun getActiveMembersPagedByExpiry(query: String = ""): PagingSource<Int, MemberListItem>

    /** نفس getActiveMembersPagedByName لكن بترتيب "الأحدث" (id تنازلياً). */
    @Query(
        """
        SELECT m.id as id, m.name as name, m.phone as phone, m.photoPath as photoPath, m.notes as notes,
               s.type as type, s.startDate as startDate, s.endDate as endDate, s.price as price, s.isPaid as isPaid,
               s.paidAmount as paidAmount,
               s.paymentMethod as paymentMethod,
               s.senderName as senderName, s.senderPhone as senderPhone,
               m.deletedAt as deletedAt,
               (SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
                FROM subscriptions WHERE memberId = m.id AND isPaid = 0) as outstandingBalance
        FROM members m
        LEFT JOIN subscriptions s ON s.id = (
            SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1
        )
        WHERE m.isDeleted = 0
        AND (m.name LIKE '%' || :query || '%' OR m.phone LIKE '%' || :query || '%')
        ORDER BY m.id DESC
        """
    )
    fun getActiveMembersPagedByNewest(query: String = ""): PagingSource<Int, MemberListItem>

    /**
     * يجلب الأعضاء المؤرشفين (المحذوفين) مع دعم البحث بالاسم أو رقم الجوال — نفس آلية
     * getActiveMembers، لتفادي الفجوة السابقة: شاشة الأعضاء لديها بحث بينما شاشة الأرشيف
     * لم يكن فيها أي بحث إطلاقاً، فيصعب إيجاد عضو محدد لو تراكم عشرات المحذوفين
     * (راجع البند 15). مرّر سلسلة فارغة لجلب الجميع كما في السابق.
     */
    @Query(
        """
        SELECT m.id as id, m.name as name, m.phone as phone, m.photoPath as photoPath, m.notes as notes,
               s.type as type, s.startDate as startDate, s.endDate as endDate, s.price as price, s.isPaid as isPaid,
               s.paidAmount as paidAmount,
               s.paymentMethod as paymentMethod,
               s.senderName as senderName, s.senderPhone as senderPhone,
               m.deletedAt as deletedAt,
               (SELECT COALESCE(SUM(CASE WHEN paidAmount IS NOT NULL THEN price - paidAmount ELSE price END), 0)
                FROM subscriptions WHERE memberId = m.id AND isPaid = 0) as outstandingBalance
        FROM members m
        LEFT JOIN subscriptions s ON s.id = (
            SELECT id FROM subscriptions WHERE memberId = m.id ORDER BY endDate DESC LIMIT 1
        )
        WHERE m.isDeleted = 1
        AND (m.name LIKE '%' || :query || '%' OR m.phone LIKE '%' || :query || '%')
        ORDER BY m.deletedAt DESC
        """
    )
    fun getDeletedMembers(query: String = ""): Flow<List<MemberListItem>>

    @Query("SELECT COUNT(*) FROM members WHERE isDeleted = 0")
    fun getActiveMembersCount(): Flow<Int>

    /**
     * يبحث عن عضو نشط آخر (غير محذوف، وبمعرِّف مختلف عن [excludeId]) يحمل نفس رقم الجوال
     * تماماً — يُستخدم في شاشة إضافة/تعديل عضو لتحذير الموظف قبل الحفظ إن كان هذا الرقم
     * مسجَّلاً بالفعل لعضو آخر (خطأ شائع: كتابة رقم عضو موجود بالغلط بدل عضو جديد).
     * excludeId = -1 (قيمة لا تطابق أي id حقيقي أبداً لأن المعرّفات تبدأ من 1) في وضع
     * الإضافة، أو معرّف العضو الحالي نفسه في وضع التعديل حتى لا يُبلَّغ العضو أنه مكرَّر
     * لنفسه.
     */
    @Query("SELECT * FROM members WHERE isDeleted = 0 AND phone = :phone AND id != :excludeId LIMIT 1")
    suspend fun findActiveByPhone(phone: String, excludeId: Long = -1L): MemberEntity?

    /** نفس مبدأ findActiveByPhone أعلاه، لكن بحسب الاسم (بدون حساسية لحالة الأحرف). */
    @Query("SELECT * FROM members WHERE isDeleted = 0 AND name = :name COLLATE NOCASE AND id != :excludeId LIMIT 1")
    suspend fun findActiveByName(name: String, excludeId: Long = -1L): MemberEntity?

    /** معرِّفات الأعضاء المؤرشفين منذ ما قبل [cutoff] — لتنفيذ الحذف النهائي التلقائي بعد مهلة الاستعادة. */
    @Query("SELECT id FROM members WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getDeletedBefore(cutoff: Long): List<Long>

    /**
     * الأعضاء المؤرشفون الذين اقتربوا من موعد الحذف النهائي التلقائي لكن لم يصلوه بعد —
     * أي deletedAt أحدث من [purgeCutoff] (لم يُحذف بعد) لكن أقدم من [warningCutoff] (تبقّى
     * أقل من نافذة التحذير). راجع ArchivePurgeWorker: قبل هذا الاستعلام لم يكن هناك أي وسيلة
     * لتنبيه صاحب النادي قبل أن يفقد بيانات العضو نهائياً وبلا رجعة.
     */
    @Query(
        """
        SELECT id, name, deletedAt FROM members
        WHERE isDeleted = 1 AND deletedAt IS NOT NULL
        AND deletedAt >= :purgeCutoff AND deletedAt < :warningCutoff
        """
    )
    suspend fun getArchivePurgeWarningCandidates(
        purgeCutoff: Long,
        warningCutoff: Long
    ): List<ArchivePurgeCandidate>

    // ---------- نسخ احتياطي / استعادة ----------

    @Query("SELECT * FROM members")
    suspend fun getAllRaw(): List<MemberEntity>

    @Query("DELETE FROM members")
    suspend fun deleteAllRaw()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<MemberEntity>)
}
