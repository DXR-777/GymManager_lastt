package com.gympro.manager.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.gympro.manager.R

/**
 * Chip بخلفية متدرّجة (crim → vio بزاوية 135°) عند التحديد، كزر الفلترة المضغوط في تصميم HTML
 * (.chips button[aria-pressed=true]). ChipDrawable لا يدعم التدرّج، فنرسمه يدوياً تحت النص
 * بينما تكون خلفية الـ Chip الأصلية شفافة في حالة التحديد (انظر color/chip_home_bg).
 */
class GradientChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.chipStyle
) : Chip(context, attrs, defStyleAttr) {

    private val gradient = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            ContextCompat.getColor(context, R.color.home_crim),
            ContextCompat.getColor(context, R.color.home_vio)
        )
    ).apply { shape = GradientDrawable.RECTANGLE }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (isChecked && width > 0 && height > 0) {
            gradient.setBounds(0, 0, width, height)
            gradient.cornerRadius = height / 2f
            gradient.draw(canvas)
        }
        super.onDraw(canvas)
    }
}
