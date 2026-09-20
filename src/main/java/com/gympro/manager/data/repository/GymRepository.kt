package com.gympro.manager.data.repository

import com.gympro.manager.data.local.AppDatabase
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.model.ArchivePurgeCandidate
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.MemberSort
import com.gympro.manager.model.MemberStats
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.model.paymentStatus
import com.gympro.manager.utils.DateUtils
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * نقطة الوصول الوحيدة لكل بيانات التطبيق. تجمع المنطق التجاري
 * (حساب تواريخ الانتهاء، الإيرادات، الحذف الناعم...) في مكان واحد
 * بدل تكراره داخل كل ViewModel.
 */
class GymRepository(private val db: AppDatabase) {

    private val memberDao = db.memberDao()
    private val subscriptionDao = db.subscriptionDao()
    private val settingsDao = db.settingsDao()

    // ---------------------------------------------------------------- إعدادات

    fun observeSettings(): Flow<GymSettingsEntity?> = settingsDao.observe()

    suspend fun getSettingsOnce(): GymSettingsEntity =
        settingsDao.getOnce() ?: GymSettingsEntity()

    suspend fun saveSettings(settings: GymSettingsEntity) = settingsDao.save(settings)

    /**
     * تُستدعى بعد نجاح "تسجيل الدخول عبر Google" في شاشة "حفظ البيانات على
     * السحابة" ضمن الإعداد الأولي. تحافظ على كل حقول الإعدادات الأخرى كما هي
     * (تقرأ الصفّ الحالي أولاً بدل الكتابة فوقه بصفّ افتراضي فارغ) — مهم هنا
     * تحديداً لأن هذه الشاشة تسبق شاشة "اسم النادي/الأسعار" في مسار الإعداد
     * الأولي، فقد لا توجد قيم أخرى بعد، لكن الدالة تبقى آمنة للاستخدام لاحقاً
     * من شاشة الإعدادات لتغيير الحساب المرتبط دون التأثير على أي بيانات نادٍ.
     */
    suspend fun updateGoogleAccount(email: String?, displayName: String?) {
        val current = getSettingsOnce()
        settingsDao.save(
            current.copy(googleAccountEmail = email, googleAccountDisplayName = displayName)
        )
    }

    // ---------------------------------------------------------------- أعضاء

    fun observeActiveMembers(query: String = "") = memberDao.getActiveMembers(query)

    /**
     * راجع البند 24: نسخة مُقسَّمة على صفحات (Paging 3) من observeActiveMembers أعلاه —
     * تُستخدم في MembersViewModel.pagedMembers لعرض قائمة الأعضاء بدون تحميل كل الصفوف
     * دفعة واحدة في الذاكرة، مهم مع نادٍ كبير (600+ عضو). observeActiveMembers نفسها
     * تبقى دون تغيير وتُستخدم كما هي في كل مكان آخر (ExpiryCheckWorker، النسخ الاحتياطي...).
     * pageSize=30 وprefetchDistance=10: يجلب صفحة جديدة قبل وصول المستخدم لنهاية القائمة
     * الحالية بعشرة عناصر تقريباً، فلا يشعر بأي توقّف أثناء التمرير الطبيعي.
     * enablePlaceholders=false: لا حاجة لعرض عناصر فارغة بحجم القائمة الكلي مسبقاً؛
     * القائمة تكبر تدريجياً بمقدار كل صفحة مُحمَّلة فعلياً بدل ذلك.
     */
    fun getActiveMembersPaged(query: String, sort: MemberSort): Flow<PagingData<MemberListItem>> {
        val pagingSourceFactory: () -> PagingSource<Int, MemberListItem> = when (sort) {
            MemberSort.NAME -> { { memberDao.getActiveMembersPagedByName(query) } }
            MemberSort.EXPIRY_SOONEST -> { { memberDao.getActiveMembersPagedByExpiry(query) } }
            MemberSort.NEWEST -> { { memberDao.getActiveMembersPagedByNewest(query) } }
        }
        return Pager(
            config = PagingConfig(pageSize = 30, enablePlaceholders = false, prefetchDistance = 10),
            pagingSourceFactory = pagingSourceFactory
        ).flow
    }

    fun observeDeletedMembers(query: String = "") = memberDao.getDeletedMembers(query)

    fun observeMember(id: Long) = memberDao.getById(id)

