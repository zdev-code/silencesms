package org.smssecure.smssecure.database;

import java.util.HashMap;
import java.util.Map;

final class BackupMessageDeduplicator {

  private final Map<String, Integer> existing = new HashMap<>();
  private final Map<String, Integer> seen = new HashMap<>();

  void addExisting(String fingerprint) {
    existing.merge(fingerprint, 1, Integer::sum);
  }

  boolean shouldImport(String fingerprint) {
    int occurrence = seen.merge(fingerprint, 1, Integer::sum);
    return occurrence > existing.getOrDefault(fingerprint, 0);
  }
}