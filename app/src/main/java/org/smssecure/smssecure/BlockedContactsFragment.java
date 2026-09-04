package org.smssecure.smssecure;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.ListFragment;
import androidx.lifecycle.ViewModelProvider;

import org.smssecure.smssecure.preferences.BlockedContactListItem;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.blocked.BlockedContactEntry;
import org.smssecure.smssecure.ui.blocked.BlockedContactsViewModel;

import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class BlockedContactsFragment extends ListFragment
    implements ListView.OnItemClickListener {
  private BlockedContactAdapter adapter;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    return inflater.inflate(R.layout.blocked_contacts_fragment, container, false);
  }

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    adapter = new BlockedContactAdapter(requireContext());
    setListAdapter(adapter);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle bundle) {
    super.onViewCreated(view, bundle);
    getListView().setOnItemClickListener(this);
    BlockedContactsViewModel viewModel = new ViewModelProvider(this).get(BlockedContactsViewModel.class);
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), adapter::submitList);
  }

  @Override
  public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
    Recipients recipients = adapter.getItem(position);
    ((ConversationScreenHost) requireActivity()).openRecipientPreferences(recipients.getIds());
  }

  private static final class BlockedContactAdapter extends BaseAdapter {
    private final Context context;
    private List<Recipients> recipients = List.of();

    private BlockedContactAdapter(Context context) {
      this.context = context;
    }

    private void submitList(List<BlockedContactEntry> entries) {
      List<Recipients> next = new ArrayList<>(entries.size());
      for (BlockedContactEntry entry : entries) {
        next.add(RecipientFactory.getRecipientsForIds(context, entry.getRecipientIds(), true));
      }
      recipients = List.copyOf(next);
      notifyDataSetChanged();
    }

    @Override public int getCount() { return recipients.size(); }
    @Override public Recipients getItem(int position) { return recipients.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View view = convertView != null ? convertView :
          LayoutInflater.from(context).inflate(R.layout.blocked_contact_list_item, parent, false);
      ((BlockedContactListItem) view).set(getItem(position));
      return view;
    }
  }
}