    suspend fun getMemberOnce(id: Long) = memberDao.getByIdOnce(id)

    fun observeSubscriptionHistory(memberId: Long) = subscriptionDao.getForMember(memberId)

    suspend fun getCurrentSubscription(memberId: Long) = subscriptionDao.getCurrentForMember(memberId)

    suspend fun getMemberStats(memberId: Long): MemberStats = MemberStats(
        totalMonthsSubscribed = subscriptionDao.countMonthlySubscriptions(memberId),
        totalPaid = subscriptionDao.totalPaidByMember(memberId),
        totalOutstanding = subscriptionDao.totalOutstandingByMember(memberId),
        unpaidSubscriptionsCount = subscriptionDao.countUnpaidByMember(memberId)
    )

    /**
     * يضيف عضواً جديداً مع اشتراكه الأول، ويُعيد رقم العضو الجديد.
     *
     * يُنفَّذ إدخال العضو وإدخال اشتراكه الأول ضمن معاملة واحدة (db.withTransaction)، بنفس
     * النمط المستخدم في permanentDeleteMember و restoreAll. بدون هذا، توقّف العملية بين
     * الإدخالَين (تدمير الـ Activity، تدوير الشاشة، إنهاء العملية من النظام) كان يترك عضواً
     * "شبحاً" بلا اشتراك وبلا إيراد مسجَّل رغم تحصيل المبلغ فعلياً من العميل.
     */
    suspend fun addMemberWithSubscription(
        name: String,
        phone: String,
        notes: String,
        photoPath: String?,
        type: SubscriptionType,
        price: Double,
        startDate: Long,
        isPaid: Boolean,
        customDays: Int = 1,
        paymentMethod: PaymentMethod = PaymentMethod.PALPAY,
        senderName: String? = null,
        senderPhone: String? = null,
        paidAmount: Double? = null
    ): Long = db.withTransaction {
        val memberId = memberDao.insert(
            MemberEntity(name = name, phone = phone, notes = notes, photoPath = photoPath)
        )
        addSubscription(
            memberId, type, price, startDate, isPaid, customDays, paymentMethod, senderName, senderPhone, paidAmount
        )
        memberId
    }

    suspend fun updateMemberProfile(member: MemberEntity) = memberDao.update(member)

    /**
     * يوم الفوترة المرجعي المُورَّث من آخر اشتراك شهري سابق لهذا العضو (إن وُجد)، بلا أي
     * افتراضي بديل — تُرجع null إن لم يكن للعضو اشتراك شهري سابق صالح، ليقرر المستدعي
     * القيمة الاحتياطية المناسبة (عادة يوم startDate الجديد).
     *
     * مُستخرجة كدالة عامة مستقلة (بدل بقائها منطقاً داخلياً في addSubscription فقط) حتى
     * تستطيع شاشة إضافة/تجديد العضو (AddEditMemberActivity) حساب *نفس* معاينة تاريخ
     * الانتهاء المعروضة للموظف قبل الحفظ، بدل معاينة مبنية على افتراض مختلف قد لا يطابق
     * التاريخ الذي يُحفظ فعلياً عند الضغط على "حفظ".
     */
    suspend fun getInheritedMonthlyAnchor(memberId: Long): Int? =
        subscriptionDao.getLastMonthlySubscription(memberId)?.billingAnchorDay?.takeIf { it in 1..31 }

