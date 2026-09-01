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

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import org.smssecure.smssecure.color.MaterialColor;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.MmsDatabase;
import org.smssecure.smssecure.database.MmsSmsDatabase;
import org.smssecure.smssecure.database.SmsDatabase;
import org.smssecure.smssecure.database.loaders.MessageDetailsLoader;
import org.smssecure.smssecure.database.model.MessageRecord;
import org.smssecure.smssecure.notifications.MessageNotifier;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.DateUtils;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;

/**
 * @author Jake McGinty
 */
public class MessageDetailsActivity extends PassphraseRequiredActionBarActivity implements LoaderCallbacks<Cursor>, Recipients.RecipientsModifiedListener {
  private final static String TAG = MessageDetailsActivity.class.getSimpleName();

  public final static String MASTER_SECRET_EXTRA  = "master_secret";
  public final static String MESSAGE_ID_EXTRA     = "message_id";
  public final static String THREAD_ID_EXTRA      = "thread_id";
  public final static String TYPE_EXTRA           = "type";
  public final static String RECIPIENTS_IDS_EXTRA = "recipients_ids";

  private MasterSecret     masterSecret;
  private long             threadId;
  private ConversationItem conversationItem;
  private ViewGroup        itemParent;
  private View             metadataContainer;
  private TextView         errorText;
  private TextView         sentDate;
  private TextView         receivedDate;
  private View             receivedContainer;
  private TextView         transport;
  private TextView         toFrom;
  private ListView         recipientsList;
  private LayoutInflater   inflater;
  private AppTaskExecutor.TaskHandle recipientTask;

  private DynamicTheme     dynamicTheme    = new DynamicTheme();
  private DynamicLanguage  dynamicLanguage = new DynamicLanguage();

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  public void onCreate(Bundle bundle, @NonNull MasterSecret masterSecret) {
    setContentView(R.layout.message_details_activity);

    initializeResources();
    initializeActionBar();
    LoaderManager.getInstance(this).initLoader(0, null, this);
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    getSupportActionBar().setTitle(R.string.AndroidManifest__message_details);

    MessageNotifier.setVisibleThread(threadId);
  }

  @Override
  protected void onPause() {
    super.onPause();
    MessageNotifier.setVisibleThread(-1L);
  }

  @Override
  protected void onDestroy() {
    if (recipientTask != null) recipientTask.cancel();
    recipientTask = null;
    super.onDestroy();
  }

  private void initializeActionBar() {
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    Recipients recipients = RecipientFactory.getRecipientsForIds(this, getIntent().getLongArrayExtra(RECIPIENTS_IDS_EXTRA), true);
    recipients.addListener(this);

    setActionBarColor(recipients.getColor());
  }

