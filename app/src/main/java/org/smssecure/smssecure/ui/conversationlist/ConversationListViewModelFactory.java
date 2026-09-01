package org.smssecure.smssecure.ui.conversationlist;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.viewmodel.CreationExtras;

import org.smssecure.smssecure.injection.AppDependencies;

import java.util.Objects;

public final class ConversationListViewModelFactory implements ViewModelProvider.Factory {
  private final AppDependencies dependencies;
  private final boolean         archived;

  public ConversationListViewModelFactory(AppDependencies dependencies, boolean archived) {
    this.dependencies = Objects.requireNonNull(dependencies);
    this.archived     = archived;
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
    if (!modelClass.isAssignableFrom(ConversationListViewModel.class)) {
      throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
    }
    return (T) new ConversationListViewModel(dependencies.conversationRepository(),
      dependencies.sendSelectedDrafts(),
      dependencies.conversationListReminderPolicy(),
      new ConversationListStateStore(new SavedStateHandle(), archived));
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass,
                                                 @NonNull CreationExtras extras) {
    if (!modelClass.isAssignableFrom(ConversationListViewModel.class)) {
      throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
    }
    SavedStateHandle handle = SavedStateHandleSupport.createSavedStateHandle(extras);
    return (T) new ConversationListViewModel(dependencies.conversationRepository(),
      dependencies.sendSelectedDrafts(), dependencies.conversationListReminderPolicy(),
      new ConversationListStateStore(handle, archived));
  }
}