    /**
     * يضيف اشتراكاً جديداً (تجديد) لعضو موجود، ويحسب تاريخ الانتهاء تلقائياً.
     *
     * بالنسبة للاشتراك الشهري تحديداً: "يوم الفوترة المرجعي" (billingAnchorDay) يُورَّث من
     * آخر اشتراك شهري سابق لنفس العضو إن وُجد (عبر [getInheritedMonthlyAnchor])، بدل اشتقاقه
     * من [startDate] في كل مرة. هذا يمنع انزلاق يوم الفوترة تدريجياً عبر الأشهر (مثال: عضو
     * انضم يوم 31 يفقد يوم فوترته الأصلي بشكل دائم لولا هذا التوريث) — التفاصيل الكاملة في
     * DateUtils.calculateEndDate. أول اشتراك شهري للعضو يُثبِّت يوم [startDate] نفسه كمرجع
     * لكل التجديدات القادمة.
     *
     * [customDays]: عدد الأيام المُدخَل يدوياً — ذو معنى فقط عندما type = CUSTOM، ويُمرَّر
     * مباشرة لـ DateUtils.calculateEndDate. لا علاقة له بـ billingAnchorDay (يبقى 0 لأي
     * نوع غير MONTHLY كما كان الحال قبل إضافة CUSTOM).
     */
    suspend fun addSubscription(
        memberId: Long,
        type: SubscriptionType,
        price: Double,
        startDate: Long,
        isPaid: Boolean,
        customDays: Int = 1,
        paymentMethod: PaymentMethod = PaymentMethod.PALPAY,
        senderName: String? = null,
        senderPhone: String? = null,
        // مبلغ الدفع الجزئي (ميزة "الدفع الجزئي" الجديدة) — ذو معنى فقط عندما isPaid = false
        // وقيمته > 0؛ خلاف ذلك يُطبَّع إلى null عبر normalizePaidAmount (راجع الشرح هناك).
        paidAmount: Double? = null
    ): Long {
        val anchorDay = when (type) {
            SubscriptionType.MONTHLY -> getInheritedMonthlyAnchor(memberId) ?: DateUtils.dayOfMonth(startDate)
            else -> 0
        }
        val endDate = DateUtils.calculateEndDate(startDate, type, anchorDay, customDays)
        val normalizedAmount = normalizePaidAmount(isPaid, paidAmount, price)
        return subscriptionDao.insert(
            SubscriptionEntity(
                memberId = memberId,
                type = type,
                price = price,
                startDate = startDate,
                endDate = endDate,
                isPaid = isPaid,
                billingAnchorDay = anchorDay,
                // مدفوع (بالكامل أو جزئياً) فور الإنشاء = تحصيل حقيقي حدث الآن. غير مدفوع
                // إطلاقاً = لا تحصيل بعد. راجع resolvePaidAt للمنطق الموحّد عند التعديل لاحقاً.
                paidAt = if (isPaid || normalizedAmount != null) DateUtils.now() else null,
                paymentMethod = paymentMethod,
                senderName = senderName,
                senderPhone = senderPhone,
                paidAmount = normalizedAmount
            )
        )
    }

    /**
     * تُطبِّع paidAmount المُدخَل من الشاشة إلى القيمة الصحيحة التي يجب تخزينها فعلياً:
     * - isPaid = true → null دائماً (السعر الكامل price يُغني عنه تماماً؛ لا داعٍ لتخزين
     *   مبلغ جزئي بجانب اشتراك مدفوع بالكامل — راجع توثيق SubscriptionEntity.paidAmount).
     * - isPaid = false وقيمة موجبة صالحة (0 < amount) → تُقيَّد بحد أقصى = السعر (لا معنى
     *   لدفع جزئي أكبر من السعر نفسه؛ لو أدخل صاحب الجيم قيمة أكبر خطأً تُخزَّن كحد أقصى).
     * - isPaid = false وبقية الحالات (null أو ≤ 0) → null (يعني UNPAID تماماً، راجع
     *   PaymentStatus.kt).
     */
    private fun normalizePaidAmount(isPaid: Boolean, paidAmount: Double?, price: Double = Double.MAX_VALUE): Double? {
        if (isPaid) return null
        val amount = paidAmount ?: return null
        if (amount <= 0.0) return null
        return amount.coerceAtMost(price)
    }

    suspend fun updateSubscription(subscription: SubscriptionEntity) =
        subscriptionDao.update(subscription)

