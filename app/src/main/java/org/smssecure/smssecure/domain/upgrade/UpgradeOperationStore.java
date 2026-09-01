package org.smssecure.smssecure.domain.upgrade;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Objects;

final class UpgradeOperationStore {
  enum Stage { DATABASE, SIM_PROMPT, MULTI_SIM, FINALIZE, COMPLETE }

  static final class Record {
    private final int fromVersion;
    private final int targetVersion;
    private final Stage stage;

    Record(int fromVersion, int targetVersion, Stage stage) {
      this.fromVersion = fromVersion;
      this.targetVersion = targetVersion;
      this.stage = stage;
    }

    int getFromVersion() { return fromVersion; }
    int getTargetVersion() { return targetVersion; }
    Stage getStage() { return stage; }
  }

  interface Storage {
    Record read();
    void write(Record record);
    void clear();
  }

  static final class PreferencesStorage implements Storage {
    private static final String NAME = "database-upgrade-operation";
    private static final String FROM_VERSION = "from_version";
    private static final String TARGET_VERSION = "target_version";
    private static final String STAGE = "stage";
    private final SharedPreferences preferences;

    PreferencesStorage(Context context) {
      preferences = Objects.requireNonNull(context).getApplicationContext()
          .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    @Override public Record read() {
      if (!preferences.contains(STAGE)) return null;
      try {
        return new Record(preferences.getInt(FROM_VERSION, 0),
            preferences.getInt(TARGET_VERSION, 0),
            Stage.valueOf(preferences.getString(STAGE, Stage.DATABASE.name())));
      } catch (IllegalArgumentException exception) {
        clear();
        return null;
      }
    }

    @Override public void write(Record record) {
      boolean committed = preferences.edit()
          .putInt(FROM_VERSION, record.getFromVersion())
          .putInt(TARGET_VERSION, record.getTargetVersion())
          .putString(STAGE, record.getStage().name())
          .commit();
      if (!committed) throw new IllegalStateException("Unable to persist database upgrade checkpoint");
    }

    @Override public void clear() {
      if (!preferences.edit().clear().commit()) {
        throw new IllegalStateException("Unable to clear database upgrade checkpoint");
      }
    }
  }
}
