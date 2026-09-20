package com.gympro.manager.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * يمثل عضو النادي. لا يحتوي على معلومات الاشتراك الحالي مباشرة؛
 * كل اشتراك (شهري / أسبوعي / يومي) يُخزَّن كسجل منفصل في [SubscriptionEntity]
 * بحيث يبقى السجل التاريخي الكامل لكل عضو متاحاً دائماً.
 */
@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val notes: String = "",
    val photoPath: String? = null,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