    /**
     * يحدّث بيانات العضو واشتراكه الحالي معاً ضمن معاملة واحدة (db.withTransaction)، بنفس
     * النمط المستخدم في addMemberWithSubscription و permanentDeleteMember و restoreAll.
     *
     * سابقاً كانت شاشة التعديل (AddEditMemberActivity) تستدعي updateMemberProfile ثم
     * updateSubscriptionDetails كعمليتين منفصلتين غير مُغلَّفتين بمعاملة واحدة. توقّف
     * العملية بينهما (تدمير الـ Activity، إنهاء العملية من النظام) كان يترك تحديث بيانات
     * العضو محفوظاً بينما يُفقَد صمتاً تحديث الاشتراك (السعر/النوع/التاريخ/حالة الدفع)
     * رغم ظهور رسالة "تم الحفظ بنجاح" للمستخدم — بالضبط نفس فئة المشكلة الموثّقة أعلاه
     * في addMemberWithSubscription، لكن في مسار التعديل. هذه الدالة تجمع العمليتين ضمن
     * معاملة واحدة لضمان أن كلا التحديثين يُحفظان معاً أو لا يُحفظ أي منهما.
     *
     * updateMemberProfile و updateSubscriptionDetails تبقيان كما هما (تُستخدمان داخلياً هنا،
     * وتبقيان متاحتين لأي مستدعٍ آخر يحتاج تحديث أحد الجانبين فقط).
     */
    suspend fun updateMemberAndSubscription(
        member: MemberEntity,
        subscriptionOriginal: SubscriptionEntity,
        type: SubscriptionType,
        price: Double,
        startDate: Long,
        isPaid: Boolean,
        customDays: Int = 1,
        paymentMethod: PaymentMethod = subscriptionOriginal.paymentMethod,
        senderName: String? = subscriptionOriginal.senderName,
        senderPhone: String? = subscriptionOriginal.senderPhone,
        paidAmount: Double? = subscriptionOriginal.paidAmount
    ) = db.withTransaction {
        updateMemberProfile(member)
        updateSubscriptionDetails(
            original = subscriptionOriginal,
            type = type,
            price = price,
            startDate = startDate,
            isPaid = isPaid,
            customDays = customDays,
            paymentMethod = paymentMethod,
            senderName = senderName,
            senderPhone = senderPhone,
            paidAmount = paidAmount
        )
    }

    /**
     * يحدّث تفاصيل اشتراك قائم (تصحيح إداري: نوع/سعر/تاريخ بدء/حالة دفع)، مع الحفاظ على
     * id و memberId و createdAt الأصليين، وحساب paidAt بشكل صحيح بحسب [resolvePaidAt].
     * هذا المنطق كان مكرَّراً سابقاً داخل AddEditMemberActivity؛ تجميعه هنا يضمن أن أي شاشة
     * أو مصدر مستقبلي (مزامنة سيرفر، استيراد جماعي) يتبع نفس قاعدة التحصيل الزمنية ولا
     * يعيد اختراعها بشكل مختلف في مكان آخر.
     *
     * [paidAmount]: مبلغ الدفع الجزئي الجديد (ميزة "الدفع الجزئي") — يُطبَّع دائماً عبر
     * normalizePaidAmount قبل الحفظ، بنفس القاعدة المستخدمة في addSubscription تماماً.
     */
    suspend fun updateSubscriptionDetails(
        original: SubscriptionEntity,
        type: SubscriptionType,
        price: Double,
        startDate: Long,
        isPaid: Boolean,
        customDays: Int = 1,
        paymentMethod: PaymentMethod = original.paymentMethod,
        senderName: String? = original.senderName,
        senderPhone: String? = original.senderPhone,
        paidAmount: Double? = original.paidAmount
    ): SubscriptionEntity {
        val anchorDay = if (type == SubscriptionType.MONTHLY) {
            original.billingAnchorDay.takeIf { it in 1..31 } ?: DateUtils.dayOfMonth(startDate)
        } else 0
        val endDate = DateUtils.calculateEndDate(startDate, type, anchorDay, customDays)
        val normalizedAmount = normalizePaidAmount(isPaid, paidAmount, price)
        val updated = original.copy(
            type = type,
            price = price,
            startDate = startDate,
            endDate = endDate,
            isPaid = isPaid,
            billingAnchorDay = anchorDay,
            paidAt = resolvePaidAt(
                newIsPaid = isPaid,
                newPaidAmount = normalizedAmount,
                previousIsPaid = original.isPaid,
                previousPaidAmount = original.paidAmount,
                previousPaidAt = original.paidAt
            ),
            paymentMethod = paymentMethod,
            senderName = senderName,
            senderPhone = senderPhone,
            paidAmount = normalizedAmount
        )
        subscriptionDao.update(updated)
        return updated
    }

