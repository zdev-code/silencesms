package org.smssecure.smssecure.data.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.smssecure.smssecure.database.DatabaseContentProviders;
import org.smssecure.smssecure.util.concurrent.AppTaskExecutor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ConversationRepositoryRobolectricTest {
  private Context context;
  private DefaultConversationRepository repository;
  private ConversationRepository.Subscription subscription;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    repository = new DefaultConversationRepository(context, AppTaskExecutor.getInstance());
  }

  @After
  public void tearDown() {
    if (subscription != null) subscription.close();
    context.deleteDatabase("messages.db");
  }

  @Test
  public void contentResolverNotificationRequeriesUntilSubscriptionCloses() {
    AtomicInteger snapshots = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();
    subscription = repository.observe(new ConversationListQuery(false, ""),
        new ConversationRepository.Observer() {
          @Override
          public void onSnapshot(ConversationListSnapshot snapshot) {
            assertThat(snapshot.getEntries()).isEmpty();
            snapshots.incrementAndGet();
          }

          @Override
          public void onError(Exception exception) {
            failure.set(exception);
          }
        });

    awaitSnapshots(snapshots, 1);
    context.getContentResolver().notifyChange(
        DatabaseContentProviders.ConversationList.CONTENT_URI, null);
    awaitSnapshots(snapshots, 2);

    subscription.close();
    context.getContentResolver().notifyChange(
        DatabaseContentProviders.ConversationList.CONTENT_URI, null);
    drainMainLooper();

    assertThat(failure.get()).isNull();
    assertThat(snapshots.get()).isEqualTo(2);
  }

  private static void awaitSnapshots(AtomicInteger snapshots, int expected) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (snapshots.get() < expected && System.nanoTime() < deadline) {
      drainMainLooper();
      Thread.yield();
    }
    assertThat(snapshots.get()).isGreaterThanOrEqualTo(expected);
  }

  private static void drainMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
}
