package org.smssecure.smssecure;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.libsignal.protocol.IdentityKey;
import org.smssecure.smssecure.crypto.IdentityKeyUtil;
import org.smssecure.smssecure.domain.conversation.ConversationUnlockCapability;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.util.Hex;

public final class ViewIdentityFragment extends KeyScanningFragment {
  static final String SUBSCRIPTION_ID_ARGUMENT = "identity.subscription_id";

  private TextView identityFingerprint;
  private int subscriptionId;

  static Bundle arguments(int subscriptionId) {
    if (subscriptionId < 0) throw new SecurityException("Invalid identity subscription ID");
    Bundle arguments = new Bundle();
    arguments.putInt(SUBSCRIPTION_ID_ARGUMENT, subscriptionId);
    requireValidArguments(arguments);
    return arguments;
  }

  static void requireValidArguments(@NonNull Bundle arguments) {
    try {
      if (!arguments.containsKey(SUBSCRIPTION_ID_ARGUMENT) ||
          arguments.getInt(SUBSCRIPTION_ID_ARGUMENT, -1) < 0) {
        throw new SecurityException("Invalid identity subscription ID");
      }
    } catch (ClassCastException exception) {
      throw new SecurityException("Invalid identity subscription ID");
    }
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.view_identity_activity, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    Bundle arguments = requireArguments();
    requireValidArguments(arguments);
    subscriptionId = arguments.getInt(SUBSCRIPTION_ID_ARGUMENT);
    identityFingerprint = view.findViewById(R.id.identity_fingerprint);
    renderIdentity();
  }

  private void renderIdentity() {
    IdentityKey identityKey = resolveIdentity(UnlockSession.capture());
    if (identityKey == null) {
      identityFingerprint.setText(R.string.VerifyIdentityActivity_you_do_not_have_an_identity_key);
    } else {
      identityFingerprint.setText(Hex.toString(identityKey.serialize()));
    }
  }

  private @Nullable IdentityKey resolveIdentity(@NonNull UnlockSession session) {
    try {
      return new ConversationUnlockCapability(session).use(
          masterSecret -> IdentityKeyUtil.getIdentityKey(requireContext(), subscriptionId));
    } catch (Exception exception) {
      return null;
    }
  }

  @Override protected int getExpectedSubscriptionId() { return subscriptionId; }
  @Override protected long getExpectedRecipientId() { return -1; }
  @Override protected IdentityKey resolveIdentityKeyToCompare(@NonNull UnlockSession session) {
    return resolveIdentity(session);
  }
  @Override protected IdentityKey resolveIdentityKeyToDisplay(@NonNull UnlockSession session) {
    return resolveIdentity(session);
  }
  @Override protected void clearRenderedIdentity() {
    if (identityFingerprint != null) identityFingerprint.setText("");
  }
  @Override protected String getScanString() {
    return getString(R.string.ViewIdentityActivity_scan_contacts_qr_code);
  }
  @Override protected String getDisplayString() {
    return getString(R.string.ViewIdentityActivity_display_your_qr_code);
  }
  @Override protected String getNotVerifiedMessage() {
    return getString(R.string.ViewIdentityActivity_warning_the_scanned_key_does_not_match_exclamation);
  }
  @Override protected String getNotVerifiedTitle() {
    return getString(R.string.VerifyIdentityActivity_not_verified_exclamation);
  }
  @Override protected String getVerifiedMessage() {
    return getString(R.string.ViewIdentityActivity_the_scanned_key_matches_exclamation);
  }
  @Override protected String getVerifiedTitle() {
    return getString(R.string.VerifyIdentityActivity_verified_exclamation);
  }
}