package com.gympro.manager.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** نتيجة استيراد صورة: حُفظت، أو الجهة بلا صورة، أو فشلت القراءة. */
sealed class PhotoImport {
    data class Saved(val path: String) : PhotoImport()
    object NoPhoto : PhotoImport()
    object Failed : PhotoImport()
}

/** نتيجة تجهيز صورة للقصّ: ملف مؤقت جاهز، أو الجهة بلا صورة، أو فشلت القراءة. */
sealed class PhotoStage {
    data class Ready(val path: String) : PhotoStage()
    object NoPhoto : PhotoStage()
    object Failed : PhotoStage()
}

/**
 * تخزين صور الأعضاء داخل مساحة التطبيق الخاصة (filesDir/member_photos) — بلا أي صلاحية.
 *
 * المصادر لا تحتاج READ_MEDIA_IMAGES ولا READ_CONTACTS:
 *  - المعرض: منتقي الصور الرسمي (ActivityResultContracts.PickVisualMedia) يمنح التطبيق صورة
 *    واحدة اختارها المستخدم فقط.
 *  - جهات الاتصال: منتقي جهات النظام (ActivityResultContracts.PickContact) يمنح صلاحية قراءة مؤقتة
 *    لجهة واحدة اختارها المستخدم، ومنها تُقرأ صورتها.
 *
 * كل صورة تُقصّ مربعةً من المنتصف وتُصغَّر (حتى 640px) وتُحفظ JPEG، فتبقى خفيفة وتُعرض دائرية
 * بلا تشوّه. المسار المخزَّن في MemberEntity.photoPath نسبي (member_photos/xxx.jpg).
 */
object MemberPhotoStore {
    private const val DIR = "member_photos"
    private const val MAX_SIDE = 640
    private const val JPEG_QUALITY = 88
    private const val STAGE_SIDE = 1800
    private const val STAGE_DIR = "photo_crop"

    fun file(context: Context, relativePath: String): File = File(context.filesDir, relativePath)

