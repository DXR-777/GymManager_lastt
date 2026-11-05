package com.gympro.manager.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.LruCache
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.gympro.manager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** تحميل صور الأعضاء من مساحة التطبيق الخاصة مع ذاكرة مؤقتة (بلا مكتبات خارجية). */
object MemberPhotoLoader {
    private val cache = object : LruCache<String, Bitmap>(
        maxOf(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun cached(path: String, maxPx: Int): Bitmap? = cache.get("$path|$maxPx")

    fun load(context: Context, path: String, maxPx: Int, onLoaded: (Bitmap?) -> Unit) {
        cached(path, maxPx)?.let { onLoaded(it); return }
        val appContext = context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(appContext, path, maxPx) }
            if (bitmap != null) cache.put("$path|$maxPx", bitmap)
            onLoaded(bitmap)
        }
    }

    private fun decode(context: Context, path: String, maxPx: Int): Bitmap? = runCatching {
        val file = MemberPhotoStore.file(context, path)
        if (!file.exists()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/**
 * يعرض أفاتار العضو داخل TextView الأحرف الأولى نفسه: صورة دائرية فوق الحلقة الأصلية إن وُجدت،
 * وإلا الحرف الأول كالسابق. لا يتطلب تغيير أي تخطيط، ويتحمّل إعادة استخدام العناصر في
 * RecyclerView (يتحقق من أن المسار لم يتغيّر قبل تطبيق الصورة).
 */
fun TextView.bindMemberAvatar(name: String, photoPath: String?, photoMaxPx: Int = 256) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    if (getTag(R.id.tag_avatar_ready) == null) {
        setTag(R.id.tag_avatar_bg, background)
        setTag(R.id.tag_avatar_ready, true)
    }
    val original = getTag(R.id.tag_avatar_bg) as? Drawable
    setTag(R.id.tag_avatar_path, photoPath)

    fun showInitial() {
        background = original
        text = initial
    }

    fun showPhoto(bitmap: Bitmap) {
        val round = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
            isCircular = true
            setAntiAlias(true)
        }
        val inset = (2 * resources.displayMetrics.density).toInt()
        val base = original?.constantState?.newDrawable(resources)?.mutate()
        val layers = if (base != null) arrayOf(base, InsetDrawable(round, inset)) else arrayOf<Drawable>(round)
        background = LayerDrawable(layers)
        text = ""
    }

    if (photoPath.isNullOrBlank()) {
        showInitial()
        return
    }
    val hit = MemberPhotoLoader.cached(photoPath, photoMaxPx)
    if (hit != null) {
        showPhoto(hit)
        return
    }
    showInitial()
    MemberPhotoLoader.load(context, photoPath, photoMaxPx) { bitmap ->
        if (bitmap != null && getTag(R.id.tag_avatar_path) == photoPath) showPhoto(bitmap)
    }
}
