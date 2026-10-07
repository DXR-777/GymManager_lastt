package com.gympro.manager.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * اختبار ترحيل قاعدة البيانات من كل إصدار قديم (1..8) إلى الحالي.
 *
 * الفكرة: نبني ملف قاعدة بالإصدار القديم بـSQL خام (مخططه مُستنتج من أعمدة كل ترحيل في
 * AppDatabase)، ونزرع بيانات، ثم نفتحه عبر Room بقائمة الترحيلات الحقيقية ALL_MIGRATIONS.
 * عند الفتح يتحقق Room نفسه من أن المخطط الناتج مطابق للكيانات (وإلا ترمي IllegalStateException)،
 * ونتحقق نحن من بقاء البيانات والقيم الافتراضية والمفتاح الأجنبي والفهرس.
 *
 * مهم: مخطط الإصدارات القديمة هنا مُعاد بناؤه من كود الترحيلات (لم تكن ملفات schema مُصدَّرة).
 * منذ الآن يُصدَّر مخطط كل إصدار إلى app/schemas؛ عند إضافة ترحيل جديد (9→10) اكتب اختباره
 * بـMigrationTestHelper (room-testing موجود في build.gradle) مقابل ملف 9.json الحقيقي.
 *
 * يعمل على جهاز/محاكي: ./gradlew connectedDebugAndroidTest
 */
@RunWith(Parameterized::class)
class AppDatabaseMigrationTest(private val fromVersion: Int) {

    companion object {
        private const val DB = "migration_test.db"

        @JvmStatic
        @Parameterized.Parameters(name = "from v{0}")
        fun versions() = (1..8).toList()

        // 2024-01-31T00:00:00Z — يوم 31 ليتأكد backfill يوم الاستحقاق في 1→2.
        private const val START_JAN31 = 1_706_659_200_000L
        private const val END_FEB29 = 1_709_164_800_000L
        private const val CREATED = 1_706_700_000_000L
    }

