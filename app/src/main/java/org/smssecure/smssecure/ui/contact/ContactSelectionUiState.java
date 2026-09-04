package org.smssecure.smssecure.ui.contact;

import org.smssecure.smssecure.data.contact.ContactEntry;

import java.util.List;

public final class ContactSelectionUiState {
  private final boolean loading;
  private final List<ContactEntry> contacts;

  public ContactSelectionUiState(boolean loading, List<ContactEntry> contacts) {
    this.loading = loading;
    this.contacts = List.copyOf(contacts);
  }

  public boolean isLoading() { return loading; }
  public List<ContactEntry> getContacts() { return contacts; }
}