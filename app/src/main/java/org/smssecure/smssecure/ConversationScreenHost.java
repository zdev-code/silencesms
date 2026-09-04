package org.smssecure.smssecure;

interface ConversationScreenHost extends MediaNavigationHost {
  void finishConversationScreen();
  boolean isConversationHostFinishing();
  void onConversationSendComplete(long threadId);
  void openVerifyIdentity(long recipientId, int subscriptionId);
  void openRecipientPreferences(long[] recipientIds);
}