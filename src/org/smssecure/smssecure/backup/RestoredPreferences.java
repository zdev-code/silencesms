package org.smssecure.smssecure.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.smssecure.smssecure.crypto.MasterSecretUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RestoredPreferences {
  private RestoredPreferences() {}

  public static Snapshot captureMainPreferences(Context context) {
    return new Snapshot(context, copyValues(context.getSharedPreferences(
        MasterSecretUtil.PREFERENCES_NAME, 0).getAll()));
  }

  /**
   * Realigns the cached main {@link SharedPreferences} with the values the restore already wrote
   * to disk. Android caches one instance per file, so without this the restoring process keeps
   * serving pre-restore values and writes them back over the restored XML on its next commit.
   *
   * <p>The values come from {@link SecureBackupArchive#RESTORED_PREFERENCES_NAME}, a duplicate the
   * commit installs under a name nothing has opened, so the framework parses them with their
   * original types. The write is content-identical to what is already on disk and therefore
   * carries no state of its own; failing or dying part way through cannot desynchronise the
   * restore.
   */
  public static void resyncMainPreferences(Context context) throws IOException {
    SharedPreferences source = context.getSharedPreferences(
        SecureBackupArchive.RESTORED_PREFERENCES_NAME, 0);
    Map<String, ?> restored = source.getAll();
    if (restored.isEmpty()) return;

    apply(context.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, 0), restored);
    source.edit().clear().commit();
  }

  private static void apply(SharedPreferences preferences, Map<String, ?> values)
      throws IOException
  {
    SharedPreferences.Editor editor = preferences.edit().clear();
    for (Map.Entry<String, ?> value : values.entrySet()) put(editor, value.getKey(), value.getValue());
    if (!editor.commit()) throw new IOException("Unable to apply restored preferences");
  }

  private static Map<String, ?> copyValues(Map<String, ?> values) {
    Map<String, Object> copy = new HashMap<>();
    for (Map.Entry<String, ?> value : values.entrySet()) {
      Object item = value.getValue();
      copy.put(value.getKey(), item instanceof Set ? new HashSet<>((Set<?>) item) : item);
    }
    return copy;
  }

  public static final class Snapshot {
    private final Context context;
    private final Map<String, ?> values;

    private Snapshot(Context context, Map<String, ?> values) {
      this.context = context;
      this.values = values;
    }

    public void restore() throws IOException {
      apply(context.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, 0), values);
    }
  }

  @SuppressWarnings("unchecked")
  private static void put(SharedPreferences.Editor editor, String key, Object value)
      throws IOException
  {
    if (value instanceof String) editor.putString(key, (String) value);
    else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
    else if (value instanceof Long) editor.putLong(key, (Long) value);
    else if (value instanceof Float) editor.putFloat(key, (Float) value);
    else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
    else throw new IOException("Unsupported restored preference type for " + key);
  }
}
