package com.gympro.manager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gympro.manager.data.local.dao.MemberDao
import com.gympro.manager.data.local.dao.SettingsDao
import com.gympro.manager.data.local.dao.SubscriptionDao
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity

/**
 * قاعدة بيانات SQLite محلية بالكامل عبر Room.
 * جميع بيانات النادي (الأعضاء، الاشتراكات، الإعدادات) تُخزَّن هنا على الجهاز
 * ولا تُحذف عند إغلاق التطبيق أو إعادة تشغيل الهاتف.
 */
@Database(
    entities = [MemberEntity::class, SubscriptionEntity::class, GymSettingsEntity::class],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun memberDao(): MemberDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DB_NAME = "gym_manager.db"

        /**
         * الترقية 1 → 2: تضيف عمود billingAnchorDay لإصلاح مشكلة انزلاق يوم الفوترة
         * الشهرية (انظر الشرح الكامل في DateUtils.calculateEndDate و SubscriptionEntity).
         *
         * لا تُحذف ولا تُعاد كتابة أي بيانات موجودة — عملية إضافة عمود آمنة تماماً على
         * قاعدة بيانات حقيقية لصاحب جيم يستخدم التطبيق فعلياً. بعد إضافة العمود، نملأ
         * القيمة لكل سجلات الاشتراك الشهري الموجودة مسبقاً بأفضل تقدير متاح (يوم الشهر
         * من startDate الخاص بها)، بدل تركها 0، حتى تعمل عليها منطقية التوريث فوراً.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN billingAnchorDay INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    UPDATE subscriptions
                    SET billingAnchorDay = CAST(strftime('%d', startDate / 1000, 'unixepoch') AS INTEGER)
                    WHERE type = 'MONTHLY'
                    """.trimIndent()
                )
            }
        }

        /**
         * الترقية 2 → 3: تضيف عمود paidAt (تاريخ التحصيل الفعلي للدفعة)، لإصلاح خطأ محاسبي
         * كان يجعل تقارير الإيرادات تُنسب أي دَين قديم يُحصَّل لاحقاً إلى شهر *إنشاء* السجل
         * بدل شهر *تحصيله* الفعلي (راجع الشرح الكامل في SubscriptionEntity.paidAt).
         *
         * لا تُحذف ولا تُعاد كتابة أي بيانات موجودة. لكل اشتراك سابق كان مُعلَّماً بالفعل
         * كمدفوع (isPaid = 1)، لا نملك سجلاً لتاريخ التحصيل الحقيقي لأن الحقل لم يكن موجوداً،
         * لذا نستخدم createdAt كأفضل تقدير متاح — وهذا يحافظ تماماً على أرقام تقارير
         * الإيرادات التاريخية كما هي دون أي تغيير مفاجئ لصاحب الجيم (لا فرق عملياً لأن كل
         * الدفعات القديمة كانت أصلاً تُسجَّل مدفوعة فور الإنشاء قبل وجود شاشة تعديل الدَّين).
         * الاشتراكات غير المدفوعة (isPaid = 0) تبقى paidAt = NULL كما هو متوقع.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN paidAt INTEGER"
                )
                db.execSQL(
                    "UPDATE subscriptions SET paidAt = createdAt WHERE isPaid = 1"
                )
            }
        }

        /**
         * الترقية 3 → 4: تُصلِح خطأً كان يحذف نهائياً كل اشتراكات العضو (بما فيها المدفوعة)
         * عند حذف العضو حذفاً نهائياً، بسبب onDelete = CASCADE على المفتاح الخارجي memberId.
         * كانت الاشتراكات المدفوعة (isPaid = 1) هي المصدر الوحيد لأرقام تقارير الإيرادات
         * (revenueBetween/revenueBetweenByType لا تُصفّي حسب isDeleted عمداً)، فكان حذف عضو
         * يُقلّص إجمالي الإيرادات التاريخية بصمت دون أي أثر أو تنبيه.
         *
         * التغيير: onDelete أصبح SET_NULL بدل CASCADE، وعمود memberId أصبح NULLABLE، بحيث
         * حذف عضو "يفصل" اشتراكاته (memberId = NULL) بدل حذفها. الاشتراكات غير المدفوعة
         * تُحذف صراحة قبل حذف العضو (انظر GymRepository.permanentDeleteMember)، فتبقى نتيجة
         * حذف العضو من ناحية الدَّين مطابقة تماماً للسلوك السابق.
         *
         * SQLite لا يدعم تعديل قيد FK أو NOT NULL على عمود موجود مباشرة عبر ALTER TABLE، لذا
         * نتبع نمط إعادة بناء الجدول الموصى به من Room: إنشاء جدول جديد بالتعريف الصحيح، نسخ
         * كل البيانات الموجودة كما هي دون أي فقدان أو تغيير قيمة، ثم استبدال الجدول القديم.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscriptions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        memberId INTEGER,
                        type TEXT NOT NULL,
                        price REAL NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        isPaid INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        paidAt INTEGER,
                        billingAnchorDay INTEGER NOT NULL,
                        FOREIGN KEY(memberId) REFERENCES members(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO subscriptions_new
                        (id, memberId, type, price, startDate, endDate, isPaid, createdAt, paidAt, billingAnchorDay)
                    SELECT id, memberId, type, price, startDate, endDate, isPaid, createdAt, paidAt, billingAnchorDay
                    FROM subscriptions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE subscriptions")
                db.execSQL("ALTER TABLE subscriptions_new RENAME TO subscriptions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subscriptions_memberId ON subscriptions(memberId)")
            }
        }

        /**
         * الترقية 4 → 5: تحذف عمود restDaysMask (أيام الإجازة الأسبوعية) بعد إزالة هذه
         * الميزة بالكامل من التطبيق. العمود لم يكن يُستخدم في أي حساب فعلي للاشتراكات أو
         * الإيرادات (راجع DateUtils.calculateEndDate)، فحذفه لا يُغيّر أي رقم مالي —
         * فقط يزيل بيانات لم تعد الواجهة تعرضها أو تُحدّثها.
         *
         * SQLite على minSdk 26 لا يدعم بشكل موثوق ALTER TABLE ... DROP COLUMN، لذا نتبع
         * نفس نمط إعادة بناء الجدول المستخدم في MIGRATION_3_4: إنشاء جدول جديد بالتعريف
         * الصحيح (بلا restDaysMask)، نسخ بقية الأعمدة كما هي دون أي فقدان أو تغيير قيمة،
         * ثم استبدال الجدول القديم. صفّ الإعدادات الوحيد (id = 1) يبقى محفوظاً بكل قيمه
         * الأخرى تماماً كما كانت.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gym_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
                        gymName TEXT NOT NULL,
                        dailyPrice REAL NOT NULL,
                        weeklyPrice REAL NOT NULL,
                        monthlyPrice REAL NOT NULL,
                        whatsappPrefix TEXT,
                        currencySymbol TEXT NOT NULL,
                        logoPath TEXT,
                        notifyExpiry INTEGER NOT NULL,
                        notifyUnpaid INTEGER NOT NULL,
                        isSetupComplete INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO gym_settings_new
                        (id, gymName, dailyPrice, weeklyPrice, monthlyPrice, whatsappPrefix, currencySymbol, logoPath, notifyExpiry, notifyUnpaid, isSetupComplete)
                    SELECT id, gymName, dailyPrice, weeklyPrice, monthlyPrice, whatsappPrefix, currencySymbol, logoPath, notifyExpiry, notifyUnpaid, isSetupComplete
                    FROM gym_settings
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE gym_settings")
                db.execSQL("ALTER TABLE gym_settings_new RENAME TO gym_settings")
            }
        }

        /**
         * الترقية 5 → 6: تضيف عمود paymentMethod (طريقة الدفع: PalPay/جوال باي/بنك فلسطين/
         * نقدي) لكل اشتراك — حقل معلوماتي بحت يصف "كيف" دُفع، مستقل تماماً عن isPaid الذي
         * يصف "هل" دُفع (راجع الشرح الكامل في PaymentMethod.kt وSubscriptionEntity.paymentMethod).
         *
         * إضافة عمود آمنة تماماً ولا تُغيّر أي بيانات موجودة أو أي حساب إيراد. كل الاشتراكات
         * الموجودة مسبقاً (أُنشئت قبل وجود هذه الميزة) تحصل تلقائياً على القيمة الافتراضية
         * 'PALPAY' — نفس الافتراضي المستخدم لأي اشتراك جديد، لأن التطبيق موجَّه لسوق غزة حيث
         * المحافظ الإلكترونية أكثر استخداماً من الكاش.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'PALPAY'"
                )
            }
        }

        /**
         * الترقية 6 → 7: تضيف عمودين اختياريين senderName و senderPhone لكل اشتراك — بيانات
         * معلوماتية بحتة عن الشخص الذي أرسل المبلغ فعلياً في حالة الدفع الإلكتروني، مستقلة
         * تماماً عن isPaid وعن paymentMethod ولا تدخل في أي حساب إيراد أو منطق تجديد (راجع
         * الشرح الكامل في SubscriptionEntity.senderName/senderPhone).
         *
         * إضافة عمودين NULLABLE بلا قيمة افتراضية إجبارية — آمنة تماماً ولا تُغيّر أي بيانات
         * موجودة. كل الاشتراكات الموجودة مسبقاً (أُنشئت قبل وجود هذه الميزة) تحصل تلقائياً
         * على NULL لكلا العمودين، تماماً كالقيمة الافتراضية null المستخدمة لأي اشتراك جديد
         * لا يُدخَل له مُرسِل. لا حاجة لإعادة بناء الجدول (بخلاف MIGRATION_3_4/4_5) لأن العمود
         * الجديد هنا لا يغيّر قيداً على عمود موجود (NOT NULL أو FK)، بل يضيف عموداً جديداً
         * فقط — وهي الحالة التي تدعمها SQLite مباشرة عبر ALTER TABLE ADD COLUMN.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN senderName TEXT")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN senderPhone TEXT")
            }
        }

        /**
         * الترقية 7 → 8: تضيف عمود paidAmount (اختياري) لكل اشتراك — أساس ميزة "الدفع
         * الجزئي" الجديدة (شاشة إضافة/تعديل عضو). راجع الشرح الكامل في
         * SubscriptionEntity.paidAmount وPaymentStatus.kt لكيفية اشتقاق الحالة الثلاثية
         * (مدفوع/جزئي/غير مدفوع) من هذا العمود مع isPaid معاً.
         *
         * إضافة عمود NULLABLE بلا قيمة افتراضية إجبارية — آمنة تماماً ولا تُغيّر أي بيانات
         * موجودة أو أي رقم إيراد/دَين محسوب مسبقاً. كل الاشتراكات الموجودة مسبقاً (أُنشئت
         * قبل وجود هذه الميزة) تحصل تلقائياً على NULL، فتبقى حالتها المُشتقة تماماً كما كانت
         * قبل هذه الترقية (PAID إن كان isPaid = 1، أو UNPAID إن كان isPaid = 0) — لا يوجد
         * اشتراك قديم يتحول فجأة إلى PARTIAL دون تدخل صريح من صاحب الجيم.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN paidAmount REAL")
            }
        }

        /**
         * الترقية 8 → 9: تضيف عمودين اختياريين googleAccountEmail وgoogleAccountDisplayName
         * لصفّ الإعدادات الوحيد — نتيجة تفعيل "تسجيل الدخول عبر Google" الفعلي في شاشة
         * "حفظ البيانات على السحابة" ضمن الإعداد الأولي (راجع OnboardingCloudActivity).
         * ⚠️ ميزة Google معلَّقة بانتظار إعداد خارجي من صاحب التطبيق (راجع الملاحظة
         * الكاملة أعلى OnboardingCloudActivity.kt) — هذه الترقية نفسها مكتملة وآمنة
         * ولا علاقة لها بذلك الانتظار، فلا داعي لتأخيرها أو التراجع عنها.
         * حقلان معلوماتيان بحتان لعرض حساب صاحب النادي المرتبط لاحقاً في الإعدادات، لا
         * علاقة لهما بـ isSetupComplete ولا بأي منطق حساب مالي أو اشتراك.
         *
         * إضافة عمودين NULLABLE بلا قيمة افتراضية إجبارية — آمنة تماماً ولا تُغيّر أي بيانات
         * موجودة. كل تثبيت سابق للتطبيق (من قبل وجود هذه الميزة) يحصل تلقائياً على NULL
         * لكلا العمودين، تماماً كصاحب نادٍ اختار "المتابعة كضيف" ولم يربط حساباً. لا حاجة
         * لإعادة بناء الجدول (بخلاف MIGRATION_4_5) لأن العمودين الجديدين لا يغيّران قيداً
         * على عمود موجود، بل يضيفان عمودين فقط — تدعمها SQLite مباشرة عبر ALTER TABLE ADD COLUMN.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gym_settings ADD COLUMN googleAccountEmail TEXT")
                db.execSQL("ALTER TABLE gym_settings ADD COLUMN googleAccountDisplayName TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9
                ).build().also { INSTANCE = it }
            }
    }
}
