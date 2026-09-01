package org.smssecure.smssecure.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.BaseUnitTest;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.database.DraftDatabase;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SendSelectedDraftsTest extends BaseUnitTest {
  private FakeDraftStore draftStore;
  private FakeDraftSender draftSender;
  private Recipients recipients;
  private boolean secure;
  private int observedSubscriptionId;
  private SendSelectedDrafts operation;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    draftStore = new FakeDraftStore();
    draftSender = new FakeDraftSender();
    recipients = mock(Recipients.class);
    when(recipients.isSingleRecipient()).thenReturn(true);
    when(recipients.isGroupRecipient()).thenReturn(false);
    operation = new SendSelectedDrafts(mock(AppTaskExecutor.class), draftStore,
        ids -> recipients,
        (secret, targetRecipients, subscriptionId) -> {
          observedSubscriptionId = subscriptionId;
          return secure;
        },
        draftSender,
        () -> 7);
  }

  @Test
  public void sendsSecureTextAndClearsDrafts() throws Exception {
    secure = true;
    draftStore.put(11L, new DraftDatabase.Draft(DraftDatabase.Draft.TEXT, "hello"));

    SendSelectedDrafts.Result result = operation.run(input(11L), masterSecret);

    assertThat(draftSender.sent).containsExactly("text:11:true:hello");
    assertThat(draftStore.cleared).containsExactly(11L);
    assertThat(observedSubscriptionId).isEqualTo(7);
    assertThat(result.getSentDraftCount()).isEqualTo(1);
  }

  @Test
  public void combinesTextAndMediaInLegacyOrder() throws Exception {
    draftStore.put(12L,
        new DraftDatabase.Draft(DraftDatabase.Draft.TEXT, "caption"),
        new DraftDatabase.Draft(DraftDatabase.Draft.IMAGE, "content://image"));

    operation.run(input(12L), masterSecret);

    assertThat(draftSender.sent).containsExactly("media:12:false:content://image:caption");
    assertThat(draftStore.cleared).containsExactly(12L);
  }

  @Test
  public void missingRecipientsSkipsSendAndClearsDrafts() throws Exception {
    operation = new SendSelectedDrafts(mock(AppTaskExecutor.class), draftStore,
        ids -> null, (secret, targetRecipients, subscriptionId) -> false,
        draftSender, () -> -1);
    draftStore.put(13L, new DraftDatabase.Draft(DraftDatabase.Draft.TEXT, "hello"));

    SendSelectedDrafts.Result result = operation.run(input(13L), masterSecret);

    assertThat(draftSender.sent).isEmpty();
    assertThat(draftStore.cleared).containsExactly(13L);
    assertThat(result.getSkippedThreadCount()).isEqualTo(1);
  }

  @Test
  public void sendFailureDoesNotClearFailingThread() {
    draftStore.put(14L, new DraftDatabase.Draft(DraftDatabase.Draft.TEXT, "hello"));
    draftSender.failure = new RuntimeException("send failed");

    assertThatThrownBy(() -> operation.run(input(14L), masterSecret))
        .isSameAs(draftSender.failure);
    assertThat(draftStore.cleared).isEmpty();
  }

  private static SendSelectedDrafts.Input input(long threadId) {
    return new SendSelectedDrafts.Input(List.of(new SendSelectedDrafts.Target(threadId, "1")));
  }

  private static final class FakeDraftStore implements SendSelectedDrafts.DraftStore {
    private final Map<Long, List<DraftDatabase.Draft>> drafts = new HashMap<>();
    private final List<Long> cleared = new ArrayList<>();

    private void put(long threadId, DraftDatabase.Draft... values) {
      drafts.put(threadId, List.of(values));
    }

    @Override
    public List<DraftDatabase.Draft> getDrafts(MasterSecret masterSecret, long threadId) {
      return drafts.getOrDefault(threadId, List.of());
    }

    @Override public void clearDrafts(long threadId) { cleared.add(threadId); }
  }

  private static final class FakeDraftSender implements SendSelectedDrafts.DraftSender {
    private final List<String> sent = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public void sendText(MasterSecret masterSecret, Recipients recipients, boolean secure,
                         DraftDatabase.Draft draft, long threadId) {
      if (failure != null) throw failure;
      sent.add("text:" + threadId + ":" + secure + ":" + draft.getValue());
    }

    @Override
    public void sendMedia(MasterSecret masterSecret, Recipients recipients, boolean secure,
                          DraftDatabase.Draft draft, long threadId, String forcedValue) {
      if (failure != null) throw failure;
      sent.add("media:" + threadId + ":" + secure + ":" + draft.getValue() + ":" + forcedValue);
    }
  }
}