  private void setActionBarColor(MaterialColor color) {
    getSupportActionBar().setBackgroundDrawable(new ColorDrawable(color.toActionBarColor(this)));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setStatusBarColorCompat(color.toStatusBarColor(this));
    }
  }

  @Override
  public void onModified(final Recipients recipients) {
    Util.runOnMain(new Runnable() {
      @Override
      public void run() {
        setActionBarColor(recipients.getColor());
      }
    });
  }

  private void initializeResources() {
    inflater       = LayoutInflater.from(this);
    View header = inflater.inflate(R.layout.message_details_header, recipientsList, false);

    masterSecret      = androidx.core.content.IntentCompat.getParcelableExtra(getIntent(), MASTER_SECRET_EXTRA, MasterSecret.class);
    threadId          = getIntent().getLongExtra(THREAD_ID_EXTRA, -1);
    itemParent        = (ViewGroup) header.findViewById(R.id.item_container);
    recipientsList    = (ListView ) findViewById(R.id.recipients_list);
    metadataContainer =             header.findViewById(R.id.metadata_container);
    errorText         = (TextView ) header.findViewById(R.id.error_text);
    sentDate          = (TextView ) header.findViewById(R.id.sent_time);
    receivedContainer =             header.findViewById(R.id.received_container);
    receivedDate      = (TextView ) header.findViewById(R.id.received_time);
    transport         = (TextView ) header.findViewById(R.id.transport);
    toFrom            = (TextView ) header.findViewById(R.id.tofrom);
    recipientsList.setHeaderDividersEnabled(false);
    recipientsList.addHeaderView(header, null, false);
  }

  private void updateTransport(MessageRecord messageRecord) {
    final String transportText;
    if (messageRecord.isOutgoing() && messageRecord.isFailed()) {
      transportText = "-";
    } else if (messageRecord.isPending()) {
      transportText = getString(R.string.ConversationFragment_pending);
    } else if (messageRecord.isMms()) {
      transportText = getString(R.string.ConversationFragment_mms);
    } else {
      transportText = getString(R.string.ConversationFragment_sms);
    }

    transport.setText(transportText);
  }

  private void updateTime(Context context, MessageRecord messageRecord) {
    boolean isSmsDeliveryReportsEnabled = SilencePreferences.isSmsDeliveryReportsEnabled(context);

    if (messageRecord.isPending() || messageRecord.isFailed()) {
      sentDate.setText("-");
      if (!isSmsDeliveryReportsEnabled) receivedContainer.setVisibility(View.GONE);
      receivedDate.setText("-");
    } else {
      Locale           dateLocale    = dynamicLanguage.getCurrentLocale();
      SimpleDateFormat dateFormatter = DateUtils.getDetailedDateFormatter(this, dateLocale);
      sentDate.setText(dateFormatter.format(new Date(messageRecord.getDateSent())));

      if (!messageRecord.isOutgoing()) {
        receivedDate.setText(dateFormatter.format(new Date(messageRecord.getDateReceived())));
      } else if (isSmsDeliveryReportsEnabled) {
        Log.w(TAG, "getDateSent(): "+messageRecord.getDateSent());
        Log.w(TAG, "getDateDeliveryReceived(): "+messageRecord.getDateDeliveryReceived());
        String deliveryString;
        if (!messageRecord.isDelivered()){
          deliveryString = getString(R.string.no);
        } else if (messageRecord.getDateDeliveryReceived() == 0) {
          deliveryString = getString(R.string.yes);
        } else {
          deliveryString = dateFormatter.format(new Date(messageRecord.getDateDeliveryReceived()));
        }
        receivedDate.setText(deliveryString);
      } else {
        receivedContainer.setVisibility(View.GONE);
      }
    }
  }

  private void updateRecipients(MessageRecord messageRecord, Recipients recipients) {
    final int toFromRes;
    if (messageRecord.isMms() && !messageRecord.isPush() && !messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__with;
    } else if (messageRecord.isOutgoing()) {
      toFromRes = R.string.message_details_header__to;
    } else {
      toFromRes = R.string.message_details_header__from;
    }
    toFrom.setText(toFromRes);
    conversationItem.bind(masterSecret, messageRecord, dynamicLanguage.getCurrentLocale(),
                         new HashSet<MessageRecord>(), recipients);
    if (conversationItem != null) conversationItem.hideClickForDetails();
    recipientsList.setAdapter(new MessageDetailsRecipientAdapter(this, masterSecret, messageRecord,
                                                                 recipients));
  }

  private void inflateMessageViewIfAbsent(MessageRecord messageRecord) {
    if (conversationItem == null) {
      if (messageRecord.isGroupAction()) {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_update, itemParent, false);
      } else if (messageRecord.isOutgoing()) {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_sent, itemParent, false);
      } else {
        conversationItem = (ConversationItem) inflater.inflate(R.layout.conversation_item_received, itemParent, false);
      }
      itemParent.addView(conversationItem);
    }
  }

  private MessageRecord getMessageRecord(Context context, Cursor cursor, String type) {
    switch (type) {
      case MmsSmsDatabase.SMS_TRANSPORT:
        EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
        SmsDatabase.Reader    reader      = smsDatabase.readerFor(masterSecret, cursor);
        return reader.getNext();
      case MmsSmsDatabase.MMS_TRANSPORT:
        MmsDatabase        mmsDatabase = DatabaseFactory.getMmsDatabase(context);
        MmsDatabase.Reader mmsReader   = mmsDatabase.readerFor(masterSecret, cursor);
        return mmsReader.getNext();
      default:
        throw new AssertionError("no valid message type specified");
    }
  }


  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new MessageDetailsLoader(this, getIntent().getStringExtra(TYPE_EXTRA),
                                    getIntent().getLongExtra(MESSAGE_ID_EXTRA, -1));
  }

  @Override
  public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
    final MessageRecord messageRecord = getMessageRecord(this, cursor, getIntent().getStringExtra(TYPE_EXTRA));
    if (recipientTask != null) recipientTask.cancel();

    Context context = getApplicationContext();
    WeakReference<MessageDetailsActivity> owner = new WeakReference<>(this);
    recipientTask = AppTaskExecutor.getInstance().submitSerial(
        () -> resolveRecipients(context, messageRecord),
        recipients -> {
          MessageDetailsActivity activity = owner.get();
          if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            activity.handleRecipientsResolved(messageRecord, recipients);
          }
        },
        exception -> Log.w(TAG, "Unable to resolve message recipients", exception));
  }

  @Override
  public void onLoaderReset(Loader<Cursor> loader) {
    recipientsList.setAdapter(null);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home: finish(); return true;
    }

    return false;
  }

  private static Recipients resolveRecipients(Context context, MessageRecord messageRecord) {
      if (messageRecord == null) return null;

      if (messageRecord.isMms()) {
        return DatabaseFactory.getMmsAddressDatabase(context).getRecipientsForId(messageRecord.getId());
      } else {
        return messageRecord.getRecipients();
      }
  }

  private void handleRecipientsResolved(MessageRecord messageRecord, Recipients recipients) {
      if (messageRecord == null) {
        Log.w(TAG, "messageRecord is null, finishing activity...");
        finish();
        return;
      }

      inflateMessageViewIfAbsent(messageRecord);

      updateRecipients(messageRecord, recipients);
      if (messageRecord.isFailed()) {
        errorText.setVisibility(View.VISIBLE);
        metadataContainer.setVisibility(View.GONE);
      } else {
        updateTransport(messageRecord);
        updateTime(this, messageRecord);
        errorText.setVisibility(View.GONE);
        metadataContainer.setVisibility(View.VISIBLE);
      }
  }
}
