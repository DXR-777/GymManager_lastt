package com.gympro.manager.utils

import android.animation.ValueAnimator

/**
 * هل الحركة مفعّلة؟ يُرجع false إذا ضبط المستخدم "مقياس مدة الرسوم المتحركة" على صفر
 * (تقليل الحركة) أو كان وضع توفير الطاقة يعطّل الرسوم. كل حركات شاشة تفاصيل العضو تعتمد عليه:
 * عند false تُعرض القيم النهائية فوراً بلا إزاحة ولا عدّ ولا رسم تدريجي.
 */
fun areAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
