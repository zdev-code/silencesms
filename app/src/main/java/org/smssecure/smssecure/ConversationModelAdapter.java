package org.smssecure.smssecure;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversationthread.ConversationMessageRow;
import org.smssecure.smssecure.database.model.MediaMmsMessageRecord;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.Conversions;
import org.smssecure.smssecure.util.DateUtils;
import org.smssecure.smssecure.util.StickyHeaderDecoration;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.ViewUtil;

import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ConversationModelAdapter
    extends RecyclerView.Adapter<RecyclerView.ViewHolder>
    implements StickyHeaderDecoration.StickyHeaderAdapter<ConversationModelAdapter.HeaderViewHolder> {
  private static final int MESSAGE_TYPE_OUTGOING = 0;
  private static final int MESSAGE_TYPE_INCOMING = 1;
  private static final int MESSAGE_TYPE_UPDATE = 2;
  private static final int MESSAGE_TYPE_AUDIO_OUTGOING = 3;
  private static final int MESSAGE_TYPE_AUDIO_INCOMING = 4;
  private static final int FOOTER_TYPE = Integer.MIN_VALUE + 1;
  private static final long FOOTER_ID = Long.MIN_VALUE + 1;

  private final Context context;
  private final MasterSecret masterSecret;
  private final Locale locale;
  private final @Nullable ItemClickListener clickListener;
  private final Recipients recipients;
  private final LayoutInflater inflater;
  private final Calendar calendar = Calendar.getInstance();
  private final MessageDigest digest;
  private final ConversationMessageMapper mapper;
  private final Map<String, SoftReference<MessageRecord>> cache = new ConcurrentHashMap<>();

  private List<ConversationMessageRow> rows = List.of();
  private Set<String> selectedIds = Set.of();
  private @Nullable View footer;

  public interface ItemClickListener {
    void onItemClick(ConversationItem item);
    void onItemLongClick(ConversationItem item);
  }

  static final class ViewHolder extends RecyclerView.ViewHolder {
    ViewHolder(@NonNull ConversationItem itemView) { super(itemView); }
    ConversationItem getView() { return (ConversationItem) itemView; }
  }

  static class HeaderViewHolder extends RecyclerView.ViewHolder {
    protected final TextView textView;
    HeaderViewHolder(View itemView) {
      super(itemView);
      textView = ViewUtil.findById(itemView, R.id.text);
    }
    HeaderViewHolder(TextView textView) {
      super(textView);
      this.textView = textView;
    }
    void setText(CharSequence text) { textView.setText(text); }
  }

  private static final class FooterViewHolder extends RecyclerView.ViewHolder {
    FooterViewHolder(View itemView) { super(itemView); }
  }

  public ConversationModelAdapter(Context context, MasterSecret masterSecret, Locale locale,
                                  @Nullable ItemClickListener clickListener, Recipients recipients,
                                  ConversationMessageMapper mapper) {
    this.context = context;
    this.masterSecret = masterSecret;
    this.locale = locale;
    this.clickListener = clickListener;
    this.recipients = recipients;
    this.inflater = LayoutInflater.from(context);
    this.mapper = mapper;
    try {
      this.digest = MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA1 isn't supported!");
    }
    setHasStableIds(true);
  }

  public void setMessages(List<ConversationMessageRow> rows, Set<String> selectedIds) {
    this.rows = List.copyOf(rows);
    this.selectedIds = Set.copyOf(selectedIds);
    cache.keySet().retainAll(this.rows.stream().map(ConversationMessageRow::getStableId).toList());
    notifyDataSetChanged();
  }

  public void setFooterView(@Nullable View footer) {
    this.footer = footer;
    notifyDataSetChanged();
  }

  public Set<MessageRecord> getSelectedItems() {
    Set<MessageRecord> selected = new HashSet<>();
    for (int index = 0; index < rows.size(); index++) {
      if (selectedIds.contains(rows.get(index).getStableId())) selected.add(getMessageRecord(index));
    }
    return Collections.unmodifiableSet(selected);
  }

  public String getStableId(MessageRecord record) {
    for (int index = 0; index < rows.size(); index++) {
      if (getMessageRecord(index) == record || getMessageRecord(index).equals(record)) {
        return rows.get(index).getStableId();
      }
    }
    throw new IllegalArgumentException("Message is not present in this adapter");
  }

  public int findLastSeenPosition(long lastSeen) {
    if (lastSeen <= 0) return -1;
    for (int index = 0; index < rows.size(); index++) {
      MessageRecord record = getMessageRecord(index);
      if (record.isOutgoing() || record.getDateReceived() <= lastSeen) return index;
    }
    return -1;
  }

  public long getReceivedTimestamp(int position) {
    if (!isMessagePosition(position)) return 0;
    MessageRecord record = getMessageRecord(position);
    return record.isOutgoing() ? 0 : record.getDateReceived();
  }

  @Override public int getItemCount() { return rows.size() + (footer == null ? 0 : 1); }
  private boolean isFooterPosition(int position) { return footer != null && position == rows.size(); }
  private boolean isMessagePosition(int position) { return position >= 0 && position < rows.size(); }

  @Override
  public int getItemViewType(int position) {
    if (isFooterPosition(position)) return FOOTER_TYPE;
    MessageRecord record = getMessageRecord(position);
    if (record.isGroupAction()) return MESSAGE_TYPE_UPDATE;
    if (hasAudio(record)) return record.isOutgoing() ? MESSAGE_TYPE_AUDIO_OUTGOING
                                                     : MESSAGE_TYPE_AUDIO_INCOMING;
    return record.isOutgoing() ? MESSAGE_TYPE_OUTGOING : MESSAGE_TYPE_INCOMING;
  }

  @Override
  public long getItemId(int position) {
    if (isFooterPosition(position)) return FOOTER_ID;
    return Conversions.byteArrayToLong(digest.digest(rows.get(position).getStableId().getBytes()));
  }

  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    if (viewType == FOOTER_TYPE) return new FooterViewHolder(footer);
    ConversationItem item = ViewUtil.inflate(inflater, parent, getLayoutForViewType(viewType));
    if (viewType == MESSAGE_TYPE_INCOMING || viewType == MESSAGE_TYPE_OUTGOING) {
      item.setOnClickListener(view -> {
        if (clickListener != null) clickListener.onItemClick(item);
      });
      item.setOnLongClickListener(view -> {
        if (clickListener != null) clickListener.onItemLongClick(item);
        return true;
      });
    }
    return new ViewHolder(item);
  }

  @Override
  public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
    if (holder instanceof ViewHolder) {
      MessageRecord record = getMessageRecord(position);
      Set<MessageRecord> selected = new HashSet<>();
      if (selectedIds.contains(rows.get(position).getStableId())) selected.add(record);
      ((ViewHolder) holder).getView().bind(masterSecret, record, locale, selected, recipients);
    }
  }

  @Override
  public void onViewRecycled(RecyclerView.ViewHolder holder) {
    if (holder instanceof ViewHolder) ((ViewHolder) holder).getView().unbind();
  }

  private @LayoutRes int getLayoutForViewType(int viewType) {
    switch (viewType) {
      case MESSAGE_TYPE_AUDIO_OUTGOING:
      case MESSAGE_TYPE_OUTGOING: return R.layout.conversation_item_sent;
      case MESSAGE_TYPE_AUDIO_INCOMING:
      case MESSAGE_TYPE_INCOMING: return R.layout.conversation_item_received;
      case MESSAGE_TYPE_UPDATE: return R.layout.conversation_item_update;
      default: throw new IllegalArgumentException("Unsupported conversation item type");
    }
  }

  private MessageRecord getMessageRecord(int position) {
    ConversationMessageRow row = rows.get(position);
    SoftReference<MessageRecord> reference = cache.get(row.getStableId());
    MessageRecord record = reference == null ? null : reference.get();
    if (record == null) {
      record = mapper.map(row);
      cache.put(row.getStableId(), new SoftReference<>(record));
    }
    return record;
  }

  private static boolean hasAudio(MessageRecord record) {
    return record.isMms() && !record.isMmsNotification() &&
        ((MediaMmsMessageRecord) record).getSlideDeck().getAudioSlide() != null;
  }

  @Override
  public long getHeaderId(int position) {
    if (!isMessagePosition(position)) return -1;
    calendar.setTime(new Date(getMessageRecord(position).getDateSent()));
    return Util.hashCode(calendar.get(Calendar.YEAR), calendar.get(Calendar.DAY_OF_YEAR));
  }

  @Override
  public HeaderViewHolder onCreateHeaderViewHolder(ViewGroup parent) {
    return new HeaderViewHolder(inflater.inflate(R.layout.conversation_item_header, parent, false));
  }

  public HeaderViewHolder onCreateLastSeenViewHolder(ViewGroup parent) {
    return new HeaderViewHolder(inflater.inflate(R.layout.conversation_item_last_seen, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(HeaderViewHolder holder, int position) {
    holder.setText(DateUtils.getRelativeDate(context, locale, getMessageRecord(position).getDateReceived()));
  }

  public void onBindLastSeenViewHolder(HeaderViewHolder holder, int position) {
    holder.setText(context.getResources().getQuantityString(
        R.plurals.ConversationAdapter_n_unread_messages, position + 1, position + 1));
  }

  static final class LastSeenHeader extends StickyHeaderDecoration {
    private final ConversationModelAdapter adapter;
    private final long lastSeenTimestamp;

    LastSeenHeader(ConversationModelAdapter adapter, long lastSeenTimestamp) {
      super(adapter, false, false);
      this.adapter = adapter;
      this.lastSeenTimestamp = lastSeenTimestamp;
    }

    @Override
    protected boolean hasHeader(RecyclerView parent, StickyHeaderAdapter stickyAdapter, int position) {
      if (lastSeenTimestamp <= 0) return false;
      long current = adapter.getReceivedTimestamp(position);
      long previous = adapter.getReceivedTimestamp(position + 1);
      return current > lastSeenTimestamp && previous < lastSeenTimestamp;
    }

    @Override
    protected int getHeaderTop(RecyclerView parent, View child, View header,
                               int adapterPosition, int layoutPosition) {
      return parent.getLayoutManager().getDecoratedTop(child);
    }

    @Override
    protected HeaderViewHolder getHeader(RecyclerView parent, StickyHeaderAdapter stickyAdapter,
                                         int position) {
      HeaderViewHolder holder = adapter.onCreateLastSeenViewHolder(parent);
      adapter.onBindLastSeenViewHolder(holder, position);
      int widthSpec = View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY);
      int heightSpec = View.MeasureSpec.makeMeasureSpec(parent.getHeight(), View.MeasureSpec.UNSPECIFIED);
      int childWidth = ViewGroup.getChildMeasureSpec(widthSpec,
          parent.getPaddingLeft() + parent.getPaddingRight(), holder.itemView.getLayoutParams().width);
      int childHeight = ViewGroup.getChildMeasureSpec(heightSpec,
          parent.getPaddingTop() + parent.getPaddingBottom(), holder.itemView.getLayoutParams().height);
      holder.itemView.measure(childWidth, childHeight);
      holder.itemView.layout(0, 0, holder.itemView.getMeasuredWidth(), holder.itemView.getMeasuredHeight());
      return holder;
    }
  }
}
