/**
 * Copyright (C) 2014 Open Whisper Systems
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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.MenuItemCompat;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;

import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.data.conversation.ConversationRepository;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.conversation.ConversationPayloadStore;
import org.smssecure.smssecure.domain.identity.ConflictIdentityStore;
import org.smssecure.smssecure.domain.media.MediaPreviewDraftStore;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.service.KeyCachingService;
import org.smssecure.smssecure.util.dualsim.SubscriptionInfoCompat;
import org.smssecure.smssecure.util.dualsim.SubscriptionManagerCompat;
import org.smssecure.smssecure.util.DynamicLanguage;
import org.smssecure.smssecure.util.DynamicTheme;
import org.smssecure.smssecure.util.ActivityTransitionCompat;
import org.smssecure.smssecure.util.SilencePreferences;

import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public class ConversationListActivity extends PassphraseRequiredActionBarActivity
  implements ConversationListFragment.ConversationSelectedListener, AppearancePreferenceHost,
             ConversationScreenHost, PromptMmsDialogFragment.Listener
{
  private static final String TAG = ConversationListActivity.class.getSimpleName();

  private final DynamicTheme    dynamicTheme    = new DynamicTheme   ();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private NavController navController;
  private ContentObserver observer;
  private List<SubscriptionInfoCompat> activeSubscriptions;
  @Inject MediaPreviewDraftStore mediaPreviewDraftStore;
  @Inject ConversationPayloadStore conversationPayloadStore;

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle icicle, @NonNull MasterSecret masterSecret) {
    this.activeSubscriptions = SubscriptionManagerCompat.from(this).getActiveSubscriptionInfoList();

    getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE);
    getSupportActionBar().setTitle(R.string.app_name);
    NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
        .findFragmentByTag(ConversationListDestination.HOST_TAG);
    if (navHost == null) {
      navHost = new NavHostFragment();
      getSupportFragmentManager().beginTransaction()
                                 .replace(android.R.id.content, navHost, ConversationListDestination.HOST_TAG)
                                 .setPrimaryNavigationFragment(navHost)
                                 .commitNow();
    }

    navController = navHost.getNavController();
    if (navController.getCurrentDestination() == null) {
      navController.setGraph(R.navigation.conversation_list_navigation,
                   ConversationListDestination.INBOX.arguments(dynamicLanguage.getCurrentLocale()));
    }
    navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
      applyScreenshotSecurity(ConversationListDestination.from(controller).isAlwaysSecure());
      updateDestinationChrome(destination);
      invalidateOptionsMenu();
    });
    routeIntent(getIntent());

    initializeContactUpdatesReceiver();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    routeIntent(intent);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  public void onDestroy() {
    if (observer != null) getContentResolver().unregisterContentObserver(observer);
    super.onDestroy();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    ConversationListDestination destination = ConversationListDestination.from(navController);
    if (destination == ConversationListDestination.ARCHIVE) {
      menu.clear();
      return true;
    }
    if (destination == ConversationListDestination.GROUP_CREATE) {
      menu.clear();
      getMenuInflater().inflate(R.menu.group_create, menu);
      return true;
    }
    if (destination == ConversationListDestination.NEW_CONVERSATION) {
      menu.clear();
      return true;
    }
    if (destination == ConversationListDestination.PUSH_CONTACT_SELECTION) {
      menu.clear();
      return true;
    }
    if (destination != ConversationListDestination.INBOX) {
      menu.clear();
      Fragment fragment = getCurrentDestinationFragment();
      if (fragment instanceof KeyScanningFragment) {
        ((KeyScanningFragment) fragment).populateOptionsMenu(menu);
      } else if (fragment instanceof MediaOverviewFragment) {
        ((MediaOverviewFragment) fragment).populateOptionsMenu(menu);
      } else if (fragment instanceof MediaPreviewFragment) {
        ((MediaPreviewFragment) fragment).populateOptionsMenu(menu);
      }
      return true;
    }

    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.text_secure_normal, menu);

    menu.findItem(R.id.menu_clear_passphrase).setVisible(!SilencePreferences.isPasswordDisabled(this));

    inflateViewIdentities(menu);

    inflater.inflate(R.menu.conversation_list, menu);
    MenuItem menuItem = menu.findItem(R.id.menu_search);
    initializeSearch(menuItem);

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  private void inflateViewIdentities(Menu menu) {
    if (Build.VERSION.SDK_INT >= 22 && activeSubscriptions.size() > 1) {
      menu.findItem(R.id.menu_my_identity).setVisible(false);
      MenuItem menuItem = menu.findItem(R.id.menu_my_identity_dual_sim);
      SubMenu identitiesMenu = menuItem.getSubMenu();
      for (SubscriptionInfoCompat subscriptionInfo : activeSubscriptions) {
        final int subscriptionId = subscriptionInfo.getSubscriptionId();
        identitiesMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, subscriptionInfo.getDisplayName())
                      .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                          handleMyIdentity(subscriptionId);
                          return true;
                        }
                      });
      }
    } else {
      menu.findItem(R.id.menu_my_identity_dual_sim).setVisible(false);
    }
  }

  private void initializeSearch(MenuItem searchViewItem) {
    SearchView searchView = (SearchView)searchViewItem.getActionView();
    searchView.setQueryHint(getString(R.string.ConversationListActivity_search));
    searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
      @Override
      public boolean onQueryTextSubmit(String query) {
        ConversationListFragment fragment = getCurrentFragment();
        if (fragment != null) {
          fragment.setQueryFilter(query);
          return true;
        }

        return false;
      }

      @Override
      public boolean onQueryTextChange(String newText) {
        return onQueryTextSubmit(newText);
      }
    });

    searchViewItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
      @Override
      public boolean onMenuItemActionExpand(MenuItem menuItem) {
        return true;
      }

      @Override
      public boolean onMenuItemActionCollapse(MenuItem menuItem) {
        ConversationListFragment fragment = getCurrentFragment();
        if (fragment != null) {
          fragment.resetQueryFilter();
        }

        return true;
      }
    });
  }

  @Override
  @SuppressLint("NonConstantResourceId")
  public boolean onOptionsItemSelected(MenuItem item) {
    if (super.onOptionsItemSelected(item)) return true;

    Fragment fragment = getCurrentDestinationFragment();
    if (fragment instanceof KeyScanningFragment &&
        ((KeyScanningFragment) fragment).handleOptionsItem(item)) return true;
    if (fragment instanceof MediaOverviewFragment &&
      ((MediaOverviewFragment) fragment).handleOptionsItem(item)) return true;
    if (fragment instanceof MediaPreviewFragment &&
      ((MediaPreviewFragment) fragment).handleOptionsItem(item)) return true;

    int itemId = item.getItemId();
    if      (itemId == android.R.id.home)                 { navController.navigateUp();     return true; }
    else if (itemId == R.id.menu_create_group)            { createCurrentGroup();           return true; }
    else if (itemId == R.id.menu_archived_conversations) { handleSwitchToArchive();        return true; }
    else if (itemId == R.id.menu_new_group)              { createGroup();                  return true; }
    else if (itemId == R.id.menu_settings)               { handleDisplaySettings();        return true; }
    else if (itemId == R.id.menu_clear_passphrase)       { handleClearPassphrase();        return true; }
    else if (itemId == R.id.menu_mark_all_read)          { handleMarkAllRead();            return true; }
    else if (itemId == R.id.menu_import_export)          { handleImportExport();           return true; }
    else if (itemId == R.id.menu_my_identity)            { handleMyIdentity();             return true; }

    return false;
  }

  @Override
  public void onCreateConversation(long threadId, Recipients recipients, int distributionType, long lastSeen) {
    navigateToConversation(ConversationScreenFragment.arguments(
        recipients.getIds(), threadId, distributionType,
        ConversationListDestination.from(navController) == ConversationListDestination.ARCHIVE,
        System.currentTimeMillis(), lastSeen, null));
  }

  @Override
  public void onSwitchToArchive() {
    navigateTo(ConversationListDestination.ARCHIVE);
  }

  @Override
  public void onCreateNewConversation() {
    navigateTo(ConversationListDestination.NEW_CONVERSATION);
  }

  private void createGroup() {
    navigateTo(ConversationListDestination.GROUP_CREATE);
  }

  private void handleSwitchToArchive() {
    onSwitchToArchive();
  }

  private void handleDisplaySettings() {
    navigateTo(ConversationListDestination.APPLICATION_PREFERENCES);
  }

  private void handleClearPassphrase() {
    Intent intent = new Intent(this, KeyCachingService.class);
    intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
    startService(intent);
  }

  private void handleImportExport() {
    navigateTo(ConversationListDestination.IMPORT_EXPORT);
  }

  @Override
  public void onMmsPreferencesRequested() {
    navigateTo(ConversationListDestination.MMS_PREFERENCES);
  }

  private void handleMyIdentity() {
    if (activeSubscriptions.size() < 2) {
      int subscriptionId = activeSubscriptions.get(0).getSubscriptionId();
      handleMyIdentity(subscriptionId);
    }
  }

  private void handleMyIdentity(int subscriptionId) {
    Bundle arguments = ViewIdentityFragment.arguments(subscriptionId);
    ConversationListDestination.VIEW_IDENTITY.requireAllowedArguments(arguments);
    navController.navigate(ConversationListDestination.VIEW_IDENTITY.getId(), arguments);
  }

  private void handleMarkAllRead() {
    ConversationListFragment fragment = getCurrentFragment();
    if (fragment == null) return;
    fragment.getViewModel().markAllRead(new ConversationUnlockCapability(
      org.smssecure.smssecure.domain.security.UnlockSession.capture()),
        new ConversationRepository.MutationCallback() {
          @Override public void onSuccess() {}
          @Override public void onFailure(Exception exception) {
            Log.w(TAG, "Unable to mark all conversations read", exception);
          }
        });
  }

  private void initializeContactUpdatesReceiver() {
    observer = new ContentObserver(null) {
      @Override
      public void onChange(boolean selfChange) {
        super.onChange(selfChange);
        Log.w(TAG, "Detected android contact data changed, refreshing cache");
        RecipientFactory.clearCache();
        ConversationListActivity.this.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            ConversationListFragment fragment = getCurrentFragment();
            if (fragment != null) fragment.getListAdapter().notifyDataSetChanged();
          }
        });
      }
    };

    getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI,
                                                 true, observer);
  }

  private void routeIntent(Intent intent) {
    if (intent != null && intent.getBooleanExtra(ConversationListDestination.EXTRA_ARCHIVE, false)) {
      intent.removeExtra(ConversationListDestination.EXTRA_ARCHIVE);
      navigateTo(ConversationListDestination.ARCHIVE);
      return;
    }

    switch (HostNavigationCommand.consume(intent)) {
      case ARCHIVE: navigateTo(ConversationListDestination.ARCHIVE); break;
      case NEW_CONVERSATION: {
        Bundle arguments = HostNavigationCommand.consumeNewConversationArguments(intent);
        navController.navigate(ConversationListDestination.NEW_CONVERSATION.getId(), arguments);
        break;
      }
      case BLOCKED_CONTACTS: navigateTo(ConversationListDestination.BLOCKED_CONTACTS); break;
      case MMS_PREFERENCES: navigateTo(ConversationListDestination.MMS_PREFERENCES); break;
      case APP_PROTECTION_PREFERENCES:
        navigateTo(ConversationListDestination.APP_PROTECTION_PREFERENCES); break;
      case CONVERSATION: navigateToConversation(HostNavigationCommand.consumeConversationArguments(intent)); break;
      case MEDIA_OVERVIEW: {
        Bundle arguments = HostNavigationCommand.consumeMediaOverviewArguments(intent);
        openMediaOverview(arguments.getLong(MediaOverviewFragment.THREAD_ID_ARGUMENT),
                          arguments.getLong(MediaOverviewFragment.RECIPIENT_ID_ARGUMENT));
        break;
      }
      case VERIFY_IDENTITY: {
        Bundle arguments = HostNavigationCommand.consumeVerifyIdentityArguments(intent);
        ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(arguments);
        navController.navigate(ConversationListDestination.VERIFY_IDENTITY.getId(), arguments);
        break;
      }
        case RECIPIENT_PREFERENCES: navigateToRecipientPreferences(
          HostNavigationCommand.consumeRecipientPreferencesArguments(intent)); break;
      case INBOX: break;
      default: throw new AssertionError("Unknown host navigation command");
    }
  }

  private void navigateTo(ConversationListDestination destination) {
    if (ConversationListDestination.from(navController) == destination) return;
    navController.navigate(destination.getId(),
                           destination.arguments(dynamicLanguage.getCurrentLocale()));
  }

  private void navigateToConversation(@NonNull Bundle arguments) {
    ConversationListDestination.CONVERSATION.requireAllowedArguments(arguments);
    if (ConversationListDestination.from(navController) == ConversationListDestination.CONVERSATION) {
      Fragment fragment = getCurrentDestinationFragment();
      if (fragment instanceof ConversationScreenFragment) {
        ((ConversationScreenFragment) fragment).updateArguments(arguments);
        return;
      }
    }
    navController.navigate(ConversationListDestination.CONVERSATION.getId(), arguments);
  }

  private void navigateToRecipientPreferences(@NonNull Bundle arguments) {
    ConversationListDestination.RECIPIENT_PREFERENCES.requireAllowedArguments(arguments);
    navController.navigate(ConversationListDestination.RECIPIENT_PREFERENCES.getId(), arguments);
  }

  @Override
  public void finishConversationScreen() {
    navController.navigateUp();
  }

  @Override
  public boolean isConversationHostFinishing() {
    return isFinishing();
  }

  @Override
  public void onConversationSendComplete(long threadId) {}

  @Override
  public void openVerifyIdentity(long recipientId, int subscriptionId) {
    Bundle arguments = VerifyIdentityFragment.arguments(recipientId, subscriptionId);
    ConversationListDestination.VERIFY_IDENTITY.requireAllowedArguments(arguments);
    navController.navigate(ConversationListDestination.VERIFY_IDENTITY.getId(), arguments);
  }

  @Override
  public void openRecipientPreferences(long[] recipientIds) {
    navigateToRecipientPreferences(RecipientPreferenceFragment.arguments(recipientIds));
  }

  @Override
  public void openMediaOverview(long threadId, long recipientId) {
    Bundle arguments = MediaOverviewFragment.arguments(threadId, recipientId);
    ConversationListDestination.MEDIA_OVERVIEW.requireAllowedArguments(arguments);
    if (ConversationListDestination.from(navController) == ConversationListDestination.MEDIA_PREVIEW &&
        navController.getPreviousBackStackEntry() != null &&
        navController.getPreviousBackStackEntry().getDestination().getId() == R.id.media_overview) {
      navController.navigateUp();
    } else {
      navController.navigate(ConversationListDestination.MEDIA_OVERVIEW.getId(), arguments);
    }
  }

  @Override
  public void openMediaPreview(long partRowId, long partUniqueId, long messageId, long threadId,
                               long recipientId, long date, long size) {
    Bundle arguments = MediaPreviewFragment.persistedArguments(
        partRowId, partUniqueId, messageId, threadId, recipientId, date, size);
    ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(arguments);
    navController.navigate(ConversationListDestination.MEDIA_PREVIEW.getId(), arguments);
  }

  @Override
  public void openDraftMediaPreview(android.net.Uri uri, String contentType, long size) {
    mediaPreviewDraftStore.put(MediaPreviewFragment.DRAFT_OWNER, uri, contentType, size);
    Bundle arguments = MediaPreviewFragment.draftArguments(size);
    ConversationListDestination.MEDIA_PREVIEW.requireAllowedArguments(arguments);
    navController.navigate(ConversationListDestination.MEDIA_PREVIEW.getId(), arguments);
  }

  @Override
  public void closeMediaPreview() {
    navController.navigateUp();
  }

  private void updateDestinationChrome(androidx.navigation.NavDestination destination) {
    int destinationId = destination.getId();
    boolean archived = destinationId == R.id.conversation_list_archive;
    boolean newConversation = destinationId == R.id.new_conversation;
    boolean contactSelection = destinationId == R.id.push_contact_selection;
    boolean conversation = destinationId == R.id.conversation_screen;
    boolean recipientPreferences = destinationId == R.id.recipient_preferences;
    if (newConversation || contactSelection || recipientPreferences) getSupportActionBar().hide();
    else                 getSupportActionBar().show();
    if (!conversation) {
      getSupportActionBar().setDisplayShowCustomEnabled(false);
      getSupportActionBar().setDisplayShowTitleEnabled(true);
      resetSystemBarColors();
    }
    getSupportActionBar().setDisplayHomeAsUpEnabled(
        destinationId != R.id.conversation_list_inbox && !newConversation && !contactSelection);
    if (!conversation) getSupportActionBar().setTitle(destination.getLabel());
  }

  @Override
  public void onAppearancePreferenceChanged(String key) {
    getWindow().getDecorView().post(() -> {
      if (key.equals(SilencePreferences.THEME_PREF)) {
        recreate();
      } else if (key.equals(SilencePreferences.LANGUAGE_PREF)) {
        recreate();
        Intent intent = new Intent(this, KeyCachingService.class);
        intent.setAction(KeyCachingService.LOCALE_CHANGE_EVENT);
        startService(intent);
      }
    });
  }

  private ConversationListFragment getCurrentFragment() {
    Fragment current = getCurrentDestinationFragment();
    return current instanceof ConversationListFragment ? (ConversationListFragment) current : null;
  }

  private Fragment getCurrentDestinationFragment() {
    Fragment host = getSupportFragmentManager().findFragmentByTag(ConversationListDestination.HOST_TAG);
    if (!(host instanceof NavHostFragment)) return null;
    return host.getChildFragmentManager().getPrimaryNavigationFragment();
  }

  private void createCurrentGroup() {
    Fragment host = getSupportFragmentManager().findFragmentByTag(ConversationListDestination.HOST_TAG);
    if (!(host instanceof NavHostFragment)) return;
    Fragment current = host.getChildFragmentManager().getPrimaryNavigationFragment();
    if (current instanceof GroupCreateFragment) ((GroupCreateFragment) current).createGroup();
  }

  @Override
  public void onMasterSecretCleared() {
    Fragment fragment = getCurrentDestinationFragment();
    if (fragment instanceof ConversationScreenFragment) {
      ((ConversationScreenFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof MessageDetailsFragment) {
      ((MessageDetailsFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof ImportExportFragment) {
      ((ImportExportFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof KeyScanningFragment) {
      ((KeyScanningFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof RecipientPreferenceFragment) {
      ((RecipientPreferenceFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof MediaOverviewFragment) {
      ((MediaOverviewFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof MediaPreviewFragment) {
      ((MediaPreviewFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof ConversationListFragment) {
      ((ConversationListFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof NewConversationFragment) {
      ((NewConversationFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof GroupCreateFragment) {
      ((GroupCreateFragment) fragment).clearSensitiveState();
    } else if (fragment instanceof PushContactSelectionFragment) {
      ((PushContactSelectionFragment) fragment).clearSensitiveState();
    }
    mediaPreviewDraftStore.clear();
    conversationPayloadStore.clear();
    ConflictIdentityStore.getInstance().clear();
    super.onMasterSecretCleared();
  }
}
