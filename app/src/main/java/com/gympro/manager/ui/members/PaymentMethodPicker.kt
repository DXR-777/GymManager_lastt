package com.gympro.manager.ui.members

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.utils.normalizeDigitsToLatin

/**
 * منتقي طريقة الدفع وبيانات المُرسِل (include_payment_picker.xml) — مشترك بين ورقة "تسجيل دفعة"
 * وورقة "تجديد الاشتراك" كي تبقى القواعد واحدة: لا اختيار افتراضي، النقد بلا مُرسِل،
 * "نفس العضو" أو "شخص آخر" (اسم ≥ 2 ورقم أرقام فقط ≥ 9).
 */
class PaymentMethodPicker(
    private val root: View,
    private val member: MemberEntity,
    private val onChanged: () -> Unit
) {
    var selected: PaymentMethod? = null
        private set
    private var senderOther = false

    private val ctx = root.context
    private val cards = mapOf(
        PaymentMethod.PALPAY to Triple(R.id.payCardPalpay, R.id.palpayCheck, R.id.palpayLabel),
        PaymentMethod.JAWWAL_PAY to Triple(R.id.payCardJawwal, R.id.jawwalCheck, R.id.jawwalLabel),
        PaymentMethod.BANK_OF_PALESTINE to Triple(R.id.payCardBank, R.id.bankCheck, R.id.bankLabel),
        PaymentMethod.CASH to Triple(R.id.payCardCash, R.id.cashCheck, R.id.cashLabel)
    )
    private val senderSection = root.findViewById<View>(R.id.paySenderSection)
    private val senderFields = root.findViewById<View>(R.id.paySenderFields)
    private val sameBtn = root.findViewById<View>(R.id.paySenderSame)
    private val otherBtn = root.findViewById<View>(R.id.paySenderOther)
    private val sameRadio = root.findViewById<View>(R.id.paySenderSameRadio)
    private val otherRadio = root.findViewById<View>(R.id.paySenderOtherRadio)
    private val nameErr = root.findViewById<TextView>(R.id.tvPayNameError)
    private val phoneErr = root.findViewById<TextView>(R.id.tvPayPhoneError)
    private val etName = root.findViewById<EditText>(R.id.etPaySenderName)
    private val etPhone = root.findViewById<EditText>(R.id.etPaySenderPhone)

    init {
        root.findViewById<TextView>(R.id.paySenderSameSub).text = "${member.name} · ${member.phone}"
        cards.forEach { (m, ids) ->
            root.findViewById<View>(ids.first).setOnClickListener { selected = m; render(); onChanged() }
        }
        sameBtn.setOnClickListener { senderOther = false; render(); onChanged() }
        otherBtn.setOnClickListener { senderOther = true; render(); onChanged() }
        etName.doAfterTextChanged { render(); onChanged() }
        etPhone.doAfterTextChanged { render(); onChanged() }
        render()
    }

    private fun nameValid() = etName.text.toString().trim().length >= 2
    private fun phoneValid(): Boolean {
        val d = etPhone.text.toString().trim().normalizeDigitsToLatin()
        return d.isNotEmpty() && d.all { it.isDigit() } && d.length >= 9
    }

    fun isValid(): Boolean {
        val m = selected ?: return false
        if (m == PaymentMethod.CASH || !senderOther) return true
        return nameValid() && phoneValid()
    }

    fun senderName(): String? = when {
        selected == null || selected == PaymentMethod.CASH -> null
        senderOther -> etName.text.toString().trim()
        else -> member.name
    }

    fun senderPhone(): String? = when {
        selected == null || selected == PaymentMethod.CASH -> null
        senderOther -> etPhone.text.toString().trim().normalizeDigitsToLatin()
        else -> member.phone
    }

    /** تعبئة مسبقة (يبقى التعديل ممكناً) — تُستدعى قبل أي تفاعل. */
    fun prefill(method: PaymentMethod, name: String?, phone: String?) {
        selected = method
        val sn = name?.trim().orEmpty()
        val sp = phone?.trim().orEmpty()
        if (method != PaymentMethod.CASH && sn.isNotEmpty() && sp.isNotEmpty() &&
            !(sn == member.name.trim() && sp == member.phone.trim())
        ) {
            senderOther = true
            etName.setText(sn)
            etPhone.setText(sp)
        }
        render()
    }

    /** يُظهر/يُخفي المنتقي كاملاً (مثلاً عند اختيار "دفع لاحقاً" في التجديد). */
    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun render() {
        cards.forEach { (m, ids) ->
            val on = m == selected
            root.findViewById<View>(ids.first).isSelected = on
            root.findViewById<View>(ids.second).visibility = if (on) View.VISIBLE else View.GONE
            root.findViewById<TextView>(ids.third)
                .setTextColor(ContextCompat.getColor(ctx, if (on) R.color.home_ink else R.color.home_mute))
        }
        val needsSender = selected != null && selected != PaymentMethod.CASH
        if (needsSender && senderSection.visibility != View.VISIBLE) {
            senderSection.alpha = 0f
            senderSection.visibility = View.VISIBLE
            senderSection.animate().alpha(1f).setDuration(220).start()
        } else if (!needsSender) {
            senderSection.visibility = View.GONE
        }
        sameBtn.isSelected = !senderOther
        otherBtn.isSelected = senderOther
        sameRadio.isSelected = !senderOther
        otherRadio.isSelected = senderOther
        if (senderOther && senderFields.visibility != View.VISIBLE) {
            senderFields.alpha = 0f
            senderFields.visibility = View.VISIBLE
            senderFields.animate().alpha(1f).setDuration(200).start()
        } else if (!senderOther) {
            senderFields.visibility = View.GONE
        }
        val nameBad = !etName.text.isNullOrBlank() && !nameValid()
        nameErr.text = ctx.getString(R.string.member_sender_name_error_invalid)
        nameErr.visibility = if (nameBad) View.VISIBLE else View.GONE
        val phoneBad = etPhone.text.length >= 9 && !phoneValid()
        phoneErr.text = ctx.getString(R.string.member_sender_phone_error_invalid)
        phoneErr.visibility = if (phoneBad) View.VISIBLE else View.GONE
    }
}