    private lateinit var context: Context
    private var room: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB)
    }

    @After fun tearDown() {
        room?.close()
        context.deleteDatabase(DB)
    }

    // ───────────────────────── مخططات الإصدارات القديمة ─────────────────────────

    private val membersDdl = """
        CREATE TABLE members (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, phone TEXT NOT NULL, notes TEXT NOT NULL,
            photoPath TEXT, isDeleted INTEGER NOT NULL, deletedAt INTEGER,
            createdAt INTEGER NOT NULL
        )""".trimIndent()

    // gym_settings: نفس الأعمدة حتى v8 (4→5 أعاد بناءه بهذا الشكل؛ قبله الأعمدة نفسها).
    private val settingsDdl = """
        CREATE TABLE gym_settings (
            id INTEGER PRIMARY KEY NOT NULL, gymName TEXT NOT NULL,
            dailyPrice REAL NOT NULL, weeklyPrice REAL NOT NULL, monthlyPrice REAL NOT NULL,
            whatsappPrefix TEXT, currencySymbol TEXT NOT NULL, logoPath TEXT,
            notifyExpiry INTEGER NOT NULL, notifyUnpaid INTEGER NOT NULL,
            isSetupComplete INTEGER NOT NULL
        )""".trimIndent()

    private fun subscriptionsDdl(version: Int): List<String> {
        val base = "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, memberId INTEGER, " +
            "type TEXT NOT NULL, price REAL NOT NULL, startDate INTEGER NOT NULL, " +
            "endDate INTEGER NOT NULL, isPaid INTEGER NOT NULL, createdAt INTEGER NOT NULL"
        return when {
            version == 1 -> listOf("CREATE TABLE subscriptions ($base)")
            version == 2 -> listOf("CREATE TABLE subscriptions ($base, billingAnchorDay INTEGER NOT NULL DEFAULT 0)")
            version == 3 -> listOf(
                "CREATE TABLE subscriptions ($base, billingAnchorDay INTEGER NOT NULL DEFAULT 0, paidAt INTEGER)"
            )
            else -> {
                // v4+: الشكل الذي ينتجه 3→4 (مفتاح أجنبي SET NULL + فهرس) ثم الأعمدة المضافة لاحقاً.
                val extra = buildString {
                    if (version >= 6) append(", paymentMethod TEXT NOT NULL DEFAULT 'PALPAY'")
                    if (version >= 7) append(", senderName TEXT, senderPhone TEXT")
                    if (version >= 8) append(", paidAmount REAL")
                }
                listOf(
                    "CREATE TABLE subscriptions ($base, billingAnchorDay INTEGER NOT NULL DEFAULT 0, " +
                        "paidAt INTEGER$extra, FOREIGN KEY(memberId) REFERENCES members(id) ON DELETE SET NULL)",
                    "CREATE INDEX index_subscriptions_memberId ON subscriptions(memberId)"
                )
            }
        }
    }

    private fun seed(): List<String> = listOf(
        "INSERT INTO members VALUES (1, 'أحمد', '0599123456', '', NULL, 0, NULL, $CREATED)",
        "INSERT INTO members VALUES (2, 'سارة', '0598000000', 'ملاحظة', NULL, 1, $CREATED, $CREATED)",
        "INSERT INTO gym_settings VALUES (1, 'نادي الاختبار', 3.0, 20.0, 60.0, '+970', '₪', NULL, 1, 1, 1)",
        // مشترك مدفوع شهري يبدأ يوم 31، وآخر يومي غير مدفوع.
        "INSERT INTO subscriptions (id, memberId, type, price, startDate, endDate, isPaid, createdAt) " +
            "VALUES (1, 1, 'MONTHLY', 60.0, $START_JAN31, $END_FEB29, 1, $CREATED)",
        "INSERT INTO subscriptions (id, memberId, type, price, startDate, endDate, isPaid, createdAt) " +
            "VALUES (2, 1, 'DAILY', 3.0, $START_JAN31, $START_JAN31, 0, $CREATED)"
    )

    private fun createOldDatabase(version: Int) {
        val statements = listOf(membersDdl, settingsDdl) + subscriptionsDdl(version) + seed()
        val helper = object : SQLiteOpenHelper(context, DB, null, version) {
            override fun onCreate(db: SQLiteDatabase) = statements.forEach(db::execSQL)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close() // ينشئ الملف ويضبط user_version = version
        helper.close()
    }

    private fun migrate(): AppDatabase {
        createOldDatabase(fromVersion)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        room = db
        // الفتح يشغّل الترحيلات ويتحقق Room من مطابقة المخطط للكيانات (يرمي عند أي اختلاف).
        db.openHelper.writableDatabase
        return db
    }

    private fun <T> AppDatabase.scalar(sql: String, read: (android.database.Cursor) -> T): T =
        openHelper.readableDatabase.query(sql).use { c ->
            assertTrue("لا صفوف لـ: $sql", c.moveToFirst())
            read(c)
        }

    // ───────────────────────────────── الاختبارات ─────────────────────────────────

    @Test fun migration_opensWithSchemaMatchingEntities() {
        val db = migrate()
        assertEquals(9, db.openHelper.readableDatabase.version)
    }

    @Test fun migration_preservesMembersAndSettings() {
        val db = migrate()
        assertEquals(2, db.scalar("SELECT COUNT(*) FROM members") { it.getInt(0) })
        assertEquals("أحمد", db.scalar("SELECT name FROM members WHERE id = 1") { it.getString(0) })
        assertEquals(1, db.scalar("SELECT isDeleted FROM members WHERE id = 2") { it.getInt(0) })
        assertEquals("نادي الاختبار", db.scalar("SELECT gymName FROM gym_settings WHERE id = 1") { it.getString(0) })
        assertEquals("₪", db.scalar("SELECT currencySymbol FROM gym_settings") { it.getString(0) })
        // أعمدة جوجل أُضيفت في 8→9: تبدأ NULL ولا تمسّ بقية الإعدادات.
        assertTrue(db.scalar("SELECT googleAccountEmail IS NULL FROM gym_settings") { it.getInt(0) == 1 })
    }

    @Test fun migration_preservesSubscriptionsAndAppliesDefaults() {
        val db = migrate()
        assertEquals(2, db.scalar("SELECT COUNT(*) FROM subscriptions") { it.getInt(0) })
        assertEquals(60.0, db.scalar("SELECT price FROM subscriptions WHERE id = 1") { it.getDouble(0) }, 0.0)
        assertEquals("MONTHLY", db.scalar("SELECT type FROM subscriptions WHERE id = 1") { it.getString(0) })
        assertEquals(START_JAN31, db.scalar("SELECT startDate FROM subscriptions WHERE id = 1") { it.getLong(0) })
        // قيم الأعمدة المضافة لاحقاً: افتراضي PALPAY، وبقية الأعمدة NULL.
        assertEquals("PALPAY", db.scalar("SELECT paymentMethod FROM subscriptions WHERE id = 1") { it.getString(0) })
        assertTrue(db.scalar("SELECT senderName IS NULL AND senderPhone IS NULL AND paidAmount IS NULL FROM subscriptions WHERE id = 1") { it.getInt(0) == 1 })
    }

    @Test fun migration_backfillsBillingAnchorDay_onlyWhenComingFromV1() {
        val db = migrate()
        val monthlyAnchor = db.scalar("SELECT billingAnchorDay FROM subscriptions WHERE id = 1") { it.getInt(0) }
        val dailyAnchor = db.scalar("SELECT billingAnchorDay FROM subscriptions WHERE id = 2") { it.getInt(0) }
        // 1→2 يملأ يوم الاستحقاق (31) للاشتراك الشهري فقط؛ من v2 وما بعد القيمة المزروعة 0 تبقى.
        assertEquals(if (fromVersion == 1) 31 else 0, monthlyAnchor)
        assertEquals(0, dailyAnchor)
    }

    @Test fun migration_backfillsPaidAt_onlyWhenComingFromV1OrV2() {
        val db = migrate()
        val paidAtPaid = db.scalar("SELECT paidAt FROM subscriptions WHERE id = 1") { if (it.isNull(0)) null else it.getLong(0) }
        val paidAtUnpaid = db.scalar("SELECT paidAt FROM subscriptions WHERE id = 2") { if (it.isNull(0)) null else it.getLong(0) }
        // 2→3 ينسخ createdAt إلى paidAt للمدفوع فقط؛ من v3 وما بعد لم تُزرع قيمة فتبقى NULL.
        if (fromVersion <= 2) assertEquals(CREATED, paidAtPaid) else assertNull(paidAtPaid)
        assertNull(paidAtUnpaid)
    }

    @Test fun migration_keepsForeignKeyAndIndexOnSubscriptions() {
        val db = migrate()
        val sqlite = db.openHelper.readableDatabase
        sqlite.query("PRAGMA foreign_key_list(subscriptions)").use { c ->
            assertTrue("المفتاح الأجنبي مفقود", c.moveToFirst())
            assertEquals("members", c.getString(c.getColumnIndexOrThrow("table")))
            assertEquals("memberId", c.getString(c.getColumnIndexOrThrow("from")))
            assertEquals("SET NULL", c.getString(c.getColumnIndexOrThrow("on_delete")))
        }
        sqlite.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_subscriptions_memberId'").use {
            assertTrue("فهرس memberId مفقود", it.moveToFirst())
        }
    }

    @Test fun migration_deletingMember_nullsSubscriptionMemberId() {
        val db = migrate()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("PRAGMA foreign_keys = ON")
        sqlite.execSQL("DELETE FROM members WHERE id = 1")
        assertEquals(2, db.scalar("SELECT COUNT(*) FROM subscriptions") { it.getInt(0) })
        assertEquals(2, db.scalar("SELECT COUNT(*) FROM subscriptions WHERE memberId IS NULL") { it.getInt(0) })
    }
}
