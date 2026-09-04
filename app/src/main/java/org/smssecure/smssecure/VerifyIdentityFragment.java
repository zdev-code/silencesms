package org.smssecure.smssecure;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BundleCompat;
import androidx.navigation.fragment.NavHostFragment;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.crypto.storage.VendoredSessionStore;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.identity.ConflictIdentityStore;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.Hex;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;

public final class VerifyIdentityFragment extends KeyScanningFragment {
  static final String RECIPIENT_ID_ARGUMENT = "verify_identity.recipient_id";
  static final String SUBSCRIPTION_ID_ARGUMENT = "verify_identity.subscription_id";
  static final String CONFLICT_TOKEN_ARGUMENT = "verify_identity.conflict_token";
  static final String CONFLICT_OWNER = "verify-identity-destination";
  private static final int MAX_TOKEN_LENGTH = 128;

  private TextView localIdentityFingerprint;
  private TextView remoteIdentityFingerprint;
  private IdentityKey injectedRemoteIdentity;
  private long recipientId;
  private int subscriptionId;
  private boolean conflictIdentity;
  private boolean invalidConflict;

  static Bundle arguments(long recipientId, int subscriptionId) {
    if (recipientId < 0 || subscriptionId < 0) {
      throw new SecurityException("Invalid identity verification IDs");
    }
    Bundle arguments = new Bundle();
    arguments.putLong(RECIPIENT_ID_ARGUMENT, recipientId);
    arguments.putInt(SUBSCRIPTION_ID_ARGUMENT, subscriptionId);
    requireValidArguments(arguments);
    return arguments;
  }