    /**
     * "تسديد الدَّين المتبقي الآن" — تُستدعى من زر تسديد الدَّين في الملف الشخصي، سواء كان
     * الاشتراك غير مدفوع بالكامل (UNPAID) أو مدفوعاً جزئياً (PARTIAL): في الحالتين، يُحصَّل
     * كامل المبلغ المتبقي الآن ويصبح الاشتراك PAID بالكامل (paidAmount يُصفَّر لأن السعر
     * الكامل price يُغني عنه، راجع SubscriptionEntity.paidAmount). إن كان الاشتراك PAID
     * أصلاً، تُعيد فتحه كـ UNPAID بالكامل (نفس السلوك الثنائي القديم) — يُستخدم هذا للتراجع
     * عن تصحيح خطأ إدخال، وليس لتحويل اشتراك PAID إلى PARTIAL (ذلك يمرّ عبر
     * updateSubscriptionDetails من شاشة التعديل الكاملة فقط).
     */
    suspend fun togglePaidStatus(subscription: SubscriptionEntity) {
        val newIsPaid = subscription.paymentStatus() != PaymentStatus.PAID
        subscriptionDao.update(
            subscription.copy(
                isPaid = newIsPaid,
                paidAmount = null,
                paidAt = resolvePaidAt(
                    newIsPaid = newIsPaid,
                    newPaidAmount = null,
                    previousIsPaid = subscription.isPaid,
                    previousPaidAmount = subscription.paidAmount,
                    previousPaidAt = subscription.paidAt
                )
            )
        )
    }

    /**
     * القاعدة الموحّدة لحساب paidAt عند أي تغيير في حالة الدفع الثلاثية (مدفوع/جزئي/غير
     * مدفوع)، في مكان واحد فقط:
     * - أصبحت الحالة UNPAID تماماً (newIsPaid = false و newPaidAmount = null) → paidAt = null
     *   لأن لا تحصيل فعلياً قائم الآن.
     * - نفس الحالة تماماً كما كانت (نفس isPaid ونفس paidAmount) — أي تعديل إداري آخر لا
     *   يمسّ حالة الدفع (تصحيح رقم الهاتف مثلاً) → لا نُعيد ضبط تاريخ التحصيل الأصلي (لولا
     *   هذا الاستثناء، كل تصحيح بسيط كان سيُشوِّه تقرير الإيراد الشهري الحقيقي لدفعة حُصِّلت
     *   فعلياً منذ أسابيع).
     * - أي انتقال حقيقي آخر (أصبح مدفوعاً بالكامل الآن، أو أصبح جزئياً الآن، أو تغيّر مبلغ
     *   الدفعة الجزئية نفسه) → لحظة التحصيل الحقيقية (الجديدة) هي الآن.
     */
    private fun resolvePaidAt(
        newIsPaid: Boolean,
        newPaidAmount: Double?,
        previousIsPaid: Boolean,
        previousPaidAmount: Double?,
        previousPaidAt: Long?
    ): Long? = when {
        !newIsPaid && newPaidAmount == null -> null
        previousIsPaid == newIsPaid && previousPaidAmount == newPaidAmount -> previousPaidAt ?: DateUtils.now()
        else -> DateUtils.now()
    }

    suspend fun softDeleteMember(id: Long) =
        memberDao.softDelete(id, DateUtils.now())

    suspend fun restoreMember(id: Long) = memberDao.restore(id)

    /** راجع MemberDao.findActiveByPhone — تحذير التكرار في شاشة إضافة/تعديل عضو. */
    suspend fun findMemberByPhone(phone: String, excludeId: Long = -1L) =
        memberDao.findActiveByPhone(phone, excludeId)

    /** راجع MemberDao.findActiveByName — تحذير التكرار في شاشة إضافة/تعديل عضو. */
    suspend fun findMemberByName(name: String, excludeId: Long = -1L) =
        memberDao.findActiveByName(name, excludeId)

