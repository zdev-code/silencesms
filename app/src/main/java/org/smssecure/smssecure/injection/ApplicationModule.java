package org.smssecure.smssecure.injection;

import android.content.Context;

import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.data.conversation.DefaultConversationRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationthread.DefaultConversationThreadRepository;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.data.conversationscreen.DefaultConversationScreenRepository;
import org.smssecure.smssecure.data.recipient.DefaultRecipientPreferencesRepository;
import org.smssecure.smssecure.data.recipient.RecipientPreferencesRepository;
import org.smssecure.smssecure.data.media.DefaultMediaOverviewRepository;
import org.smssecure.smssecure.data.media.MediaOverviewRepository;
import org.smssecure.smssecure.data.message.DefaultMessageDetailsRepository;
import org.smssecure.smssecure.data.message.MessageDetailsRepository;
import org.smssecure.smssecure.data.contact.ContactRepository;
import org.smssecure.smssecure.data.contact.DefaultContactRepository;
import org.smssecure.smssecure.data.settings.ApnDefaultsRepository;
import org.smssecure.smssecure.data.settings.DefaultApnDefaultsRepository;
import org.smssecure.smssecure.data.share.DefaultSharePayloadRepository;
import org.smssecure.smssecure.data.share.SharePayloadRepository;
import org.smssecure.smssecure.data.settings.DefaultNotificationRefreshRepository;
import org.smssecure.smssecure.data.settings.NotificationRefreshRepository;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.domain.conversation.DefaultConversationListReminderPolicy;
import org.smssecure.smssecure.domain.conversation.SendSelectedDrafts;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinator;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public final class ApplicationModule {
  private ApplicationModule() {}

  @Provides
  @Singleton
  public static AppTaskExecutor provideAppTaskExecutor() {
    return AppTaskExecutor.getInstance();
  }

  @Provides
  @Singleton
  public static ConversationRepository provideConversationRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultConversationRepository(context, executor);
  }

  @Provides
  @Singleton
  public static ConversationThreadRepository provideConversationThreadRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultConversationThreadRepository(context, executor);
  }

  @Provides
  @Singleton
  public static ConversationScreenRepository provideConversationScreenRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultConversationScreenRepository(context, executor);
  }

  @Provides
  @Singleton
  public static RecipientPreferencesRepository provideRecipientPreferencesRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultRecipientPreferencesRepository(context, executor);
  }

  @Provides
  @Singleton
  public static MediaOverviewRepository provideMediaOverviewRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultMediaOverviewRepository(context, executor);
  }

  @Provides
  @Singleton
  public static MessageDetailsRepository provideMessageDetailsRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultMessageDetailsRepository(context, executor);
  }

  @Provides
  @Singleton
  public static ContactRepository provideContactRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultContactRepository(context, executor);
  }

  @Provides
  @Singleton
  public static ApnDefaultsRepository provideApnDefaultsRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultApnDefaultsRepository(context, executor);
  }

  @Provides
  @Singleton
  public static SharePayloadRepository provideSharePayloadRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultSharePayloadRepository(context, executor);
  }

  @Provides
  @Singleton
  public static NotificationRefreshRepository provideNotificationRefreshRepository(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultNotificationRefreshRepository(context, executor);
  }

  @Provides
  @Singleton
  public static SendSelectedDrafts provideSendSelectedDrafts(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new SendSelectedDrafts(context, executor);
  }

  @Provides
  @Singleton
  public static ConversationListReminderPolicy provideConversationListReminderPolicy(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DefaultConversationListReminderPolicy(context, executor);
  }

  @Provides
  @Singleton
  public static DatabaseUpgradeCoordinator provideDatabaseUpgradeCoordinator(
      @ApplicationContext Context context, AppTaskExecutor executor) {
    return new DatabaseUpgradeCoordinator(context, executor);
  }
}