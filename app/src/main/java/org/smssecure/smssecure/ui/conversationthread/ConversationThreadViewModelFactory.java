package org.smssecure.smssecure.ui.conversationthread;

import androidx.annotation.NonNull;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;

import java.util.Objects;

public final class ConversationThreadViewModelFactory implements ViewModelProvider.Factory {
  private final ConversationThreadRepository repository;
  private final long threadId;
  private final long lastSeen;

  public ConversationThreadViewModelFactory(ConversationThreadRepository repository,
                                            long threadId, long lastSeen) {
    this.repository = Objects.requireNonNull(repository);
    this.threadId = threadId;
    this.lastSeen = lastSeen;
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
    validate(modelClass);
    return (T) new ConversationThreadViewModel(repository,
        new ConversationThreadStateStore(new SavedStateHandle(), threadId, lastSeen));
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass,
                                                 @NonNull CreationExtras extras) {
    validate(modelClass);
    return (T) new ConversationThreadViewModel(repository,
        new ConversationThreadStateStore(SavedStateHandleSupport.createSavedStateHandle(extras),
                                         threadId, lastSeen));
  }

  private static <T extends ViewModel> void validate(Class<T> modelClass) {
    if (!modelClass.isAssignableFrom(ConversationThreadViewModel.class)) {
      throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
    }
  }
}