    /**
     * حذف نهائي تلقائي لكل عضو موجود في الأرشيف منذ أكثر من [retentionDays] يوماً — تنفيذ
     * فعلي للوعد الظاهر في رسالة تأكيد الحذف ("يمكنك استعادته خلال 30 يوماً")، والذي كان
     * بلا أي أثر برمجي فعلي سابقاً (الأعضاء المؤرشفون كانوا يبقون للأبد حتى حذف يدوي).
     * يُستدعى دورياً من ArchivePurgeWorker (كل 24 ساعة، بنفس نمط ExpiryCheckWorker).
     * نفس منطق permanentDeleteMember بالضبط لكل عضو مؤهَّل (حذف اشتراكاته غير المدفوعة أولاً
     * ثم حذف صفّه، فتبقى اشتراكاته المدفوعة محسوبة في تقارير الإيرادات عبر onDelete =
     * SET_NULL)، ضمن معاملة واحدة تشمل كل المرشحين معاً.
     */
    suspend fun purgeArchivedOlderThan(retentionDays: Int = ARCHIVE_RETENTION_DAYS) {
        val cutoff = DateUtils.now() - retentionDays.toLong() * 24L * 60L * 60L * 1000L
        val candidateIds = memberDao.getDeletedBefore(cutoff)
        if (candidateIds.isEmpty()) return
        db.withTransaction {
            candidateIds.forEach { id ->
                subscriptionDao.deleteUnpaidForMember(id)
                memberDao.permanentDelete(id)
            }
        }
    }

    /**
     * الأعضاء المؤرشفون الذين سيُحذفون نهائياً خلال [warningWindowDays] القادمة ولم يُحذفوا
     * بعد — راجع ArchivePurgeWorker. يُستدعى قبل تنفيذ purgeArchivedOlderThan أعلاه في نفس
     * الدورة، بنفس صيغة حساب المهلة المستخدمة هناك (وفي ArchiveAdapter) تماماً حتى لا يختلف
     * "المتبقي" المعروض في الإشعار عن المعروض في شاشة الأرشيف.
     */
    suspend fun getArchivePurgeWarningCandidates(
        retentionDays: Int = ARCHIVE_RETENTION_DAYS,
        warningWindowDays: Int = 3
    ): List<ArchivePurgeCandidate> {
        val dayMs = 24L * 60L * 60L * 1000L
        val now = DateUtils.now()
        val purgeCutoff = now - retentionDays.toLong() * dayMs
        val warningCutoff = now - (retentionDays - warningWindowDays).toLong() * dayMs
        return memberDao.getArchivePurgeWarningCandidates(purgeCutoff, warningCutoff)
    }

    /**
     * حذف عضو نهائياً. سابقاً كان onDelete = CASCADE على المفتاح الخارجي يحذف تلقائياً كل
     * اشتراكات العضو بما فيها المدفوعة، فيُقلّص إجمالي الإيرادات التاريخية بصمت (راجع الشرح
     * الكامل في SubscriptionEntity ومهاجرة MIGRATION_3_4). الآن: تُحذف الاشتراكات غير
     * المدفوعة فقط (دَين لا قيمة إيرادية له)، ثم يُحذف صف العضو — فتُفصَل اشتراكاته المدفوعة
     * عنه تلقائياً (memberId = NULL عبر onDelete = SET_NULL) وتبقى محسوبة في كل تقارير
     * الإيرادات كما كانت تماماً. كل ذلك ضمن معاملة واحدة لتفادي حالة وسيطة غير متّسقة.
     */
    suspend fun permanentDeleteMember(id: Long) {
        db.withTransaction {
            subscriptionDao.deleteUnpaidForMember(id)
            memberDao.permanentDelete(id)
        }
    }

    // ---------------------------------------------------------------- لوحة التحكم

    fun observeActiveMembersCount() = memberDao.getActiveMembersCount()

    fun observeUnpaidCount() = subscriptionDao.getUnpaidCount()

    fun observeUnpaidTotal() = subscriptionDao.getUnpaidTotal()

    fun observeExpiringSoonCount(thresholdDays: Int = 3): Flow<Int> {
        val now = DateUtils.now()
        val soon = DateUtils.endOfDay(DateUtils.addDays(now, thresholdDays))
        return subscriptionDao.getExpiringSoonCount(now, soon)
    }

    // ---------------------------------------------------------------- إيرادات

    suspend fun revenueToday(): Double {
        val start = DateUtils.startOfDay(DateUtils.now())
        val end = DateUtils.endOfDay(DateUtils.now())
        return subscriptionDao.revenueBetween(start, end)
    }

    suspend fun revenueThisWeek(): Double =
        subscriptionDao.revenueBetween(DateUtils.startOfWeek(), DateUtils.endOfDay(DateUtils.now()))

    suspend fun revenueThisMonth(): Double =
        subscriptionDao.revenueBetween(DateUtils.startOfMonth(), DateUtils.endOfDay(DateUtils.now()))

