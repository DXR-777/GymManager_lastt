package com.gympro.manager.ui.members

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityPhotoCropBinding
import com.gympro.manager.utils.AddMemberMotion
import com.gympro.manager.utils.MemberPhotoStore
import com.gympro.manager.utils.applySystemBarInsetsPadding
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ضبط صورة العضو قبل حفظها: تحريك/تكبير/تدوير داخل نافذة دائرية. تستقبل مسار صورة مؤقتة
 * (EXTRA_INPUT_PATH) وتعيد مسار الصورة المقصوصة النسبي (EXTRA_RESULT_PATH) بعد الحفظ في مساحة
 * التطبيق الخاصة. الملف المؤقت يُحذف عند الإغلاق دائماً.
 */
class PhotoCropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoCropBinding
    private var inputPath: String? = null
    private var syncingSlider = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.cropContent.applySystemBarInsetsPadding()
        inputPath = intent.getStringExtra(EXTRA_INPUT_PATH)
        if (inputPath == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        binding.btnCropBack.setOnSingleClickListener { cancel() }
        binding.btnCropCancel.setOnSingleClickListener { cancel() }
        binding.btnCropRotate.setOnSingleClickListener { binding.cropView.rotate90() }
        binding.btnCropReset.setOnSingleClickListener { binding.cropView.reset() }
        binding.btnCropConfirm.setOnSingleClickListener { confirm() }
        onBackPressedDispatcher.addCallback(this) { cancel() }

        binding.sliderZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !syncingSlider) binding.cropView.zoomTo(value)
        }
        binding.cropView.onZoomChanged = { zoom ->
            syncingSlider = true
            binding.sliderZoom.value = zoom.coerceIn(1f, 6f)
            syncingSlider = false
        }

        setControlsEnabled(false)
        lifecycleScope.launch {
            val path = inputPath!!
            val bitmap = withContext(Dispatchers.IO) { MemberPhotoStore.decodeStaged(path) }
            binding.progressCrop.visibility = android.view.View.GONE
            if (bitmap == null) {
                toast(getString(R.string.crop_failed))
                cancel()
            } else {
                binding.cropView.setBitmap(bitmap)
                setControlsEnabled(true)
                if (savedInstanceState == null) AddMemberMotion.staggerIn(binding.cropControls, 3)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(binding.btnCropRotate, binding.btnCropReset, binding.btnCropConfirm, binding.sliderZoom)
            .forEach { it.isEnabled = enabled }
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun confirm() {
        setControlsEnabled(false)
        val cropped = binding.cropView.crop(640)
        if (cropped == null) {
            cancel()
            return
        }
        binding.progressCrop.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { MemberPhotoStore.saveCropped(this@PhotoCropActivity, cropped) }.getOrNull()
            }
            if (saved == null) {
                toast(getString(R.string.crop_failed))
                binding.progressCrop.visibility = android.view.View.GONE
                setControlsEnabled(true)
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, saved))
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) inputPath?.let { runCatching { File(it).delete() } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INPUT_PATH = "crop_input_path"
        const val EXTRA_RESULT_PATH = "crop_result_path"
    }
}
