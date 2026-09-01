package org.smssecure.smssecure.permissions;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import android.view.ViewGroup;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.util.LRUCache;
import org.smssecure.smssecure.util.ServiceUtil;
import org.smssecure.smssecure.util.WindowSizeCompat;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class Permissions {

  private static final Map<Integer, PermissionsRequest> OUTSTANDING = new LRUCache<>(2);
  private static Integer activeFragmentRequestCode;

  public static PermissionsBuilder with(@NonNull Activity activity) {
    return new PermissionsBuilder(new ActivityPermissionObject(activity));
  }

  public static PermissionsBuilder with(@NonNull Fragment fragment, @NonNull FragmentPermissionLauncher launcher) {
    return new PermissionsBuilder(new FragmentPermissionObject(fragment, launcher));
  }

  public static FragmentPermissionLauncher registerForResult(@NonNull Fragment fragment) {
    return new FragmentPermissionLauncher(fragment);
  }

  public static class PermissionsBuilder {

    private final PermissionObject permissionObject;

    private String[] requestedPermissions;

    private Runnable allGrantedListener;

    private Runnable anyDeniedListener;
    private Runnable anyPermanentlyDeniedListener;
    private Runnable anyResultListener;

    private Consumer<List<String>> someGrantedListener;
    private Consumer<List<String>> someDeniedListener;
    private Consumer<List<String>> somePermanentlyDeniedListener;

    private @DrawableRes int[]  rationalDialogHeader;
    private              String rationaleDialogMessage;

    private boolean ifNecesary;

    private boolean condition = true;

    PermissionsBuilder(PermissionObject permissionObject) {
      this.permissionObject = permissionObject;
    }

    public PermissionsBuilder request(String... requestedPermissions) {
      this.requestedPermissions = requestedPermissions;
      return this;
    }

    public PermissionsBuilder ifNecessary() {
      this.ifNecesary = true;
      return this;
    }

    public PermissionsBuilder ifNecessary(boolean condition) {
      this.ifNecesary = true;
      this.condition  = condition;
      return this;
    }

    public PermissionsBuilder withRationaleDialog(@NonNull String message, @NonNull @DrawableRes int... headers) {
      this.rationalDialogHeader   = headers;
      this.rationaleDialogMessage = message;
      return this;
    }

    public PermissionsBuilder withPermanentDenialDialog(@NonNull String message) {
      return onAnyPermanentlyDenied(new SettingsDialogListener(permissionObject.getContext(), message));
    }

    public PermissionsBuilder onAllGranted(Runnable allGrantedListener) {
      this.allGrantedListener = allGrantedListener;
      return this;
    }

    public PermissionsBuilder onAnyDenied(Runnable anyDeniedListener) {
      this.anyDeniedListener = anyDeniedListener;
      return this;
    }

    @SuppressWarnings("WeakerAccess")
    public PermissionsBuilder onAnyPermanentlyDenied(Runnable anyPermanentlyDeniedListener) {
      this.anyPermanentlyDeniedListener = anyPermanentlyDeniedListener;
      return this;
    }

    public PermissionsBuilder onAnyResult(Runnable anyResultListener) {
      this.anyResultListener = anyResultListener;
      return this;
    }

    public PermissionsBuilder onSomeGranted(Consumer<List<String>> someGrantedListener) {
      this.someGrantedListener = someGrantedListener;
      return this;
    }

    public PermissionsBuilder onSomeDenied(Consumer<List<String>> someDeniedListener) {
      this.someDeniedListener = someDeniedListener;
      return this;
    }

    public PermissionsBuilder onSomePermanentlyDenied(Consumer<List<String>> somePermanentlyDeniedListener) {
      this.somePermanentlyDeniedListener = somePermanentlyDeniedListener;
      return this;
    }

    public void execute() {
      PermissionsRequest request = new PermissionsRequest(allGrantedListener, anyDeniedListener, anyPermanentlyDeniedListener, anyResultListener,
                                                          someGrantedListener, someDeniedListener, somePermanentlyDeniedListener);

      if (ifNecesary && (permissionObject.hasAll(requestedPermissions) || !condition)) {
        executePreGrantedPermissionsRequest(request);
      } else if (rationaleDialogMessage != null && rationalDialogHeader != null) {
        executePermissionsRequestWithRationale(request);
      } else {
        executePermissionsRequest(request);
      }
    }

    private void executePreGrantedPermissionsRequest(PermissionsRequest request) {
      int[] grantResults = new int[requestedPermissions.length];
      for (int i=0;i<grantResults.length;i++) grantResults[i] = PackageManager.PERMISSION_GRANTED;

      request.onResult(requestedPermissions, grantResults, new boolean[requestedPermissions.length]);
    }

    @SuppressWarnings("ConstantConditions")
    private void executePermissionsRequestWithRationale(PermissionsRequest request) {
      RationaleDialog.createFor(permissionObject.getContext(), rationaleDialogMessage, rationalDialogHeader)
                     .setPositiveButton(R.string.Permissions_continue, (dialog, which) -> executePermissionsRequest(request))
                     .setNegativeButton(R.string.Permissions_not_now, (dialog, which) -> executeNoPermissionsRequest(request))
                     .show()
                     .getWindow()
                     .setLayout((int)(permissionObject.getWindowWidth() * .75), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void executePermissionsRequest(PermissionsRequest request) {
      int requestCode = new SecureRandom().nextInt(65434) + 100;

      synchronized (OUTSTANDING) {
        OUTSTANDING.put(requestCode, request);
      }

      for (String permission : requestedPermissions) {
        request.addMapping(permission, permissionObject.shouldShouldPermissionRationale(permission));
      }

      permissionObject.requestPermissions(requestCode, requestedPermissions);
    }

    private void executeNoPermissionsRequest(PermissionsRequest request) {
      for (String permission : requestedPermissions) {
        request.addMapping(permission, true);
      }

      String[] permissions  = filterNotGranted(permissionObject.getContext(), requestedPermissions);
      int[]    grantResults = Arrays.stream(permissions).mapToInt(permission -> PackageManager.PERMISSION_DENIED).toArray();
      boolean[] showDialog   = new boolean[permissions.length];
      Arrays.fill(showDialog, true);

      request.onResult(permissions, grantResults, showDialog);
    }

  }

  private static void requestPermissions(@NonNull Activity activity, int requestCode, String... permissions) {
    ActivityCompat.requestPermissions(activity, filterNotGranted(activity, permissions), requestCode);
  }

  private static String[] filterNotGranted(@NonNull Context context, String... permissions) {
    return Arrays.stream(permissions)
                 .filter(permission -> ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED)
                 .toArray(String[]::new);
  }

  public static boolean hasAny(@NonNull Context context, String... permissions) {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Arrays.stream(permissions).anyMatch(permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED);

  }

  public static boolean hasAll(@NonNull Context context, String... permissions) {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Arrays.stream(permissions).allMatch(permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED);

  }

  public static void onRequestPermissionsResult(Activity activity, int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    onRequestPermissionsResult(new ActivityPermissionObject(activity), requestCode, permissions, grantResults);
  }

  private static void onRequestPermissionsResult(@NonNull PermissionObject context, int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    PermissionsRequest resultListener;

    synchronized (OUTSTANDING) {
      resultListener = OUTSTANDING.remove(requestCode);
    }

    if (resultListener == null) return;

    boolean[] shouldShowRationaleDialog = new boolean[permissions.length];

    for (int i=0;i<permissions.length;i++) {
      if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
        shouldShowRationaleDialog[i] = context.shouldShouldPermissionRationale(permissions[i]);
      }
    }

    resultListener.onResult(permissions, grantResults, shouldShowRationaleDialog);
  }

  private static Intent getApplicationSettingsIntent(@NonNull Context context) {
    Intent intent = new Intent();
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    Uri uri = Uri.fromParts("package", context.getPackageName(), null);
    intent.setData(uri);

    return intent;
  }

  private abstract static class PermissionObject {

    abstract Context getContext();
    abstract boolean shouldShouldPermissionRationale(String permission);
    abstract boolean hasAll(String... permissions);
    abstract void requestPermissions(int requestCode, String... permissions);

    int getWindowWidth() {
      return WindowSizeCompat.getWindowWidth(getContext());
    }
  }

  private static class ActivityPermissionObject extends PermissionObject {

    private Activity activity;

    ActivityPermissionObject(@NonNull Activity activity) {
      this.activity = activity;
    }

    @Override
    public Context getContext() {
      return activity;
    }

    @Override
    public boolean shouldShouldPermissionRationale(String permission) {
      return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }

    @Override
    public boolean hasAll(String... permissions) {
      return Permissions.hasAll(activity, permissions);
    }

    @Override
    public void requestPermissions(int requestCode, String... permissions) {
      Permissions.requestPermissions(activity, requestCode, permissions);
    }
  }

  public static final class FragmentPermissionLauncher {

    private final Fragment fragment;
    private final ActivityResultLauncher<String[]> launcher;

    private FragmentPermissionLauncher(@NonNull Fragment fragment) {
      this.fragment = fragment;
      this.launcher = fragment.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onResult);
    }

    private void launch(int requestCode, String... permissions) {
      synchronized (OUTSTANDING) {
        if (activeFragmentRequestCode != null) {
          OUTSTANDING.remove(requestCode);
          throw new IllegalStateException("A fragment permission request is already active");
        }
        activeFragmentRequestCode = requestCode;
      }

      launcher.launch(filterNotGranted(fragment.requireContext(), permissions));
    }

    private void onResult(Map<String, Boolean> results) {
      int requestCode;

      synchronized (OUTSTANDING) {
        if (activeFragmentRequestCode == null) return;
        requestCode = activeFragmentRequestCode;
        activeFragmentRequestCode = null;
      }

      String[] permissions = results.keySet().toArray(new String[0]);
      int[] grantResults = results.values().stream()
                                  .mapToInt(granted -> granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED)
                                  .toArray();

      onRequestPermissionsResult(new FragmentPermissionObject(fragment, this), requestCode, permissions, grantResults);
    }
  }

  private static class FragmentPermissionObject extends PermissionObject {

    private Fragment fragment;
    private FragmentPermissionLauncher launcher;

    FragmentPermissionObject(@NonNull Fragment fragment, @NonNull FragmentPermissionLauncher launcher) {
      this.fragment = fragment;
      this.launcher = launcher;
    }

    @Override
    public Context getContext() {
      return fragment.getContext();
    }

    @Override
    public boolean shouldShouldPermissionRationale(String permission) {
      return fragment.shouldShowRequestPermissionRationale(permission);
    }

    @Override
    public boolean hasAll(String... permissions) {
      return Permissions.hasAll(fragment.getContext(), permissions);
    }

    @Override
    public void requestPermissions(int requestCode, String... permissions) {
      launcher.launch(requestCode, permissions);
    }
  }

  private static class SettingsDialogListener implements Runnable {

    private final WeakReference<Context> context;
    private final String                 message;

    SettingsDialogListener(Context context, String message) {
      this.message = message;
      this.context = new WeakReference<>(context);
    }

    @Override
    public void run() {
      Context context = this.context.get();

      if (context != null) {
        new AlertDialog.Builder(context)
            .setTitle(R.string.Permissions_permission_required)
            .setMessage(message)
            .setPositiveButton(R.string.Permissions_continue, (dialog, which) -> context.startActivity(getApplicationSettingsIntent(context)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
      }
    }
  }
}
