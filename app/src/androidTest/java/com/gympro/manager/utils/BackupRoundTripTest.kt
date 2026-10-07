package com.gympro.manager.utils

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gympro.manager.data.local.AppDatabase
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.SubscriptionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var source: GymRepository
    private lateinit var target: GymRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        source = GymRepository(sourceDb)
        target = GymRepository(targetDb)
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        File(context.getExternalFilesDir(null), "GymManagerBackup").deleteRecursively()
    }

    private suspend fun seed() {
        source.saveSettings(
            GymSettingsEntity(
                gymName = "نادي الاختبار",
                dailyPrice = 5.0,
                weeklyPrice = 25.5,
                monthlyPrice = 80.0,
                currencySymbol = "$",
                isSetupComplete = true
            )
        )
        val start = DateUtils.startOfDay(DateUtils.now())
        source.addMemberWithSubscription(
            name = "أحمد", phone = "0599000001", notes = "ملاحظة", photoPath = null,
            type = SubscriptionType.MONTHLY, price = 80.0, startDate = start, isPaid = true,
            paymentMethod = PaymentMethod.PALPAY, senderName = "مرسل", senderPhone = "0599111222"
        )
        source.addMemberWithSubscription(
            name = "سارة", phone = "0599000002", notes = "", photoPath = null,
            type = SubscriptionType.WEEKLY, price = 25.5, startDate = start, isPaid = false
        )
        source.addMemberWithSubscription(
            name = "خالد", phone = "0599000003", notes = "", photoPath = null,
            type = SubscriptionType.CUSTOM, price = 40.0, startDate = start, isPaid = false,
            customDays = 10, paidAmount = 15.0
        )
    }

    private fun backupDir() = File(context.getExternalFilesDir(null), "GymManagerBackup")

    private suspend fun assertTargetMatchesSource() {
        assertEquals(source.getAllMembersRaw().sortedBy { it.id }, target.getAllMembersRaw().sortedBy { it.id })
        assertEquals(
            source.getAllSubscriptionsRaw().sortedBy { it.id },
            target.getAllSubscriptionsRaw().sortedBy { it.id }
        )
        val a = source.getSettingsOnce()
        val b = target.getSettingsOnce()
        assertEquals(a.gymName, b.gymName)
        assertEquals(a.dailyPrice, b.dailyPrice, 0.0)
        assertEquals(a.weeklyPrice, b.weeklyPrice, 0.0)
        assertEquals(a.monthlyPrice, b.monthlyPrice, 0.0)
        assertEquals(a.currencySymbol, b.currencySymbol)
    }

    @Test
    fun encryptedBackup_restoresEverything() = runBlocking {
        val file = BackupManager.createBackupFile(context, source, "pass-1234".toCharArray())
        assertTrue(file.extension == "gymbak")
        BackupManager.restoreFromUri(context, Uri.fromFile(file), target, "pass-1234".toCharArray())
        assertTargetMatchesSource()
    }

    @Test
    fun plainBackup_restoresEverything() = runBlocking {
        val file = BackupManager.createBackupFile(context, source, null)
        assertTrue(file.extension == "json")
        BackupManager.restoreFromUri(context, Uri.fromFile(file), target, null)
        assertTargetMatchesSource()
    }

    @Test
    fun encryptedBackup_withoutPassword_isRejected_andTargetUntouched() = runBlocking {
        val file = BackupManager.createBackupFile(context, source, "pass-1234".toCharArray())
        try {
            BackupManager.restoreFromUri(context, Uri.fromFile(file), target, null)
            fail()
        } catch (e: BackupPasswordRequiredException) {
        }
        assertTrue(target.getAllMembersRaw().isEmpty())
    }

    @Test
    fun encryptedBackup_wrongPassword_isRejected_andTargetUntouched() = runBlocking {
        val file = BackupManager.createBackupFile(context, source, "pass-1234".toCharArray())
        try {
            BackupManager.restoreFromUri(context, Uri.fromFile(file), target, "other-pass".toCharArray())
            fail()
        } catch (e: BackupWrongPasswordException) {
        }
        assertTrue(target.getAllMembersRaw().isEmpty())
    }

    @Test
    fun corruptFile_failsWithoutTouchingExistingData() = runBlocking {
        val before = source.getAllMembersRaw()
        val bad = File(context.cacheDir, "corrupt.json").apply { writeText("{ not json") }
        try {
            BackupManager.restoreFromUri(context, Uri.fromFile(bad), source, null)
            fail()
        } catch (e: Exception) {
        }
        assertEquals(before, source.getAllMembersRaw())
    }

    @Test
    fun encryptedBackup_removesPlainJsonBackups() = runBlocking {
        val plain = BackupManager.createBackupFile(context, source, null)
        assertTrue(plain.exists())
        BackupManager.createBackupFile(context, source, "pass-1234".toCharArray())
        assertFalse(plain.exists())
        assertTrue(backupDir().listFiles()!!.any { it.extension == "gymbak" })
    }

    @Test
    fun restore_overwritesExistingTargetData() = runBlocking {
        target.addMemberWithSubscription(
            name = "قديم", phone = "0590000000", notes = "", photoPath = null,
            type = SubscriptionType.DAILY, price = 5.0, startDate = DateUtils.now(), isPaid = true
        )
        val file = BackupManager.createBackupFile(context, source, "pass-1234".toCharArray())
        BackupManager.restoreFromUri(context, Uri.fromFile(file), target, "pass-1234".toCharArray())
        assertTargetMatchesSource()
    }

    @Test
    fun vault_roundTrip_andRejectsGarbage() {
        val blob = SecretVault.encrypt("سر-123".toCharArray())
        assertEquals("سر-123", String(SecretVault.decrypt(blob)!!))
        assertEquals(null, SecretVault.decrypt("AAAA"))
    }
}
