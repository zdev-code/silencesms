package org.smssecure.smssecure.domain.upgrade;

import android.content.Context;

import androidx.annotation.NonNull;

import org.smssecure.smssecure.util.Util;
import org.smssecure.smssecure.util.VersionTracker;

public final class DatabaseUpgradePolicy {
  public static final int ASK_FOR_SIM_CARD_VERSION = 143;
  public static final int MULTI_SIM_MULTI_KEYS_VERSION = 200;
  public static final int MERGE_EQUIVALENT_PHONE_THREADS_VERSION = 216;

  private static final int[] UPGRADE_VERSIONS = {
      ASK_FOR_SIM_CARD_VERSION,
      MULTI_SIM_MULTI_KEYS_VERSION,
      MERGE_EQUIVALENT_PHONE_THREADS_VERSION
  };

  private DatabaseUpgradePolicy() {}

  public static boolean isUpdate(@NonNull Context context) {
    return VersionTracker.getLastSeenVersion(context) <
        Util.getCurrentApkReleaseVersion(context);
  }

  public static boolean needsUpgrade(int lastSeenVersion, int currentVersion) {
    if (lastSeenVersion >= currentVersion) return false;

    for (int version : UPGRADE_VERSIONS) {
      if (lastSeenVersion < version) return true;
    }
    return false;
  }

  public interface ProgressListener {
    void setProgress(int progress, int total);
  }
}