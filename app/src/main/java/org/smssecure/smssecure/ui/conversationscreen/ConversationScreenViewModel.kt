package org.smssecure.smssecure.ui.conversationscreen

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository.MediaSendRequest
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository.TextSendRequest
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle
import javax.inject.Inject

@HiltViewModel
class ConversationScreenViewModel @Inject constructor(
  private val stateStore: ConversationScreenStateStore,
  private val repository: ConversationScreenRepository,
) : ViewModel() {
  private var recipientIds = longArrayOf()
  private var threadId = -1L
  private var distributionType = 0
  private var archived = false
  private var secureDestination = false
  private var encryptedConversation = false
  private var blocked = false
  private var draftPresent = false
  private var sendReady = false
  private var mmsEnabled = true
  private var sending = false
  private var sentThreadId = -1L
  private var error = ConversationScreenUiState.Error.NONE
  private var mmsTask: TaskHandle? = null
  private var sendTask: TaskHandle? = null
  private var mmsGeneration = 0
  private var sendGeneration = 0

  private val mutableState: MutableStateFlow<ConversationScreenUiState>
  val state: StateFlow<ConversationScreenUiState>
    get() = mutableState.asStateFlow()

  init {
    if (stateStore.hasConversation()) {
      recipientIds = stateStore.recipientIds
      threadId = stateStore.threadId
      distributionType = stateStore.getDistributionType(0)
      archived = stateStore.isArchived
    }
    mutableState = MutableStateFlow(createState())
  }

  fun setConversation(
    recipientIds: LongArray,
    threadId: Long,
    distributionType: Int,
    archived: Boolean,
  ) {
    require(recipientIds.isNotEmpty()) { "Recipients are required" }
    require(threadId == -1L || threadId > 0L) { "Invalid thread ID" }
    this.recipientIds = recipientIds.clone()
    this.threadId = threadId
    this.distributionType = distributionType
    this.archived = archived
    secureDestination = false
    encryptedConversation = false
    blocked = false
    draftPresent = false
    sendReady = false
    sendGeneration++
    sendTask = null
    sending = false
    sentThreadId = -1L
    error = ConversationScreenUiState.Error.NONE
    publish()
  }

  fun setThreadId(threadId: Long) {
    require(threadId == -1L || threadId > 0L) { "Invalid thread ID" }
    this.threadId = threadId
    publish()
  }

  fun setDistributionType(distributionType: Int) {
    this.distributionType = distributionType
    publish()
  }

  fun setSecurity(secureDestination: Boolean, encryptedConversation: Boolean) {
    this.secureDestination = secureDestination
    this.encryptedConversation = encryptedConversation
    publish()
  }

  fun setBlocked(blocked: Boolean) {
    this.blocked = blocked
    publish()
  }

  fun setComposeStatus(draftPresent: Boolean, sendReady: Boolean) {
    this.draftPresent = draftPresent
    this.sendReady = sendReady
    publish()
  }

  fun refreshMmsCapability() {
    mmsTask?.cancel()
    val generation = ++mmsGeneration
    mmsTask = repository.loadMmsCapability(object : ConversationScreenRepository.Callback<Boolean> {
      override fun onSuccess(result: Boolean) {
        if (generation != mmsGeneration) return
        mmsTask = null
        mmsEnabled = result
        publish()
      }

      override fun onFailure(exception: Exception) {
        if (generation != mmsGeneration) return
        mmsTask = null
        error = ConversationScreenUiState.Error.MMS_CAPABILITY_FAILED
        publish()
      }
    })
  }

  fun sendText(request: TextSendRequest, capability: ConversationUnlockCapability) {
    startSend(ConversationScreenUiState.Error.TEXT_SEND_FAILED) { callback ->
      repository.sendText(request, capability, callback)
    }
  }

  fun sendMedia(request: MediaSendRequest, capability: ConversationUnlockCapability) {
    startSend(ConversationScreenUiState.Error.MEDIA_SEND_FAILED) { callback ->
      repository.sendMedia(request, capability, callback)
    }
  }

  private fun startSend(
    fallback: ConversationScreenUiState.Error,
    operation: (ConversationScreenRepository.Callback<Long>) -> TaskHandle,
  ) {
    if (sending) return
    sending = true
    sentThreadId = -1L
    error = ConversationScreenUiState.Error.NONE
    publish()
    val generation = ++sendGeneration
    sendTask = operation(object : ConversationScreenRepository.Callback<Long> {
      override fun onSuccess(result: Long) {
        if (generation != sendGeneration) return
        sendTask = null
        sending = false
        sentThreadId = result
        publish()
      }

      override fun onFailure(exception: Exception) {
        if (generation != sendGeneration) return
        sendTask = null
        sending = false
        error = if (exception is ConversationUnlockCapability.LockedException) {
          ConversationScreenUiState.Error.LOCKED
        } else fallback
        publish()
      }
    })
  }

  fun acknowledgeSendResult() {
    if (sentThreadId == -1L) return
    sentThreadId = -1L
    publish()
  }

  fun acknowledgeError() {
    if (error == ConversationScreenUiState.Error.NONE) return
    error = ConversationScreenUiState.Error.NONE
    publish()
  }

  private fun publish() {
    if (recipientIds.isNotEmpty()) {
      stateStore.save(recipientIds, threadId, distributionType, archived)
    }
    mutableState.value = createState()
  }

  private fun createState() = ConversationScreenUiState(
    recipientIds,
    threadId,
    distributionType,
    archived,
    secureDestination,
    encryptedConversation,
    blocked,
    draftPresent,
    sendReady,
    mmsEnabled,
    sending,
    sentThreadId,
    error,
  )

  override fun onCleared() {
    mmsGeneration++
    sendGeneration++
    mmsTask?.cancel()
    sendTask?.cancel()
    mmsTask = null
    sendTask = null
  }
}