package com.gympro.manager.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * إعدادات النادي. صفّ واحد ثابت (id = 1) يُحدَّث دائماً ولا يُحذف أو يُكرَّر.
 */
@Entity(tableName = "gym_settings")
data class GymSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val gymName: String = "",
    val dailyPrice: Double = 3.0,
    val weeklyPrice: Double = 20.0,
    val monthlyPrice: Double = 60.0,
    val whatsappPrefix: String? = null,
    val currencySymbol: String = "₪",
    val logoPath: String? = null,
    val notifyExpiry: Boolean = true,
    val notifyUnpaid: Boolean = true,
    val isSetupComplete: Boolean = false,
    /** البريد الإلكتروني لحساب Google المرتبط (من شاشة "حفظ البيانات على السحابة"
     *  في الإعداد الأولي). null يعني أن صاحب النادي اختار "المتابعة كضيف" ولم
     *  يربط حساباً — لا علاقة له بـ isSetupComplete ولا يمنع استخدام التطبيق. */
    val googleAccountEmail: String? = null,
    /** الاسم المعروض لحساب Google المرتبط، لعرضه في الإعدادات لاحقاً بدل البريد
     *  الخام فقط. null إن لم يوجد حساب مرتبط أو لم يوفّره حساب Google نفسه. */
    val googleAccountDisplayName: String? = null
)
