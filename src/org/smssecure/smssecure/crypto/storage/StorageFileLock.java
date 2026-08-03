package org.smssecure.smssecure.crypto.storage;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Vends process-wide monitor objects keyed by the canonical path of a crypto backing directory.
 *
 * <p>The hybrid crypto setup runs two libsignal libraries (maintained + vendored) whose stores back
 * the SAME on-disk files — for example {@link SilenceSessionStore} and {@link VendoredSessionStore}
 * both read and write {@code sessions-v2}, and {@link SilencePreKeyStore} shares
 * {@code prekeys}/{@code signed_prekeys} with {@link VendoredPreKeyStore}. If each store
 * synchronizes on its own private lock, a maintained-library ratchet write can race a vendored
 * Key-Exchange write over the same file, permitting partial reads, truncation races, or a lost
 * update. Routing every store that touches a given directory through one shared monitor serializes
 * all access to those files.
 *
 * <p>Locks are keyed per backing directory (not per store class), so an isolated store such as
 * {@link KeyExchangeSessionStore} — which uses its own {@code sessions-v2-kex} directory — gets a
 * distinct lock and does not contend with the shared session store.
 */
public final class StorageFileLock {

  private static final String TAG = StorageFileLock.class.getSimpleName();

  private static final Map<String, Object> LOCKS = new HashMap<>();

  private StorageFileLock() {}

  public static synchronized Object forDirectory(File directory) {
    String key;

    try {
      key = directory.getCanonicalPath();
    } catch (IOException e) {
      Log.w(TAG, "Falling back to absolute path for lock key.", e);
      key = directory.getAbsolutePath();
    }

    Object lock = LOCKS.get(key);
    if (lock == null) {
      lock = new Object();
      LOCKS.put(key, lock);
    }

    return lock;
  }
}
