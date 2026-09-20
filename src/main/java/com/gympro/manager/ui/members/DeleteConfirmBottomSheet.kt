package com.gympro.manager.ui.members

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gympro.manager.R
import com.gympro.manager.databinding.BottomSheetDeleteConfirmBinding
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.visibleIf

/**
 * حوار تأكيد حذف العضو. يوضّح بشكل صريح إن كان على العضو مبلغ غير مدفوع
 * أو أيام متبقية من اشتراكه الحالي قبل المتابعة بالحذف.
 *
 * ملاحظة هامة: يجب ألا يحتوي أي Fragment على constructor بمعاملات، لأن
 * FragmentManager قد يُعيد إنشاء الـ Fragment تلقائيًا (بعد قتل العملية
 * في الخلفية على أجهزة الذاكرة المحدودة، أو تغيير الإعدادات) باستخدام
 * constructor بدون معاملات فقط — وإلا يحدث Fragment$InstantiationException.
 * لذلك: تُمرَّر البيانات عبر Bundle arguments (تُستعاد تلقائيًا من قبل
 * FragmentManager)، ويُستخدَم Fragment Result API بدلاً من lambda callback
 * (لا يمكن استعادة الـ lambda بعد قتل العملية).
 */
class DeleteConfirmBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDeleteConfirmBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetDeleteConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val memberName = args.getString(ARG_MEMBER_NAME).orEmpty()
        val outstandingBalance = args.getDouble(ARG_OUTSTANDING_BALANCE, 0.0)
        val currencySymbol = args.getString(ARG_CURRENCY_SYMBOL).orEmpty()
        val daysRemaining = args.getInt(ARG_DAYS_REMAINING, 0)

        binding.tvDeleteMessage.text = getString(R.string.delete_dialog_message, memberName)

        if (outstandingBalance > 0) {
            binding.tvBalanceWarning.text = getString(
                R.string.delete_dialog_balance_warning,
                CurrencyFormatter.format(outstandingBalance, currencySymbol)
            )
        }
        binding.tvBalanceWarning.visibleIf(outstandingBalance > 0)

        if (daysRemaining > 0) {
            binding.tvDaysWarning.text = getString(R.string.delete_dialog_days_warning, daysRemaining)
        }
        binding.tvDaysWarning.visibleIf(daysRemaining > 0)

        binding.btnCancelDelete.setOnClickListener { dismiss() }
        binding.btnConfirmDelete.setOnSingleClickListener {
            setFragmentResult(REQUEST_KEY_DELETE_CONFIRM, bundleOf(RESULT_CONFIRMED to true))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** مفتاح طلب النتيجة عبر Fragment Result API. */
        const val REQUEST_KEY_DELETE_CONFIRM = "request_delete_confirm"
        /** مفتاح القيمة داخل الـ Bundle الناتج (true = المستخدم أكّد الحذف). */
        const val RESULT_CONFIRMED = "result_confirmed"

        private const val ARG_MEMBER_NAME = "arg_member_name"
        private const val ARG_OUTSTANDING_BALANCE = "arg_outstanding_balance"
        private const val ARG_CURRENCY_SYMBOL = "arg_currency_symbol"
        private const val ARG_DAYS_REMAINING = "arg_days_remaining"

        fun newInstance(
            memberName: String,
            outstandingBalance: Double,
            currencySymbol: String,
            daysRemaining: Int
        ): DeleteConfirmBottomSheet = DeleteConfirmBottomSheet().apply {
            arguments = bundleOf(
                ARG_MEMBER_NAME to memberName,
                ARG_OUTSTANDING_BALANCE to outstandingBalance,
                ARG_CURRENCY_SYMBOL to currencySymbol,
                ARG_DAYS_REMAINING to daysRemaining
            )
        }
    }
}
