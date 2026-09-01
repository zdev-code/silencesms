package org.smssecure.smssecure.injection;

import android.content.Context;

import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.data.conversation.DefaultConversationRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationthread.DefaultConversationThreadRepository;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.data.conversationscreen.DefaultConversationScreenRepository;
import org.smssecure.smssecure.domain.conversation.SendSelectedDrafts;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.domain.conversation.DefaultConversationListReminderPolicy;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinator;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.Objects;

public final class DefaultAppDependencies implements AppDependencies {
  private final ConversationRepository conversationRepository;
  private final ConversationThreadRepository conversationThreadRepository;
  private final ConversationScreenRepository conversationScreenRepository;
  private final SendSelectedDrafts      sendSelectedDrafts;
  private final ConversationListReminderPolicy conversationListReminderPolicy;
  private final DatabaseUpgradeCoordinator databaseUpgradeCoordinator;

  public DefaultAppDependencies(Context context) {
    this(context, AppTaskExecutor.getInstance());
  }

  DefaultAppDependencies(Context context, AppTaskExecutor executor) {
    Context applicationContext = Objects.requireNonNull(context).getApplicationContext();
    this.conversationRepository = new DefaultConversationRepository(applicationContext, executor);
    this.conversationThreadRepository =
      new DefaultConversationThreadRepository(applicationContext, executor);
    this.conversationScreenRepository =
      new DefaultConversationScreenRepository(applicationContext, executor);
    this.sendSelectedDrafts      = new SendSelectedDrafts(applicationContext, executor);
    this.conversationListReminderPolicy =
      new DefaultConversationListReminderPolicy(applicationContext, executor);
    this.databaseUpgradeCoordinator =
      new DatabaseUpgradeCoordinator(applicationContext, executor);
  }

  @Override
  public ConversationRepository conversationRepository() {
    return conversationRepository;
  }

  @Override
  public ConversationThreadRepository conversationThreadRepository() {
    return conversationThreadRepository;
  }

  @Override
  public ConversationScreenRepository conversationScreenRepository() {
    return conversationScreenRepository;
  }

  @Override
  public SendSelectedDrafts sendSelectedDrafts() {
    return sendSelectedDrafts;
  }

  @Override
  public ConversationListReminderPolicy conversationListReminderPolicy() {
    return conversationListReminderPolicy;
  }

  @Override
  public DatabaseUpgradeCoordinator databaseUpgradeCoordinator() {
    return databaseUpgradeCoordinator;
  }
}