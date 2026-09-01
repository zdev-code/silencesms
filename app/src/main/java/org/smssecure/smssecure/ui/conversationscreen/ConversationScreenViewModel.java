package org.smssecure.smssecure.ui.conversationscreen;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.Objects;

public final class ConversationScreenViewModel extends ViewModel {
  private final ConversationScreenStateStore stateStore;
  private final MutableLiveData<ConversationScreenUiState> state = new MutableLiveData<>();

  private long[] recipientIds = new long[0];
  private long threadId = -1;
  private int distributionType;
  private boolean archived;
  private boolean secureDestination;
  private boolean encryptedConversation;
  private boolean blocked;
  private boolean draftPresent;
  private boolean sendReady;

  ConversationScreenViewModel(ConversationScreenStateStore stateStore) {
    this.stateStore = Objects.requireNonNull(stateStore);
    if (stateStore.hasConversation()) {
      recipientIds = stateStore.getRecipientIds();
      threadId = stateStore.getThreadId();
      distributionType = stateStore.getDistributionType(0);
      archived = stateStore.isArchived();
    }
    publish();
  }

  public LiveData<ConversationScreenUiState> getState() { return state; }

  public void setConversation(long[] recipientIds, long threadId, int distributionType,
                              boolean archived) {
    Objects.requireNonNull(recipientIds);
    if (recipientIds.length == 0) throw new IllegalArgumentException("Recipients are required");
    if (threadId == 0 || threadId < -1) throw new IllegalArgumentException("Invalid thread ID");
    this.recipientIds = recipientIds.clone();
    this.threadId = threadId;
    this.distributionType = distributionType;
    this.archived = archived;
    secureDestination = false;
    encryptedConversation = false;
    blocked = false;
    draftPresent = false;
    sendReady = false;
    publish();
  }

  public void setThreadId(long threadId) {
    if (threadId == 0 || threadId < -1) throw new IllegalArgumentException("Invalid thread ID");
    this.threadId = threadId;
    publish();
  }

  public void setDistributionType(int distributionType) {
    this.distributionType = distributionType;
    publish();
  }

  public void setSecurity(boolean secureDestination, boolean encryptedConversation) {
    this.secureDestination = secureDestination;
    this.encryptedConversation = encryptedConversation;
    publish();
  }

  public void setBlocked(boolean blocked) {
    this.blocked = blocked;
    publish();
  }

  public void setComposeStatus(boolean draftPresent, boolean sendReady) {
    this.draftPresent = draftPresent;
    this.sendReady = sendReady;
    publish();
  }

  private void publish() {
    if (recipientIds.length > 0) {
      stateStore.save(recipientIds, threadId, distributionType, archived);
    }
    state.setValue(new ConversationScreenUiState(recipientIds, threadId, distributionType, archived,
        secureDestination, encryptedConversation, blocked, draftPresent, sendReady));
  }
}
