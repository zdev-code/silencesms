package org.smssecure.smssecure;

import android.content.Intent;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import org.signal.libsignal.protocol.IdentityKey;
import org.smssecure.smssecure.domain.security.UnlockSession;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.Dialogs;
import org.smssecure.smssecure.util.Hex;

abstract class KeyScanningFragment extends Fragment {
  private static final String TAG = KeyScanningFragment.class.getSimpleName();

  private final ActivityResultLauncher<ScanOptions> scanLauncher =
      registerForActivityResult(new ScanContract(), this::handleScanResult);

  private IdentityScanBinding pendingScanBinding;
  private AlertDialog qrDialog;
  private ImageView qrImage;

  void populateOptionsMenu(@NonNull Menu menu) {
    MenuInflater inflater = requireActivity().getMenuInflater();
    menu.clear();
    inflater.inflate(R.menu.key_scanning, menu);
    menu.findItem(R.id.menu_scan).setTitle(getScanString());
    menu.findItem(R.id.menu_get_scanned).setTitle(getDisplayString());
  }

  boolean handleOptionsItem(@NonNull MenuItem item) {
    int itemId = item.getItemId();
    if (itemId == R.id.menu_scan) {
      initiateScan();
      return true;
    } else if (itemId == R.id.menu_get_scanned) {
      initiateDisplay();
      return true;
    } else if (itemId == R.id.menu_share_fingerprint) {
      initiateShare();
      return true;
    }
    return false;
  }

  private void initiateScan() {
    UnlockSession unlockSession = UnlockSession.capture();
    IdentityKey identityKey = resolveIdentityKeyToCompare(unlockSession);
    if (identityKey == null) {
      showMissingComparisonKey();
      return;
    }

    clearPendingScan();
    pendingScanBinding = new IdentityScanBinding(unlockSession, getExpectedRecipientId(),
                           getExpectedSubscriptionId(), identityKey.serialize());
    scanLauncher.launch(new ScanOptions().setOrientationLocked(false)
                                          .setBeepEnabled(false)
                                          .setPrompt(getScanString())
                                          .setBarcodeImageEnabled(false));
  }

  private void handleScanResult(ScanIntentResult result) {
    IdentityScanBinding binding = pendingScanBinding;
    pendingScanBinding = null;
    if (binding == null) return;
    IdentityKey currentKey = resolveIdentityKeyToCompare(UnlockSession.capture());
    IdentityScanBinding.Result bindingResult = binding.consume(
        getExpectedRecipientId(), getExpectedSubscriptionId(),
        currentKey == null ? null : currentKey.serialize(), result.getContents());
    if (bindingResult == IdentityScanBinding.Result.EMPTY) {
      Toast.makeText(requireContext(), R.string.KeyScanningActivity_no_scanned_key_found_exclamation,
                     Toast.LENGTH_LONG).show();
    } else if (bindingResult == IdentityScanBinding.Result.VERIFIED) {
      Dialogs.showInfoDialog(requireContext(), getVerifiedTitle(), getVerifiedMessage());
    } else if (bindingResult == IdentityScanBinding.Result.NOT_VERIFIED) {
      Dialogs.showAlertDialog(requireContext(), getNotVerifiedTitle(), getNotVerifiedMessage());
    }
  }

  private void initiateDisplay() {
    IdentityKey identityKey = resolveIdentityKeyToDisplay(UnlockSession.capture());
    if (identityKey == null) {
      Toast.makeText(requireContext(), R.string.KeyScanningActivity_no_identity_key,
                     Toast.LENGTH_LONG).show();
      return;
    }

    try {
      int qrSize = getQrCodeSizePx();
      Bitmap bitmap = new BarcodeEncoder().encodeBitmap(
          Base64.encodeBytes(identityKey.serialize()), BarcodeFormat.QR_CODE, qrSize, qrSize);
      qrImage = new ImageView(requireContext());
      int padding = dpToPx(16);
      qrImage.setPadding(padding, padding, padding, padding);
      qrImage.setAdjustViewBounds(true);
      qrImage.setImageBitmap(bitmap);
      qrImage.setContentDescription(getDisplayString());
      qrDialog = new AlertDialog.Builder(requireContext())
          .setTitle(getDisplayString())
          .setView(qrImage)
          .setPositiveButton(android.R.string.ok, null)
          .create();
      qrDialog.setOnDismissListener(dialog -> clearQrState());
      qrDialog.show();
    } catch (WriterException exception) {
      Log.w(TAG, "Unable to generate QR code", exception);
      Toast.makeText(requireContext(), R.string.KeyScanningActivity_unable_to_generate_qr_code,
                     Toast.LENGTH_LONG).show();
    }
  }

  private void initiateShare() {
    IdentityKey identityKey = resolveIdentityKeyToDisplay(UnlockSession.capture());
    if (identityKey == null) {
      Toast.makeText(requireContext(), R.string.KeyScanningActivity_no_identity_key,
                     Toast.LENGTH_LONG).show();
      return;
    }
    Intent shareIntent = new Intent(Intent.ACTION_SEND);
    shareIntent.setType("text/plain");
    shareIntent.putExtra(Intent.EXTRA_TEXT, Hex.toString(identityKey.serialize()));
    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_identity_fingerprint)));
  }

  void clearSensitiveState() {
    clearPendingScan();
    clearQrState();
    clearRenderedIdentity();
  }

  private void clearPendingScan() {
    if (pendingScanBinding != null) pendingScanBinding.clear();
    pendingScanBinding = null;
  }

  private void clearQrState() {
    AlertDialog dialog = qrDialog;
    qrDialog = null;
    if (qrImage != null) qrImage.setImageDrawable(null);
    qrImage = null;
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
  }

  @Override
  public void onDestroyView() {
    clearSensitiveState();
    super.onDestroyView();
  }

  private int getQrCodeSizePx() {
    DisplayMetrics metrics = getResources().getDisplayMetrics();
    int maxSize = Math.min(metrics.widthPixels, metrics.heightPixels);
    int available = maxSize - dpToPx(64);
    if (available <= 0) available = maxSize;
    return Math.max(dpToPx(160), Math.min(dpToPx(280), available));
  }

  private int dpToPx(int dp) {
    return Math.round(dp * getResources().getDisplayMetrics().density);
  }

  protected void showMissingComparisonKey() {
    Toast.makeText(requireContext(), R.string.KeyScanningActivity_no_identity_key,
                   Toast.LENGTH_LONG).show();
  }

  protected abstract int getExpectedSubscriptionId();
  protected abstract long getExpectedRecipientId();
  protected abstract @Nullable IdentityKey resolveIdentityKeyToCompare(@NonNull UnlockSession session);
  protected abstract @Nullable IdentityKey resolveIdentityKeyToDisplay(@NonNull UnlockSession session);
  protected abstract void clearRenderedIdentity();
  protected abstract String getScanString();
  protected abstract String getDisplayString();
  protected abstract String getNotVerifiedTitle();
  protected abstract String getNotVerifiedMessage();
  protected abstract String getVerifiedTitle();
  protected abstract String getVerifiedMessage();
}