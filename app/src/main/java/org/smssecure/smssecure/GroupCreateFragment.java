package org.smssecure.smssecure;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import org.smssecure.smssecure.components.PushRecipientsPanel;
import org.smssecure.smssecure.contacts.RecipientsEditor;
import org.smssecure.smssecure.database.ThreadDatabase;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.groupcreate.GroupCreateUiState;
import org.smssecure.smssecure.ui.groupcreate.GroupCreateViewModel;
import org.smssecure.smssecure.ui.groupcreate.GroupMember;
import org.smssecure.smssecure.util.SelectedRecipientsAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class GroupCreateFragment extends Fragment {
  private GroupCreateViewModel viewModel;
  private SelectedRecipientsAdapter adapter;
  private GroupCreateUiState.Error renderedError = GroupCreateUiState.Error.NONE;
  private long renderedThreadId = -1;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getParentFragmentManager().setFragmentResultListener(
        PushContactSelectionFragment.REQUEST_KEY, this, (requestKey, result) -> {
          long[] recipientIds = result.getLongArray(PushContactSelectionFragment.RECIPIENT_IDS_KEY);
          getParentFragmentManager().clearFragmentResult(requestKey);
          if (!areValidRecipientIds(recipientIds)) return;
          Recipients recipients = RecipientFactory.getRecipientsForIds(
              requireContext(), recipientIds, false);
          viewModel.addMembers(toMembers(recipients.getRecipientsList()));
        });
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.group_create_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    viewModel = new ViewModelProvider(this).get(GroupCreateViewModel.class);
    adapter = new SelectedRecipientsAdapter(requireContext());
    adapter.setOnRecipientDeletedListener(viewModel::removeMember);

    ListView list = view.findViewById(R.id.selected_contacts_list);
    list.setAdapter(adapter);
    PushRecipientsPanel recipientsPanel = view.findViewById(R.id.recipients);
    recipientsPanel.setPanelChangeListener(new PushRecipientsPanel.RecipientsPanelChangedListener() {
      @Override public void onRecipientsPanelUpdate(Recipients recipients) {
        if (recipients != null) viewModel.addMembers(toMembers(recipients.getRecipientsList()));
      }
    });
    view.findViewById(R.id.contacts_button).setOnClickListener(ignored ->
      NavHostFragment.findNavController(this).navigate(
        ConversationListDestination.PUSH_CONTACT_SELECTION.getId()));
    ((RecipientsEditor) view.findViewById(R.id.recipients_text))
        .setHint(R.string.recipients_panel__add_member);

    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::render);
  }

  void createGroup() {
    viewModel.createGroup();
  }

  void clearSensitiveState() {
    if (viewModel != null) viewModel.clearSensitiveState();
    if (adapter != null) adapter.submitList(List.of());
    renderedError = GroupCreateUiState.Error.NONE;
    renderedThreadId = -1;
  }

  private void render(GroupCreateUiState state) {
    adapter.submitList(state.getMembers());
    if (state.getError() != renderedError && state.getError() != GroupCreateUiState.Error.NONE) {
      int message = state.getError() == GroupCreateUiState.Error.NO_MEMBERS
          ? R.string.GroupCreateActivity_contacts_no_members
          : R.string.GroupCreateActivity_contacts_mms_exception;
      Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
      viewModel.acknowledgeResult();
    }
    renderedError = state.getError();

    if (state.getCreatedThreadId() > -1 && state.getCreatedThreadId() != renderedThreadId) {
      renderedThreadId = state.getCreatedThreadId();
        Bundle arguments = ConversationScreenFragment.arguments(
          state.getCreatedRecipientIds(), state.getCreatedThreadId(),
          ThreadDatabase.DistributionTypes.DEFAULT, false,
          System.currentTimeMillis(), 0L, null);
          androidx.navigation.NavController controller = NavHostFragment.findNavController(this);
          controller.popBackStack();
          controller.navigate(ConversationListDestination.CONVERSATION.getId(), arguments);
      viewModel.acknowledgeResult();
    }
  }

  private static List<GroupMember> toMembers(Collection<Recipient> recipients) {
    List<GroupMember> members = new ArrayList<>();
    for (Recipient recipient : recipients) {
      if (recipient != null && recipient.getRecipientId() > 0) members.add(toMember(recipient));
    }
    return members;
  }

  private static boolean areValidRecipientIds(@Nullable long[] recipientIds) {
    if (recipientIds == null) return false;
    java.util.HashSet<Long> unique = new java.util.HashSet<>();
    for (long recipientId : recipientIds) {
      if (recipientId <= 0 || !unique.add(recipientId)) return false;
    }
    return true;
  }

  private static GroupMember toMember(Recipient recipient) {
    return new GroupMember(recipient.getRecipientId(), recipient.getName(), recipient.getNumber());
  }
}