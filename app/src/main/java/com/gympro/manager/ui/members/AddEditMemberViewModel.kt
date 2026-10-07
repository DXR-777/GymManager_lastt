package com.gympro.manager.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.SubscriptionType

data class AddEditInitialData(
    val settings: GymSettingsEntity,
    val member: MemberEntity?,
    val currentSubscription: SubscriptionEntity?,
    val outstandingBalance: Double,
    val inheritedMonthlyAnchor: Int?
)

sealed class DuplicateMatch {
    data class Phone(val memberName: String) : DuplicateMatch()
    data class Name(val memberName: String) : DuplicateMatch()
}

data class MemberSaveRequest(
    val memberId: Long,
    val isRenewMode: Boolean,
    val loadedMember: MemberEntity?,
    val loadedSubscription: SubscriptionEntity?,
    val name: String,
    val phoneDigits: String,
    val notes: String,
    val type: SubscriptionType,
    val price: Double,
    val startDate: Long,
    val isPaid: Boolean,
    val customDays: Int,
    val paymentMethod: PaymentMethod,
    val senderName: String?,
    val senderPhone: String?,
    val paidAmount: Double?
)

class AddEditMemberViewModel(private val repository: GymRepository) : ViewModel() {

    private var cached: AddEditInitialData? = null

    suspend fun loadInitial(memberId: Long, isRenewMode: Boolean): AddEditInitialData {
        cached?.let { return it }
        val settings = repository.getSettingsOnce()
        val data = if (memberId == -1L) {
            AddEditInitialData(settings, null, null, 0.0, null)
        } else {
            val member = repository.getMemberOnce(memberId)
            val current = repository.getCurrentSubscription(memberId)
            if (isRenewMode) {
                AddEditInitialData(
                    settings = settings,
                    member = member,
                    currentSubscription = current,
                    outstandingBalance = repository.getMemberStats(memberId).totalOutstanding,
                    inheritedMonthlyAnchor = repository.getInheritedMonthlyAnchor(memberId)
                )
            } else {
                AddEditInitialData(settings, member, current, 0.0, null)
            }
        }
        cached = data
        return data
    }

    suspend fun findDuplicate(name: String, phoneDigits: String, excludeMemberId: Long): DuplicateMatch? {
        repository.findMemberByPhone(phoneDigits, excludeMemberId)?.let { return DuplicateMatch.Phone(it.name) }
        repository.findMemberByName(name, excludeMemberId)?.let { return DuplicateMatch.Name(it.name) }
        return null
    }

    /** يُرجع معرّف العضو الجديد عند الإضافة، وإلا null. */
    suspend fun save(r: MemberSaveRequest): Long? {
        when {
            r.isRenewMode -> repository.addSubscription(
                memberId = r.memberId,
                type = r.type,
                price = r.price,
                startDate = r.startDate,
                isPaid = r.isPaid,
                customDays = r.customDays,
                paymentMethod = r.paymentMethod,
                senderName = r.senderName,
                senderPhone = r.senderPhone,
                paidAmount = r.paidAmount
            )
            r.memberId != -1L -> {
                val member = r.loadedMember
                val subscription = r.loadedSubscription
                if (member != null && subscription != null) {
                    repository.updateMemberAndSubscription(
                        member = member.copy(name = r.name, phone = r.phoneDigits, notes = r.notes),
                        subscriptionOriginal = subscription,
                        type = r.type,
                        price = r.price,
                        startDate = r.startDate,
                        isPaid = r.isPaid,
                        customDays = r.customDays,
                        paymentMethod = r.paymentMethod,
                        senderName = r.senderName,
                        senderPhone = r.senderPhone,
                        paidAmount = r.paidAmount
                    )
                } else if (member != null) {
                    repository.updateMemberProfile(member.copy(name = r.name, phone = r.phoneDigits, notes = r.notes))
                }
            }
            else -> return repository.addMemberWithSubscription(
                name = r.name,
                phone = r.phoneDigits,
                notes = r.notes,
                photoPath = null,
                type = r.type,
                price = r.price,
                startDate = r.startDate,
                isPaid = r.isPaid,
                customDays = r.customDays,
                paymentMethod = r.paymentMethod,
                senderName = r.senderName,
                senderPhone = r.senderPhone,
                paidAmount = r.paidAmount
            )
        }
        return null
    }

    class Factory(private val repository: GymRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AddEditMemberViewModel(repository) as T
    }
}