    suspend fun revenueThisYear(): Double =
        subscriptionDao.revenueBetween(DateUtils.startOfYear(), DateUtils.endOfDay(DateUtils.now()))

    suspend fun revenueLastMonth(): Double {
        val startThisMonth = DateUtils.startOfMonth()
        val startLastMonth = DateUtils.addMonths(startThisMonth, -1)
        val endLastMonth = DateUtils.addDays(startThisMonth, -1)
        return subscriptionDao.revenueBetween(startLastMonth, DateUtils.endOfDay(endLastMonth))
    }

    /** إيرادات كل يوم من آخر 7 أيام (الأقدم أولاً) */
    suspend fun revenueLast7Days(): List<Double> {
        val today = DateUtils.now()
        return (6 downTo 0).map { offset ->
            val day = DateUtils.addDays(today, -offset)
            subscriptionDao.revenueBetween(DateUtils.startOfDay(day), DateUtils.endOfDay(day))
        }
    }

    suspend fun revenueByType(start: Long, end: Long): Map<SubscriptionType, Double> = mapOf(
        SubscriptionType.DAILY to subscriptionDao.revenueBetweenByType(start, end, SubscriptionType.DAILY.name),
        SubscriptionType.WEEKLY to subscriptionDao.revenueBetweenByType(start, end, SubscriptionType.WEEKLY.name),
        SubscriptionType.MONTHLY to subscriptionDao.revenueBetweenByType(start, end, SubscriptionType.MONTHLY.name),
        SubscriptionType.CUSTOM to subscriptionDao.revenueBetweenByType(start, end, SubscriptionType.CUSTOM.name)
    )

    /**
     * إيراد أي نطاق تاريخ حرّ (شهر سابق محدد أو نطاق مخصص) — راجع البند 23: سابقاً لم
     * يكن مكشوفاً للواجهة سوى فترات ثابتة (اليوم/الأسبوع/الشهر/السنة + مقارنة الشهر
     * الماضي فقط)، رغم أن subscriptionDao.revenueBetween نفسها عامة أصلاً وتدعم أي
     * نطاق. هذه الدالة مجرد تمرير مباشر لنفس الاستعلام الموجود، تُعرِّضه للاستخدام
     * الحر من RevenueViewModel/RevenueFragment بدل حصره في الفترات الثابتة أعلاه فقط.
     */
    suspend fun revenueForRange(start: Long, end: Long): Double = subscriptionDao.revenueBetween(start, end)

    // ---------------------------------------------------------------- نسخ احتياطي / استعادة

    suspend fun getAllMembersRaw(): List<MemberEntity> = memberDao.getAllRaw()

    suspend fun getAllSubscriptionsRaw(): List<SubscriptionEntity> = subscriptionDao.getAllRaw()

    /**
     * يستبدل كامل بيانات التطبيق ببيانات النسخة الاحتياطية ضمن معاملة واحدة (Transaction)
     * بحيث لا يحدث فقدان أو تلف للبيانات في حال انقطاع العملية في منتصف الطريق.
     */
    suspend fun restoreAll(
        members: List<MemberEntity>,
        subscriptions: List<SubscriptionEntity>,
        settings: GymSettingsEntity
    ) {
        db.withTransaction {
            // بعد تغيير onDelete من CASCADE إلى SET_NULL (راجع MIGRATION_3_4)، حذف الأعضاء
            // لم يعد يُفرِّغ جدول الاشتراكات تلقائياً، لذا يجب تفريغه صراحة هنا أيضاً.
            subscriptionDao.deleteAllRaw()
            memberDao.deleteAllRaw()
            memberDao.insertAll(members)
            subscriptionDao.insertAll(subscriptions)
            settingsDao.save(settings)
        }
    }

    companion object {
        /**
         * عدد أيام الاحتفاظ بالعضو في الأرشيف قبل حذفه نهائياً تلقائياً — يجب أن يطابق
         * الرقم "30" المذكور حرفياً في delete_dialog_message (strings.xml) وفي واجهة
         * الأرشيف (ArchiveAdapter). مصدر واحد للرقم بدل تكراره في أكثر من ملف.
         */
        const val ARCHIVE_RETENTION_DAYS = 30

        @Volatile private var INSTANCE: GymRepository? = null

        fun getInstance(db: AppDatabase): GymRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: GymRepository(db).also { INSTANCE = it }
            }
    }
}
