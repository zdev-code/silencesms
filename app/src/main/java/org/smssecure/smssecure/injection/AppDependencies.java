package org.smssecure.smssecure.injection;

import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.data.conversationthread.ConversationThreadRepository;
import org.smssecure.smssecure.data.conversationscreen.ConversationScreenRepository;
import org.smssecure.smssecure.domain.conversation.SendSelectedDrafts;
import org.smssecure.smssecure.domain.conversation.ConversationListReminderPolicy;
import org.smssecure.smssecure.domain.upgrade.DatabaseUpgradeCoordinator;

public interface AppDependencies {
  ConversationRepository conversationRepository();
  ConversationThreadRepository conversationThreadRepository();
  ConversationScreenRepository conversationScreenRepository();
  SendSelectedDrafts sendSelectedDrafts();
  ConversationListReminderPolicy conversationListReminderPolicy();
  DatabaseUpgradeCoordinator databaseUpgradeCoordinator();
}