package com.gympro.manager.ui.widgets

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * البند 8 (شبكة 4 أعمدة على الشاشات الكبيرة/الأفقي): يضيف فجوة أفقية متساوية بين أعمدة
 * الشبكة، بإضافة نصفها كحشوة على كل عمود (فيتساوى الفراغ بين كل بطاقتين، بما فيه الحافتين).
 * المسافة الرأسية بين الصفوف تبقى كما كانت أصلاً عبر layout_marginBottom في item_member.xml.
 */
class GridSpacingItemDecoration(private val spanCount: Int, private val horizontalSpacingPx: Int) :
    RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (spanCount <= 1) return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount
        outRect.left = horizontalSpacingPx - column * horizontalSpacingPx / spanCount
        outRect.right = (column + 1) * horizontalSpacingPx / spanCount
    }
}