  static Bundle conflictArguments(@NonNull String token) {
    if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
      throw new SecurityException("Invalid conflict identity token");
    }
    Bundle arguments = new Bundle();
    arguments.putString(CONFLICT_TOKEN_ARGUMENT, token);
    requireValidArguments(arguments);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    boolean hasRecipient = arguments.containsKey(RECIPIENT_ID_ARGUMENT);
    boolean hasSubscription = arguments.containsKey(SUBSCRIPTION_ID_ARGUMENT);
    boolean hasToken = arguments.containsKey(CONFLICT_TOKEN_ARGUMENT);
    if (hasToken) {
      if (hasRecipient || hasSubscription || arguments.size() != 1) {
        throw new SecurityException("Conflict identity navigation must contain only a token");
      }
      String token = BundleCompat.getSerializable(arguments, CONFLICT_TOKEN_ARGUMENT, String.class);
      if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
        throw new SecurityException("Invalid conflict identity token");
      }
      return;
    }
    if (!hasRecipient || !hasSubscription || arguments.size() != 2) {
      throw new SecurityException("Identity verification requires recipient and subscription IDs");
    }
    Long recipient = BundleCompat.getSerializable(arguments, RECIPIENT_ID_ARGUMENT, Long.class);
    Integer subscription = BundleCompat.getSerializable(
        arguments, SUBSCRIPTION_ID_ARGUMENT, Integer.class);
    if (recipient == null || recipient < 0L || subscription == null || subscription < 0) {
      throw new SecurityException("Invalid identity verification ID types or values");
    }
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Bundle arguments = requireArguments();
    requireValidArguments(arguments);
    if (arguments.containsKey(CONFLICT_TOKEN_ARGUMENT)) {
      conflictIdentity = true;
      try {
        ConflictIdentityStore.Payload payload = ConflictIdentityStore.getInstance().consume(
            arguments.getString(CONFLICT_TOKEN_ARGUMENT), CONFLICT_OWNER);
        recipientId = payload.getRecipientId();
        subscriptionId = payload.getSubscriptionId();
        if (recipientId < 0L || subscriptionId < 0) throw new SecurityException("Invalid conflict IDs");
        injectedRemoteIdentity = payload.getIdentityKey();
      } catch (ConflictIdentityStore.InvalidPayloadException | SecurityException error) {
        invalidConflict = true;
      }
    } else {
      recipientId = arguments.getLong(RECIPIENT_ID_ARGUMENT);
      subscriptionId = arguments.getInt(SUBSCRIPTION_ID_ARGUMENT);
    }
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.verify_identity_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    localIdentityFingerprint = view.findViewById(R.id.you_read);
    remoteIdentityFingerprint = view.findViewById(R.id.friend_reads);
    if (invalidConflict) {
      view.post(() -> {
        if (isAdded()) NavHostFragment.findNavController(this).navigateUp();
      });
      return;
    }
    renderFingerprints();
  }

  @Override
  public void onResume() {
    super.onResume();
    renderFingerprints();
  }

  private void renderFingerprints() {
    if (localIdentityFingerprint == null || remoteIdentityFingerprint == null || invalidConflict) {
      return;
    }
    IdentityKey localIdentity = resolveIdentityKeyToDisplay(UnlockSession.capture());
    localIdentityFingerprint.setText(localIdentity == null
        ? getString(R.string.VerifyIdentityActivity_you_do_not_have_an_identity_key)
        : Hex.toString(localIdentity.serialize()));

    IdentityKey remoteIdentity = resolveIdentityKeyToCompare(UnlockSession.capture());
    remoteIdentityFingerprint.setText(remoteIdentity == null
        ? getString(R.string.VerifyIdentityActivity_recipient_has_no_identity_key)
        : Hex.toString(remoteIdentity.serialize()));
  }

  @Override
  protected IdentityKey resolveIdentityKeyToCompare(@NonNull UnlockSession session) {
    try {
      return new ConversationUnlockCapability(session).use(masterSecret -> {
        if (injectedRemoteIdentity != null) return injectedRemoteIdentity;
        Recipient recipient = RecipientFactory.getRecipientForId(requireContext(), recipientId, true);
        SessionStore sessionStore = new VendoredSessionStore(requireContext(), masterSecret, subscriptionId);
        SessionRecord record = sessionStore.loadSession(new SignalProtocolAddress(recipient.getNumber(), 1));
        if (record == null || record.getSessionState().getRemoteIdentityKey() == null) return null;
        try {
          return new IdentityKey(record.getSessionState().getRemoteIdentityKey().serialize(), 0);
        } catch (InvalidKeyException exception) {
          throw new AssertionError(exception);
        }
      });
    } catch (Exception exception) {
      return null;
    }
  }

  @Override
  protected IdentityKey resolveIdentityKeyToDisplay(@NonNull UnlockSession session) {
    try {
      return new ConversationUnlockCapability(session).use(masterSecret ->
          IdentityKeyUtil.hasIdentityKey(requireContext(), subscriptionId)
              ? IdentityKeyUtil.getIdentityKey(requireContext(), subscriptionId) : null);
    } catch (Exception exception) {
      return null;
    }
  }

  @Override
  protected void showMissingComparisonKey() {
    Toast.makeText(requireContext(), R.string.VerifyIdentityActivity_recipient_has_no_identity_key,
                   Toast.LENGTH_LONG).show();
  }

  @Override protected int getExpectedSubscriptionId() { return subscriptionId; }
  @Override protected long getExpectedRecipientId() { return recipientId; }
  @Override protected void clearRenderedIdentity() {
    injectedRemoteIdentity = null;
    if (conflictIdentity) invalidConflict = true;
    if (localIdentityFingerprint != null) localIdentityFingerprint.setText("");
    if (remoteIdentityFingerprint != null) remoteIdentityFingerprint.setText("");
    localIdentityFingerprint = null;
    remoteIdentityFingerprint = null;
  }
  @Override protected String getScanString() {
    return getString(R.string.VerifyIdentityActivity_scan_contacts_qr_code);
  }
  @Override protected String getDisplayString() {
    return getString(R.string.VerifyIdentityActivity_display_your_qr_code);
  }
  @Override protected String getNotVerifiedMessage() {
    return getString(R.string.VerifyIdentityActivity_warning_the_scanned_key_does_not_match_please_check_the_fingerprint_text_carefully);
  }
  @Override protected String getNotVerifiedTitle() {
    return getString(R.string.VerifyIdentityActivity_not_verified_exclamation);
  }
  @Override protected String getVerifiedMessage() {
    return getString(R.string.VerifyIdentityActivity_their_key_is_correct_it_is_also_necessary_to_verify_your_key_with_them_as_well);
  }
  @Override protected String getVerifiedTitle() {
    return getString(R.string.VerifyIdentityActivity_verified_exclamation);
  }
}