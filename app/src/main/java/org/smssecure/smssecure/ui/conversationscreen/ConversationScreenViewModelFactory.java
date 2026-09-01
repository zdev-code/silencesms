package org.smssecure.smssecure.ui.conversationscreen;

import androidx.annotation.NonNull;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.SavedStateHandleSupport;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

public final class ConversationScreenViewModelFactory implements ViewModelProvider.Factory {
  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
    validate(modelClass);
    return (T) new ConversationScreenViewModel(
        new ConversationScreenStateStore(new SavedStateHandle()));
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass,
                                                 @NonNull CreationExtras extras) {
    validate(modelClass);
    return (T) new ConversationScreenViewModel(new ConversationScreenStateStore(
        SavedStateHandleSupport.createSavedStateHandle(extras)));
  }

  private static <T extends ViewModel> void validate(Class<T> modelClass) {
    if (!modelClass.isAssignableFrom(ConversationScreenViewModel.class)) {
      throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
    }
  }
}
