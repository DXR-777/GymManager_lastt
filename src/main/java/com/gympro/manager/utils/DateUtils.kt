package com.gympro.manager.utils

import com.gympro.manager.model.SubscriptionType
import java.util.Calendar

/**
 * كل العمليات الحسابية المتعلقة بالتواريخ والاشتراكات.
 * نستخدم Calendar اليدوي بدل SimpleDateFormat لضمان أرقام غربية ثابتة (0-9)
 * وأسماء أشهر عربية صريحة، بعيداً عن اختلاف locale الجهاز.
 */
object DateUtils {

    private val ARABIC_MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    fun now(): Long = System.currentTimeMillis()

    fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun addDays(millis: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.add(Calendar.DAY_OF_MONTH, days)
        return cal.timeInMillis
    }

    fun addMonths(millis: Long, months: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    /**
     * عدد الأيام الكاملة الفاصلة بين تاريخين (يتم تجاهل الوقت ضمن اليوم).
     *
     * لماذا لا نطرح ملي ثانية ونقسم على DAY_MS؟
     * لأن الفارق بين "بداية يوم" و"بداية يوم آخر" بالساعات الفعلية على الساعة الحائطية
     * ليس ثابتاً دائماً عند 24 ساعة: في يوم انتقال التوقيت الصيفي "spring forward" يكون
     * الفارق 23 ساعة فقط، وفي "fall back" يكون 25 ساعة. أي قسمة على 24×60×60×1000 ثابتة
     * تُنتج خطأ ±يوم كامل (بسبب الاقتطاع truncation) عندما يمتد المدى عبر أحد هذين اليومين
     * — وهو ما يجعل daysRemaining/isExpiringSoon/isExpired تُخطئ في العد مرتين سنوياً
     * تقريباً في أي منطقة زمنية تطبّق DST.
     *
     * الحل: نتجاهل الوقت (ملي ثانية) بالكامل ونحسب الفرق اعتماداً فقط على مكوّنات
     * التاريخ التقويمية (سنة/شهر/يوم) عبر رقم اليوم اليولياني (Julian Day Number).
     * هذا الرقم لا علاقة له بالمنطقة الزمنية أو DST إطلاقاً، فالنتيجة صحيحة دائماً.
     */
    fun daysBetween(fromMillis: Long, toMillis: Long): Int {
        val fromCal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        val toCal = Calendar.getInstance().apply { timeInMillis = toMillis }
        return (julianDayNumber(toCal) - julianDayNumber(fromCal)).toInt()
    }

    /**
     * يحوّل تاريخ تقويمي (سنة/شهر/يوم فقط، بلا وقت) إلى رقم اليوم اليولياني، باستخدام
     * صيغة Fliegel & Van Flandern القياسية للتقويم الغريغوري. هذا الرقم يزيد بمقدار 1
     * بالضبط عن كل يوم تقويمي يمر، بغض النظر عن أي انتقال DST أو تغيّر في المنطقة الزمنية.
     */
    private fun julianDayNumber(cal: Calendar): Long {
        val year = cal.get(Calendar.YEAR).toLong()
        val month = (cal.get(Calendar.MONTH) + 1).toLong() // Calendar.MONTH يبدأ من صفر
        val day = cal.get(Calendar.DAY_OF_MONTH).toLong()

        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3

        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    /** يوم الشهر (1-31) لتاريخ معيّن — يُستخدم لاشتقاق يوم الفوترة المرجعي للاشتراك الشهري. */
    fun dayOfMonth(millis: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * يحسب تاريخ نهاية الاشتراك بحسب نوعه. أيام الإجازة لا تُخصم من المدة الشهرية أو الأسبوعية
     * (يُدفع عنها كاملة)، بينما الاشتراك اليومي يساوي تاريخ بدايته (يوم واحد فقط).
     *
     * [billingAnchorDay]: يوم الشهر (1-31) الذي يُفترض أن يتجدد فيه اشتراك هذا العضو —
     * عادة يوم انضمامه الأصلي. مطلوب فقط لنوع MONTHLY؛ إن تُرك 0، يُستخدم يوم [startDate]
     * نفسه كافتراضي (توافق مع الإصدارات الأقدم).
     *
     * لماذا لا نكتفي بـ "startDate + شهر - يوم"؟
     * لأن Calendar.add(MONTH) في جافا "يُثَبِّت" يوم الشهر عند الانتقال لشهر أقصر
     * (مثال: 31 يناير + شهر = 28 فبراير، وليس 3 مارس). لو اعتُمد هذا الناتج كأساس للتجديد
     * التالي، يصبح يوم فوترة العضو الفعلي 27/28 بدل 31 بشكل *دائم*، لأن كل تجديد لاحق يُعاد
     * حسابه من تاريخ انتهاء منزلق، لا من يوم الانضمام الحقيقي. هذه المشكلة لا تظهر إلا بعد
     * شهرين أو ثلاثة من الاستخدام الفعلي.
     *
     * الحل: نحسب النهاية دائماً استناداً إلى [billingAnchorDay] الثابت، ونحاول كل شهر
     * الوصول لهذا اليوم بالذات؛ إن كان الشهر الهدف أقصر (كفبراير)، نتوقف عند آخر يوم متاح
     * فيه لتلك الدورة فقط، ونعود تلقائياً لليوم الأصلي في أول شهر طويل بما يكفي بعدها —
     * بلا أي انزلاق تراكمي.
     *
     * إصلاح إضافي (اشتراك مقصور بصمت عند التجديد المتأخر أو عند التجديد في آخر يوم بالضبط):
     * "يوم انتهاء الدورة" أعلاه يُحسب دائماً كـ"يوم الفوترة المرجعي في الشهر الذي يلي شهر
     * startDate مباشرة". هذا صحيح تماماً طالما يوم startDate <= يوم الفوترة المرجعي (الحالة
     * الطبيعية: تجديد في الموعد أو قبله). لكن لو تجدَّد العضو بعد أن يكون يوم الشهر الحالي
     * قد تجاوز يوم الفوترة المرجعي بالفعل (تجديد متأخر بعد انتهاء الاشتراك بأيام، أو تجديد في
     * نفس اليوم الأخير من الدورة السابقة)، فإن "يوم الفوترة المرجعي في الشهر التالي" يصبح
     * أقرب من شهر كامل فعلي — وقد يقع في نفس يوم البدء أو بعده بيوم واحد فقط، فيُنتَج اشتراك
     * شهري بمدة يوم أو بضعة أيام فقط رغم دفع السعر الشهري الكامل. هذه المشكلة لا تظهر إلا مع
     * عضو حقيقي يتأخر عن موعد التجديد الثابت (سلوك شائع تماماً في الاستخدام الفعلي)، ولا
     * تظهر أبداً في اختبار التجديد "في الموعد" الذي يعمل بشكل صحيح دائماً.
     *
     * الحل: نحسب أيضاً "شهراً كاملاً فعلياً" من startDate نفسه (بنفس منطق تثبيت Calendar.add
     * القياسي)، ونأخذ الأبعد زمنياً بين المرشحين. في الحالة الطبيعية (يوم البدء <= يوم
     * الفوترة المرجعي) يكون مرشح يوم الفوترة المرجعي هو الأبعد أو مطابقاً، فلا يتغيّر أي شيء
     * عن السلوك السابق. في حالة التجديد المتأخر فقط، يضمن هذا أن العضو يحصل دائماً على شهر
     * كامل فعلي على الأقل مقابل السعر الشهري، مع العودة ليوم الفوترة المرجعي الثابت في أول
     * دورة لاحقة تُجدَّد في موعدها.
     *
     * [customDays]: عدد الأيام المُدخَل يدوياً — ذو معنى فقط عندما type = CUSTOM (اشتراك
     * "مدة مخصصة"). يُفترض دائماً >= 1 عند هذه النقطة (التحقق من كونه رقماً صحيحاً موجباً
     * يتم في واجهة الإدخال قبل الوصول لهذه الدالة)؛ قيمة افتراضية آمنة (1) هنا فقط لتفادي
     * انهيار في حال استُدعيت الدالة مباشرة بقيمة غير صالحة بالخطأ، فلا تُنتج أبداً مدة صفر
     * أو سالبة.
     */
    fun calculateEndDate(
        startDate: Long,
        type: SubscriptionType,
        billingAnchorDay: Int = 0,
        customDays: Int = 1
    ): Long = when (type) {
        SubscriptionType.DAILY -> endOfDay(startDate)
        SubscriptionType.WEEKLY -> endOfDay(addDays(startDate, 6))
        // EndDate = StartDate + (عدد الأيام المُدخَل - 1)، بنفس منطق الأسبوعي أعلاه تماماً
        // (تغطية يوم البدء نفسه ضمن العدّ، لا يوم إضافي بعده).
        SubscriptionType.CUSTOM -> endOfDay(addDays(startDate, customDays.coerceAtLeast(1) - 1))
        SubscriptionType.MONTHLY -> {
            val cal = Calendar.getInstance()
            cal.timeInMillis = startDate
            val anchor = if (billingAnchorDay in 1..31) billingAnchorDay else cal.get(Calendar.DAY_OF_MONTH)

            // المرشح الأول: يوم الفوترة المرجعي في الشهر الذي يلي شهر البدء مباشرة.
            // نُثبّت اليوم على 1 قبل الانتقال للشهر التالي، لتفادي أي تأثير جانبي لتثبيت
            // Calendar الداخلي، فننتقل بدقة لأول يوم من الشهر التالي كنقطة انطلاق.
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, 1)
            val targetMonthLength = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val targetDay = minOf(anchor, targetMonthLength)
            cal.set(Calendar.DAY_OF_MONTH, targetDay)
            val anchorCandidate = startOfDay(cal.timeInMillis)

            // المرشح الثاني: شهر كامل فعلي بدءاً من تاريخ البدء نفسه (نفس منطق تثبيت يوم
            // Calendar.add(MONTH) القياسي، بلا أي اعتماد على يوم الفوترة المرجعي).
            val plainCal = Calendar.getInstance()
            plainCal.timeInMillis = startDate
            plainCal.add(Calendar.MONTH, 1)
            val plainCandidate = startOfDay(plainCal.timeInMillis)

            // نأخذ الأبعد زمنياً بين المرشحين، فلا يقل طول الدورة أبداً عن شهر كامل فعلي،
            // مع الحفاظ على العودة ليوم الفوترة المرجعي الثابت كلما كان ذلك لا يُقصِّر الدورة.
            val nextCycleStart = maxOf(anchorCandidate, plainCandidate)
            endOfDay(addDays(nextCycleStart, -1))
        }
    }

    /** أيام متبقية حتى انتهاء الاشتراك. سالب = منتهي منذ كم يوم. صفر = ينتهي اليوم. */
    fun daysRemaining(endDate: Long, now: Long = now()): Int = daysBetween(now, endDate)

    fun isExpiringSoon(endDate: Long, thresholdDays: Int = 3, now: Long = now()): Boolean {
        val remaining = daysRemaining(endDate, now)
        return remaining in 0..thresholdDays
    }

    fun isExpired(endDate: Long, now: Long = now()): Boolean = daysRemaining(endDate, now) < 0

    /**
     * هل تاريخ بدء الاشتراك لم يصل بعد؟ (اشتراك بتاريخ مستقبلي لم يبدأ فعلياً اليوم).
     *
     * نعتمد نفس منطق daysBetween المبني على رقم اليوم اليولياني (بدل طرح ملي ثانية
     * مباشرة)، لنفس السبب الموثَّق أعلاه على daysBetween: طرح الميلي ثانية يُخطئ بمقدار
     * يوم كامل حول أيام انتقال DST. فاشتراك يبدأ "اليوم" (نفس اليوم التقويمي لـ [now])
     * لا يُعتبر مستقبلياً — يُعتبر مستقبلياً فقط إذا كان يومه التقويمي لاحقاً فعلياً ليوم اليوم.
     */
    fun isFuture(startDate: Long, now: Long = now()): Boolean = daysBetween(now, startDate) > 0

    /**
     * هل الاشتراك نشط فعلياً عند اللحظة [now]؟ أي: بدأ فعلاً (ليس مستقبلياً) ولم ينتهِ بعد.
     * تُبنى فوق [isFuture] و[isExpired] الموجودتين أصلاً بدل مقارنة مباشرة جديدة، لضمان
     * نفس منطق المقارنة القائم على اليوم التقويمي (لا الميلي ثانية) في كل مكان بالتطبيق.
     */
    fun isActive(startDate: Long, endDate: Long, now: Long = now()): Boolean =
        !isFuture(startDate, now) && !isExpired(endDate, now)

    /** تنسيق عربي للتاريخ، مثل: 21 يونيو 2026 */
    fun formatDate(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = ARABIC_MONTHS[cal.get(Calendar.MONTH)]
        val year = cal.get(Calendar.YEAR)
        return "$day $month $year"
    }

    fun formatDateShort(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)
        return "%02d/%02d/%04d".format(day, month, year)
    }

    fun startOfWeek(millis: Long = now()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        return startOfDay(cal.timeInMillis)
    }

    fun startOfMonth(millis: Long = now()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(cal.timeInMillis)
    }

    fun startOfYear(millis: Long = now()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(cal.timeInMillis)
    }
}
