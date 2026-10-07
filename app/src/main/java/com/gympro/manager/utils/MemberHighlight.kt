package com.gympro.manager.utils

/**
 * جسر بسيط بين شاشة الإضافة وقائمة الأعضاء: يحمل معرّف آخر عضو أُضيف ليُمرَّر إليه
 * (تمرير + وميض) عند العودة. يُستهلك مرة واحدة، ولا يُحفظ عبر إعادة تشغيل التطبيق.
 */
object MemberHighlight {
    @Volatile
    private var pendingId: Long? = null

    fun set(id: Long) {
        pendingId = id
    }

    fun consume(): Long? = pendingId.also { pendingId = null }
}
