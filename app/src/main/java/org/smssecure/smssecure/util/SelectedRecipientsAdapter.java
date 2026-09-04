package org.smssecure.smssecure.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.ui.groupcreate.GroupMember;

import java.util.List;

public class SelectedRecipientsAdapter extends BaseAdapter {

  private final Context context;
  private List<GroupMember> members = List.of();
  private OnRecipientDeletedListener onRecipientDeletedListener;

  public SelectedRecipientsAdapter(Context context) {
    this.context = context;
  }

  public void submitList(List<GroupMember> members) {
    this.members = List.copyOf(members);
    notifyDataSetChanged();
  }

  @Override
  public int getCount() { return members.size(); }

  @Override
  public GroupMember getItem(int position) { return members.get(position); }

  @Override
  public long getItemId(int position) { return getItem(position).getRecipientId(); }

  @Override
  public View getView(final int position, final View convertView, final ViewGroup parent) {

    View v = convertView;

    if (v == null) {

      LayoutInflater vi;
      vi = LayoutInflater.from(context);
      v = vi.inflate(R.layout.selected_recipient_list_item, parent, false);

    }

    final GroupMember member = getItem(position);

    if (member != null) {

      TextView name = (TextView) v.findViewById(R.id.name);
      TextView phone = (TextView) v.findViewById(R.id.phone);
      ImageButton delete = (ImageButton) v.findViewById(R.id.delete);

      if (name != null) {
        name.setText(member.getName());
      }
      if (phone != null) {
        phone.setText(member.getNumber());
      }
      if (delete != null) {
        delete.setVisibility(View.VISIBLE);
        delete.setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            if (onRecipientDeletedListener != null) {
              onRecipientDeletedListener.onRecipientDeleted(member.getRecipientId());
            }
          }
        });
      }
    }

    return v;
  }

  public void setOnRecipientDeletedListener(OnRecipientDeletedListener listener) {
    onRecipientDeletedListener = listener;
  }

  public interface OnRecipientDeletedListener {
    void onRecipientDeleted(long recipientId);
  }
}