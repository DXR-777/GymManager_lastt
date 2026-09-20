package com.gympro.manager.data.local

import androidx.room.TypeConverter
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.SubscriptionType

/** محوّلات Room لتخزين الأنواع المخصصة كنص داخل قاعدة بيانات SQLite. */
class Converters {

    @TypeConverter
    fun fromSubscriptionType(type: SubscriptionType): String = type.name

    @TypeConverter
    fun toSubscriptionType(value: String): SubscriptionType = SubscriptionType.fromString(value)

    @TypeConverter
    fun fromPaymentMethod(method: PaymentMethod): String = method.name

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod = PaymentMethod.fromString(value)
}
