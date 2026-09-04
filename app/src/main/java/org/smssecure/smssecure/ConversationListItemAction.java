package org.smssecure.smssecure;

import android.content.Context;
import androidx.annotation.NonNull;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.model.ThreadRecord;
import org.smssecure.smssecure.util.ViewUtil;

import java.util.Locale;
import java.util.Set;

public class ConversationListItemAction extends LinearLayout implements BindableConversationListItem {

  private TextView description;

  public ConversationListItemAction(Context context) {
    super(context);
  }

  public ConversationListItemAction(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ConversationListItemAction(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();
    this.description = ViewUtil.findById(this, R.id.description);
  }

  public void bindArchivedCount(int archivedCount) {
    description.setText(getContext().getString(
        R.string.ConversationListItemAction_archived_conversations_d, archivedCount));
  }

  @Override
  public void bind(@NonNull MasterSecret masterSecret, @NonNull ThreadRecord thread, @NonNull Locale locale, @NonNull Set<Long> selectedThreads, boolean batchMode) {
    this.description.setText(getContext().getString(R.string.ConversationListItemAction_archived_conversations_d, thread.getCount()));
  }

  @Override
  public void unbind() {

  }

  @Override
  public void clearSensitiveData() {
    description.setText(null);
    setVisibility(INVISIBLE);
  }

  @Override
  public View asView() {
    return this;
  }
}
