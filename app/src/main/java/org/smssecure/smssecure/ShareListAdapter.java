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
package org.smssecure.smssecure;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;

import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.ui.share.ShareTarget;

import java.util.List;

/**
 * A CursorAdapter for building a list of open conversations
 *
 * @author Jake McGinty
 */
public class ShareListAdapter extends BaseAdapter implements AbsListView.RecyclerListener {

  private final Context        context;
  private final LayoutInflater inflater;
  private List<ShareTarget> targets = List.of();

  public ShareListAdapter(Context context) {
    this.context        = context;
    this.inflater       = LayoutInflater.from(context);
  }

  public void submitList(List<ShareTarget> targets) {
    this.targets = List.copyOf(targets);
    notifyDataSetChanged();
  }

  @Override public int getCount() { return targets.size(); }
  @Override public ShareTarget getItem(int position) { return targets.get(position); }
  @Override public long getItemId(int position) { return getItem(position).getThreadId(); }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    ShareListItem view = (ShareListItem) (convertView != null ? convertView :
        inflater.inflate(R.layout.share_list_item_view, parent, false));
    ShareTarget target = getItem(position);
    Recipients recipients = RecipientFactory.getRecipientsForIds(context, target.getRecipientIds(), true);
    view.set(target.getThreadId(), recipients, target.getDistributionType());
    return view;
  }

  @Override
  public void onMovedToScrapHeap(View view) {
    ((ShareListItem)view).unbind();
  }
}
