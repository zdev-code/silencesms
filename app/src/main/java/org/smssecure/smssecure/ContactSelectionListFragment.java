/**
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure;

import android.Manifest;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.smssecure.smssecure.components.RecyclerViewFastScroller;
import org.smssecure.smssecure.contacts.ContactSelectionListAdapter;
import org.smssecure.smssecure.contacts.ContactSelectionListItem;
import org.smssecure.smssecure.permissions.Permissions;
import org.smssecure.smssecure.ui.LifecycleStateCollector;
import org.smssecure.smssecure.ui.contact.ContactSelectionUiState;
import org.smssecure.smssecure.ui.contact.ContactSelectionViewModel;
import org.smssecure.smssecure.util.StickyHeaderDecoration;
import org.smssecure.smssecure.util.ViewUtil;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Fragment for selecting a one or more contacts from a list.
 *
 * @author Moxie Marlinspike
 *
 */
@AndroidEntryPoint
public class ContactSelectionListFragment extends Fragment {
  private static final String TAG = ContactSelectionListFragment.class.getSimpleName();

  private final Permissions.FragmentPermissionLauncher permissionLauncher = Permissions.registerForResult(this);

  private TextView emptyText;

  private Map<Long, String>         selectedContacts;
  private OnContactSelectedListener onContactSelectedListener;
  private View                      showContactsLayout;
  private Button                    showContactsButton;
  private TextView                  showContactsDescription;
  private String                    cursorFilter;
  private RecyclerView              recyclerView;
  private RecyclerViewFastScroller  fastScroller;

  private ContactSelectionListAdapter adapter;
  private ContactSelectionViewModel viewModel;
  private boolean                   multi = false;
  private StickyHeaderDecoration    decoration;

  @Override
  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    viewModel = new ViewModelProvider(this).get(ContactSelectionViewModel.class);
    LifecycleStateCollector.collect(getViewLifecycleOwner(), viewModel.getState(), this::render);
    initializeCursor();
  }

  @Override
  public void onStart() {
    super.onStart();
    Log.w(TAG, "onStart()");

    Permissions.with(this, permissionLauncher)
               .request(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
               .ifNecessary()
               .onAllGranted(() -> handleContactPermissionGranted())
               .onAnyDenied(() -> {
                 initializeNoContactsPermission();
               })
               .execute();
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.contact_selection_list_fragment, container, false);

    emptyText               = ViewUtil.findById(view, android.R.id.empty);
    recyclerView            = ViewUtil.findById(view, R.id.recycler_view);
    fastScroller            = ViewUtil.findById(view, R.id.fast_scroller);
    showContactsLayout      = view.findViewById(R.id.show_contacts_container);
    showContactsButton      = view.findViewById(R.id.show_contacts_button);
    showContactsDescription = view.findViewById(R.id.show_contacts_description);
    recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));

    return view;
  }

  public List<String> getSelectedContacts() {
    if (selectedContacts == null) return null;

    List<String> selected = new LinkedList<>();
    selected.addAll(selectedContacts.values());

    return selected;
  }

  public void setMultiSelect(boolean multi) {
    if (this.multi == multi) return;
    this.multi = multi;
    if (recyclerView != null) initializeCursor();
  }

  private void initializeCursor() {
    adapter = new ContactSelectionListAdapter(getActivity(), new ListClickListener(), multi);
    selectedContacts = adapter.getSelectedContacts();
    recyclerView.setAdapter(adapter);
    if (decoration != null) recyclerView.removeItemDecoration(decoration);
    decoration = new StickyHeaderDecoration(adapter, true, true);
    recyclerView.addItemDecoration(decoration);
  }

  private void initializeNoContactsPermission() {
    getActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    emptyText.setVisibility(View.GONE);
    showContactsLayout.setVisibility(View.VISIBLE);
    showContactsDescription.setText(R.string.contact_selection_list_fragment__silence_needs_access_to_your_contacts_in_order_to_display_them);
    showContactsButton.setVisibility(View.VISIBLE);

    showContactsButton.setOnClickListener(v -> {
      Permissions.with(this, permissionLauncher)
                 .request(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
                 .ifNecessary()
                 .withPermanentDenialDialog(getString(R.string.ContactSelectionListFragment_silence_requires_the_contacts_permission_in_order_to_display_your_contacts))
                 .onSomeGranted(permissions -> {
                   if (permissions.contains(Manifest.permission.WRITE_CONTACTS)) {
                     handleContactPermissionGranted();
                   }
                 })
                 .execute();
    });
  }

  public void setQueryFilter(String filter) {
    if (viewModel != null) viewModel.setFilter(filter);
  }

  public void clearSensitiveState() {
    if (viewModel != null) viewModel.clearSensitiveState();
    if (selectedContacts != null) selectedContacts.clear();
    if (adapter != null) adapter.submitList(List.of());
    onContactSelectedListener = null;
  }

  private void render(ContactSelectionUiState state) {
    if (state.isLoading()) return;
    showContactsLayout.setVisibility(View.GONE);
    adapter.submitList(state.getContacts());
    emptyText.setText(R.string.contact_selection_group_activity__no_contacts);
    boolean useFastScroller = adapter.getItemCount() > 20;
    emptyText.setVisibility(adapter.getItemCount() > 1 ? View.GONE : View.VISIBLE);
    recyclerView.setVerticalScrollBarEnabled(!useFastScroller);
    fastScroller.setVisibility(useFastScroller ? View.VISIBLE : View.GONE);
    if (useFastScroller) fastScroller.setRecyclerView(recyclerView);
  }

  private void handleContactPermissionGranted() {
    if (viewModel != null) viewModel.refresh();
    showContactsLayout.setVisibility(View.GONE);
    emptyText.setVisibility(View.GONE);
  }

  private class ListClickListener implements ContactSelectionListAdapter.ItemClickListener {
    public void onItemClick(ContactSelectionListItem contact) {

      if (!multi || !selectedContacts.containsKey(contact.getContactId())) {
        selectedContacts.put(contact.getContactId(), contact.getNumber());
        contact.setChecked(true);
        if (onContactSelectedListener != null) onContactSelectedListener.onContactSelected(contact.getNumber());
      } else {
        selectedContacts.remove(contact.getContactId());
        contact.setChecked(false);
      }
    }
  }

  public void setOnContactSelectedListener(OnContactSelectedListener onContactSelectedListener) {
    this.onContactSelectedListener = onContactSelectedListener;
  }

  public interface OnContactSelectedListener {
    public void onContactSelected(String number);
  }

}
