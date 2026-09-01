package org.smssecure.smssecure;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversation.ConversationListEntry;
import org.smssecure.smssecure.database.model.ThreadRecord;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.Conversions;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConversationListModelAdapter extends RecyclerView.Adapter<ConversationListModelAdapter.ViewHolder> {
  private static final int MESSAGE_TYPE_SWITCH_ARCHIVE = 1;
  private static final int MESSAGE_TYPE_THREAD = 2;

  private final ConversationListEntryMapper mapper;
  private final MasterSecret                 masterSecret;
  private final Locale                       locale;
  private final LayoutInflater               inflater;
  private final ItemClickListener                         clickListener;
  private final MessageDigest                digest;
  private final Set<Long>                    batchSet = Collections.synchronizedSet(new HashSet<>());
  private List<ConversationListEntry>        entries = List.of();
  private int                                archivedCount;
  private boolean                            batchMode;

  public ConversationListModelAdapter(@NonNull Context context, @NonNull MasterSecret masterSecret,
                                      @NonNull Locale locale,
                                      @Nullable ItemClickListener clickListener) {
    this.mapper        = new ConversationListEntryMapper(context, masterSecret);
    this.masterSecret  = masterSecret;
    this.locale        = locale;
    this.inflater      = LayoutInflater.from(context);
    this.clickListener = clickListener;
    try {
      this.digest = MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError("SHA-1 missing");
    }
    setHasStableIds(true);
  }

  public void submitList(List<ConversationListEntry> entries, int archivedCount, Set<Long> selectedThreadIds) {
    this.entries = List.copyOf(entries);
    this.archivedCount = archivedCount;
    batchSet.clear();
    batchSet.addAll(selectedThreadIds);
    notifyDataSetChanged();
  }

  @Override public int getItemCount() { return entries.size() + (archivedCount > 0 ? 1 : 0); }

  @Override
  public int getItemViewType(int position) {
    return position < entries.size() ? MESSAGE_TYPE_THREAD : MESSAGE_TYPE_SWITCH_ARCHIVE;
  }

  @Override
  public long getItemId(int position) {
    if (position >= entries.size()) return -1L;
    ThreadRecord record = mapper.map(entries.get(position));
    StringBuilder builder = new StringBuilder(String.valueOf(record.getThreadId()));
    for (long recipientId : record.getRecipients().getIds()) builder.append("::").append(recipientId);
    return Conversions.byteArrayToLong(digest.digest(builder.toString().getBytes()));
  }

  @Override
  public @NonNull ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    if (viewType == MESSAGE_TYPE_SWITCH_ARCHIVE) {
      ConversationListItemAction action = (ConversationListItemAction) inflater.inflate(
          R.layout.conversation_list_item_action, parent, false);
      action.setOnClickListener(view -> {
        if (clickListener != null) clickListener.onSwitchToArchive();
      });
      return new ViewHolder(action);
    }
    ConversationListItem item = (ConversationListItem) inflater.inflate(
        R.layout.conversation_list_item_view, parent, false);
    item.setOnClickListener(view -> {
      if (clickListener != null) clickListener.onItemClick(item);
    });
    item.setOnLongClickListener(view -> {
      if (clickListener != null) clickListener.onItemLongClick(item);
      return true;
    });
    return new ViewHolder(item);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    if (position < entries.size()) {
      holder.getItem().bind(masterSecret, mapper.map(entries.get(position)), locale, batchSet, batchMode);
    } else {
      ((ConversationListItemAction) holder.itemView).bindArchivedCount(archivedCount);
    }
  }

  @Override public void onViewRecycled(@NonNull ViewHolder holder) { holder.getItem().unbind(); }

  public Set<Long> getBatchSelections() { return batchSet; }

  public @Nullable Recipients getRecipientsFromThreadId(long threadId) {
    for (ConversationListEntry entry : entries) {
      if (entry.getThreadId() == threadId) return mapper.map(entry).getRecipients();
    }
    return null;
  }

  public void initializeBatchMode(boolean enabled) {
    batchMode = enabled;
    if (!enabled) batchSet.clear();
    notifyDataSetChanged();
  }

  static final class ViewHolder extends RecyclerView.ViewHolder {
    private ViewHolder(@NonNull View itemView) { super(itemView); }
    private BindableConversationListItem getItem() { return (BindableConversationListItem) itemView; }
  }

  public interface ItemClickListener {
    void onItemClick(ConversationListItem item);
    void onItemLongClick(ConversationListItem item);
    void onSwitchToArchive();
  }
}