    /** صورة من المعرض (Uri من منتقي الصور). */
    suspend fun importFromUri(context: Context, uri: Uri): PhotoImport = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val orientation = resolver.openInputStream(uri)?.use { readOrientation(it) }
                ?: ExifInterface.ORIENTATION_NORMAL
            val bitmap = decodeScaled { resolver.openInputStream(uri) }
                ?: return@withContext PhotoImport.Failed
            PhotoImport.Saved(writeJpeg(context, finalize(bitmap, orientation)))
        } catch (e: Exception) {
            CrashReporter.record(e)
            PhotoImport.Failed
        }
    }

    /** صورة جهة اتصال اختارها المستخدم من منتقي جهات النظام (Uri الجهة). */
    suspend fun importFromContact(context: Context, contactUri: Uri): PhotoImport = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var bytes: ByteArray? = ContactsContract.Contacts
                .openContactPhotoInputStream(resolver, contactUri, true)
                ?.use { it.readBytes() }
            if (bytes == null) {
                val photoUri = resolver.query(
                    contactUri, arrayOf(ContactsContract.Contacts.PHOTO_URI), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (!photoUri.isNullOrBlank()) {
                    bytes = resolver.openInputStream(Uri.parse(photoUri))?.use { it.readBytes() }
                }
            }
            val data = bytes
            if (data == null || data.isEmpty()) return@withContext PhotoImport.NoPhoto
            val bitmap = decodeScaled { ByteArrayInputStream(data) }
                ?: return@withContext PhotoImport.Failed
            PhotoImport.Saved(writeJpeg(context, finalize(bitmap, ExifInterface.ORIENTATION_NORMAL)))
        } catch (e: Exception) {
            CrashReporter.record(e)
            PhotoImport.Failed
        }
    }

    // ------------------------------------------------------------------ قصّ حر (تجهيز + حفظ)

    /** صورة من المعرض: تُنسخ مدوَّرة بحسب EXIF إلى ملف مؤقت كبير (≤1800px) ليقصّها المستخدم يدوياً. */
    suspend fun stageFromUri(context: Context, uri: Uri): PhotoStage = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val orientation = resolver.openInputStream(uri)?.use { readOrientation(it) }
                ?: ExifInterface.ORIENTATION_NORMAL
            val bitmap = decodeScaled(STAGE_SIDE) { resolver.openInputStream(uri) }
                ?: return@withContext PhotoStage.Failed
            PhotoStage.Ready(writeStage(context, orient(bitmap, orientation)))
        } catch (e: Exception) {
            CrashReporter.record(e)
            PhotoStage.Failed
        }
    }

    /** صورة جهة اتصال اختارها المستخدم: نفس مسار القصّ الحر. */
    suspend fun stageFromContact(context: Context, contactUri: Uri): PhotoStage = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var bytes: ByteArray? = ContactsContract.Contacts
                .openContactPhotoInputStream(resolver, contactUri, true)
                ?.use { it.readBytes() }
            if (bytes == null) {
                val photoUri = resolver.query(
                    contactUri, arrayOf(ContactsContract.Contacts.PHOTO_URI), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (!photoUri.isNullOrBlank()) {
                    bytes = resolver.openInputStream(Uri.parse(photoUri))?.use { it.readBytes() }
                }
            }
            val data = bytes
            if (data == null || data.isEmpty()) return@withContext PhotoStage.NoPhoto
            val bitmap = decodeScaled(STAGE_SIDE) { ByteArrayInputStream(data) }
                ?: return@withContext PhotoStage.Failed
            PhotoStage.Ready(writeStage(context, bitmap))
        } catch (e: Exception) {
            CrashReporter.record(e)
            PhotoStage.Failed
        }
    }

    /** إعادة ضبط صورة محفوظة: تُنسخ إلى ملف مؤقت لتُفتح في شاشة القصّ. */
    suspend fun stageFromStored(context: Context, relativePath: String): PhotoStage = withContext(Dispatchers.IO) {
        try {
            val src = file(context, relativePath)
            if (!src.exists()) return@withContext PhotoStage.Failed
            val dst = File(stageDir(context), "${UUID.randomUUID()}.jpg")
            src.copyTo(dst, overwrite = true)
            PhotoStage.Ready(dst.absolutePath)
        } catch (e: Exception) {
            CrashReporter.record(e)
            PhotoStage.Failed
        }
    }

    fun decodeStaged(absolutePath: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(absolutePath) }.getOrNull()

    /** يحفظ الصورة المقصوصة (مربعة) نهائياً في مجلد الصور ويعيد المسار النسبي. */
    fun saveCropped(context: Context, bitmap: Bitmap): String {
        val square = if (bitmap.width > MAX_SIDE) Bitmap.createScaledBitmap(bitmap, MAX_SIDE, MAX_SIDE, true) else bitmap
        return writeJpeg(context, square)
    }

    private fun stageDir(context: Context): File = File(context.cacheDir, STAGE_DIR).apply { mkdirs() }

    private fun writeStage(context: Context, bitmap: Bitmap): String {
        val out = File(stageDir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return out.absolutePath
    }

    /** تدوير/قلب بحسب EXIF دون أي قصّ. */
    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        return if (matrix.isIdentity) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** يحذف ملف صورة (ضمن مجلد الصور فقط — حماية من أي مسار خارجه). */
    fun delete(context: Context, relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        runCatching {
            val target = file(context, relativePath).canonicalFile
            val base = File(context.filesDir, DIR).canonicalFile
            if (target.path.startsWith(base.path + File.separator)) target.delete()
        }
    }

    private fun decodeScaled(maxSide: Int = MAX_SIDE, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun readOrientation(stream: InputStream): Int =
        runCatching {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** تدوير حسب EXIF ثم قصّ مربع من المنتصف ثم تصغير إلى MAX_SIDE كحدّ أقصى. */
    private fun finalize(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val rotated = if (matrix.isIdentity) source
        else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val side = minOf(rotated.width, rotated.height)
        val square = if (rotated.width == rotated.height) rotated
        else Bitmap.createBitmap(rotated, (rotated.width - side) / 2, (rotated.height - side) / 2, side, side)
        return if (square.width > MAX_SIDE) Bitmap.createScaledBitmap(square, MAX_SIDE, MAX_SIDE, true) else square
    }

    private fun writeJpeg(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.jpg"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return "$DIR/$name"
    }
}
