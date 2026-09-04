/**
 * Copyright (C) 2014 Open Whisper Systems
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
package org.smssecure.smssecure.contacts;

import android.content.Context;
import android.content.res.Resources;
import android.provider.ContactsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.TextView;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.components.RecyclerViewFastScroller.FastScrollAdapter;
import org.smssecure.smssecure.util.StickyHeaderDecoration.StickyHeaderAdapter;
import org.smssecure.smssecure.contacts.ContactSelectionListAdapter.HeaderViewHolder;
import org.smssecure.smssecure.contacts.ContactSelectionListAdapter.ViewHolder;
import org.smssecure.smssecure.data.contact.ContactEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * List adapter to display all contacts and their related information
 *
 * @author Jake McGinty
 */
public class ContactSelectionListAdapter extends RecyclerView.Adapter<ViewHolder>
                                         implements FastScrollAdapter,
                                                    StickyHeaderAdapter<HeaderViewHolder>
{
  private final Context           context;
  private final boolean           multiSelect;
  private final LayoutInflater    li;
  private final int               pushUserColor;
  private final int               layUserColor;
  private final ItemClickListener clickListener;

  private final HashMap<Long, String> selectedContacts = new HashMap<>();
  private List<ContactEntry> contacts = List.of();

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public ViewHolder(@NonNull  final View              itemView,
                      @Nullable final ItemClickListener clickListener)
    {
      super(itemView);
      itemView.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View v) {
          if (clickListener != null) clickListener.onItemClick(getView());
        }
      });
    }

    public ContactSelectionListItem getView() {
      return (ContactSelectionListItem) itemView;
    }
  }

  public static class HeaderViewHolder extends RecyclerView.ViewHolder {
    public HeaderViewHolder(View itemView) {
      super(itemView);
    }
  }

  public ContactSelectionListAdapter(@NonNull  Context context,
                                     @Nullable ItemClickListener clickListener,
                                     boolean multiSelect)
  {
    this.context      = context;
    this.li           = LayoutInflater.from(context);
    this.multiSelect  = multiSelect;
    this.pushUserColor = resolveThemeColor(context, R.attr.contact_selection_push_user, 0xa0000000);
    this.layUserColor  = resolveThemeColor(context, R.attr.contact_selection_lay_user, 0xff000000);
    this.clickListener = clickListener;
  }

  public void submitList(List<ContactEntry> contacts) {
    this.contacts = List.copyOf(contacts);
    notifyDataSetChanged();
  }

  @Override public int getItemCount() { return contacts.size(); }

  @Override
  public long getHeaderId(int i) {
    return getHeaderString(i).hashCode();
  }

  @Override
  public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    return new ViewHolder(li.inflate(R.layout.contact_selection_list_item, parent, false), clickListener);
  }

  @Override
  public void onBindViewHolder(ViewHolder viewHolder, int position) {
    ContactEntry contact = contacts.get(position);
    long   id          = contact.getId();
    int    contactType = contact.getContactType();
    String name        = contact.getName();
    String number      = contact.getNumber();
    String labelText   = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.getResources(),
                                                                             contact.getNumberType(),
                                                                             contact.getLabel()).toString();

    int color = (contactType == ContactsDatabase.PUSH_TYPE) ? pushUserColor : layUserColor;

    viewHolder.getView().unbind();
    viewHolder.getView().set(id, contactType, name, number, labelText, color, multiSelect);
    viewHolder.getView().setChecked(selectedContacts.containsKey(id));
  }

  @Override
  public HeaderViewHolder onCreateHeaderViewHolder(ViewGroup parent) {
    return new HeaderViewHolder(LayoutInflater.from(context).inflate(R.layout.contact_selection_recyclerview_header, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(HeaderViewHolder viewHolder, int position) {
    ((TextView)viewHolder.itemView).setText(getHeaderString(position));
  }

  @Override
  public CharSequence getBubbleText(int position) {
    return getHeaderString(position);
  }

  public Map<Long, String> getSelectedContacts() {
    return selectedContacts;
  }

  private int resolveThemeColor(@NonNull Context context, int attr, int defaultColor) {
    Resources.Theme theme = context.getTheme();
    if (theme == null) {
      return defaultColor;
    }

    TypedValue typedValue = new TypedValue();
    if (!theme.resolveAttribute(attr, typedValue, true)) {
      return defaultColor;
    }

    if (typedValue.resourceId != 0) {
      return ContextCompat.getColor(context, typedValue.resourceId);
    }

    if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
      return typedValue.data;
    }

    return defaultColor;
  }

  private @NonNull String getHeaderString(int position) {
    String letter = contacts.get(position).getName();
    if (!TextUtils.isEmpty(letter)) {
      String firstChar = letter.trim().substring(0, 1).toUpperCase(Locale.getDefault());
      if (Character.isLetterOrDigit(firstChar.codePointAt(0))) {
        return firstChar;
      }
    }

    return "#";
  }

  public interface ItemClickListener {
    void onItemClick(ContactSelectionListItem item);
  }
}
