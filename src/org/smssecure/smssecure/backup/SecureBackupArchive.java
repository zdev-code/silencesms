package org.smssecure.smssecure.backup;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.smssecure.smssecure.crypto.MasterSecret;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecureBackupArchive {
  public static final int FORMAT_VERSION = 1;

  private static final String MANIFEST_ENTRY = "manifest.properties";
  private static final String MASTER_SECRET_ENTRY = "master-secret.bin";
  private static final String ARGON2_WRAPPER_ENTRY = "argon2-wrapper.bin";
  private static final String FILES_PREFIX = "files/";
  private static final byte[] MASTER_SECRET_MAGIC = new byte[] {'S', 'M', 'S', 'W'};
  private static final int MASTER_SECRET_LENGTH = 36;
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int BUFFER_SIZE = 16 * 1024;
  private static final String MAIN_PREFERENCES = "shared_prefs/SecureSMS-Preferences.xml";
  private static final String DEVICE_PREFERENCES = "shared_prefs/SecureSMS-Device-Preferences.xml";
  /** Preference file name the restored main preferences are duplicated under for the cache resync. */
  public static final String RESTORED_PREFERENCES_NAME = "SecureSMS-Preferences-Restored";
  private static final String RESTORED_PREFERENCES =
      "shared_prefs/" + RESTORED_PREFERENCES_NAME + ".xml";
  // Device-bound state is excluded from the archive but lives inside a directory the commit
  // replaces wholesale, so it has to be carried across the swap explicitly.
  private static final String[] PRESERVED_PATHS = { DEVICE_PREFERENCES };
  private static final String REPLACED_SUFFIX = ".restore-replaced";
  private static final String SWAP_JOURNAL = "no_backup/silence-restore-swap";
  private static final String SWAP_COMMITTED = "no_backup/silence-restore-committed";
  private static final int SWAP_JOURNAL_MAGIC = 0x53525354;
  private static final int SWAP_JOURNAL_VERSION = 1;
  private static final int MAX_ENTRY_COUNT = 100_000;
  private static final int MAX_CONTROL_ENTRY_BYTES = 64 * 1024;
  private static final long MAX_ENTRY_BYTES = 1L << 30;
  private static final long MAX_TOTAL_BYTES = 16L << 30;
  // Portable material travels in master-secret.bin / argon2-wrapper.bin instead, so no root-secret
  // wrapper is archived. The legacy PBKDF wrapper especially must not ride along with a backup.
  private static final Pattern ROOT_SECRET_PREFERENCES = Pattern.compile(
      "\\s*<(string|int)\\s+name=\"(?:master_secret_v2|master_secret_v2_pending|master_secret|" +
      "encryption_salt|mac_salt|passphrase_iterations)\"[^>]*(?:/>|>.*?</\\1>)\\s*",
      Pattern.DOTALL);

  private SecureBackupArchive() {}

  public static void write(OutputStream output, File appRoot, MasterSecret masterSecret,
                           byte[] argon2Wrapper, byte[] recoveryKey, long creationTimeMillis)
      throws IOException
  {
    if (output == null || appRoot == null || masterSecret == null || argon2Wrapper == null) {
      throw new IllegalArgumentException(
          "Backup output, app root, master secret, and Argon2 wrapper are required");
    }
    if (!appRoot.isDirectory()) throw new IOException("App data root is not a directory");

    try (OutputStream encrypted = SecureBackupContainer.encrypt(output, recoveryKey);
         ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(encrypted))) {
      writeEntry(zip, MANIFEST_ENTRY, manifest(creationTimeMillis));

      byte[] combinedSecret = combine(masterSecret);
      try {
        writeEntry(zip, MASTER_SECRET_ENTRY, wrapMasterSecret(combinedSecret, recoveryKey));
      } finally {
        Arrays.fill(combinedSecret, (byte) 0);
      }
      writeEntry(zip, ARGON2_WRAPPER_ENTRY, Arrays.copyOf(argon2Wrapper, argon2Wrapper.length));

      addDirectory(zip, appRoot, appRoot, "");
    }
  }

  public static RestoreResult readToStaging(InputStream input, File stagingDirectory,
                                            byte[] recoveryKey)
      throws IOException
  {
    if (input == null || stagingDirectory == null) {
      throw new IllegalArgumentException("Backup input and staging directory are required");
    }
    prepareEmptyDirectory(stagingDirectory);

    byte[] wrappedMasterSecret = null;
    byte[] argon2Wrapper = null;
    boolean validManifest = false;
    long extracted = 0;
    Set<String> entries = new HashSet<>();
    try (SecureBackupContainer.AuthenticatedInputStream decrypted =
             SecureBackupContainer.decrypt(input, recoveryKey);
         ZipInputStream zip = new ZipInputStream(new BufferedInputStream(decrypted))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        validateEntryName(name);
        if (!entries.add(name)) throw new IOException("Duplicate backup entry: " + name);
        if (entries.size() > MAX_ENTRY_COUNT) throw new IOException("Too many backup entries");

        if (MANIFEST_ENTRY.equals(name)) {
          validManifest = validateManifest(readEntry(zip, MAX_CONTROL_ENTRY_BYTES));
        } else if (MASTER_SECRET_ENTRY.equals(name)) {
          wrappedMasterSecret = readEntry(zip, MAX_CONTROL_ENTRY_BYTES);
        } else if (ARGON2_WRAPPER_ENTRY.equals(name)) {
          argon2Wrapper = readEntry(zip, MAX_CONTROL_ENTRY_BYTES);
        } else if (name.startsWith(FILES_PREFIX)) {
          extracted += extractEntry(zip, entry, stagingDirectory,
                                    name.substring(FILES_PREFIX.length()),
                                    Math.min(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - extracted));
        } else {
          throw new IOException("Unsupported backup entry: " + name);
        }
        zip.closeEntry();
      }

      // The zip parser stops at the central directory, so the tag covering the whole archive is
      // only checked here. Staging is discarded below if it fails.
      decrypted.verify();
    } catch (IOException error) {
      deleteRecursively(stagingDirectory);
      throw error;
    }

    if (!validManifest || wrappedMasterSecret == null || argon2Wrapper == null) {
      deleteRecursively(stagingDirectory);
      throw new IOException("Backup manifest or master secret is missing");
    }

    try {
      byte[] combinedSecret = unwrapMasterSecret(wrappedMasterSecret, recoveryKey);
      return new RestoreResult(stagingDirectory, masterSecret(combinedSecret), argon2Wrapper);
    } catch (IOException error) {
      deleteRecursively(stagingDirectory);
      throw error;
    } finally {
      Arrays.fill(wrappedMasterSecret, (byte) 0);
    }
  }

  public static RestoreTransaction beginStaging(File stagingDirectory, File appRoot)
      throws IOException
  {
    if (stagingDirectory == null || appRoot == null || !stagingDirectory.isDirectory() ||
        !appRoot.isDirectory()) {
      throw new IOException("Staging and app data roots must be directories");
    }
    recoverInterruptedRestore(appRoot);
    carryForwardPreserved(appRoot, stagingDirectory);
    stageRestoredPreferencesReplica(stagingDirectory);

    List<SwapEntry> entries = managedEntries(stagingDirectory, appRoot);
    writeJournal(appRoot, entries);
    try {
      for (SwapEntry entry : entries) {
        File live = new File(appRoot, entry.name);
        if (entry.existed) {
          File aside = new File(appRoot, entry.name + REPLACED_SUFFIX);
          deleteQuietly(aside);
          if (!live.renameTo(aside)) throw new IOException("Unable to set aside " + entry.name);
        }
        File staged = new File(stagingDirectory, entry.name);
        if (staged.exists()) {
          if (!staged.renameTo(new File(appRoot, entry.name))) {
            throw new IOException("Unable to install restored " + entry.name);
          }
        }
      }
      syncDirectory(appRoot);
    } catch (IOException error) {
      recoverInterruptedRestore(appRoot);
      throw error;
    }
    return new RestoreTransaction(appRoot, entries);
  }

  public static void commitStaging(File stagingDirectory, File appRoot) throws IOException {
    beginStaging(stagingDirectory, appRoot).commit();
  }

  public static void recoverInterruptedRestore(File appRoot) throws IOException {
    if (appRoot == null || !appRoot.isDirectory()) return;
    File journal = new File(appRoot, SWAP_JOURNAL);
    if (!journal.isFile()) {
      deleteQuietly(new File(appRoot, SWAP_COMMITTED));
      deleteQuietly(new File(appRoot, RESTORED_PREFERENCES));
      return;
    }
    List<SwapEntry> entries = readJournal(journal);
    if (new File(appRoot, SWAP_COMMITTED).isFile()) cleanCommitted(appRoot, entries, false);
    else rollBack(appRoot, entries);
  }

  private static void rollBack(File appRoot, List<SwapEntry> entries) throws IOException {
    for (SwapEntry entry : entries) {
      File live = new File(appRoot, entry.name);
      File aside = new File(appRoot, entry.name + REPLACED_SUFFIX);
      if (entry.existed) {
        if (!aside.exists()) continue;
        if (live.exists()) deleteRecursively(live);
        if (!aside.renameTo(live)) throw new IOException("Unable to restore " + entry.name);
      } else if (live.exists()) {
        deleteRecursively(live);
      }
    }
    syncDirectory(appRoot);
    finishJournal(appRoot);
    deleteQuietly(new File(appRoot, RESTORED_PREFERENCES));
  }

  private static void cleanCommitted(File appRoot, List<SwapEntry> entries, boolean keepReplica)
      throws IOException
  {
    for (SwapEntry entry : entries) {
      File aside = new File(appRoot, entry.name + REPLACED_SUFFIX);
      if (aside.exists()) deleteRecursively(aside);
    }
    syncDirectory(appRoot);
    finishJournal(appRoot);
    if (!keepReplica) deleteQuietly(new File(appRoot, RESTORED_PREFERENCES));
  }

  private static List<SwapEntry> managedEntries(File stagingDirectory, File appRoot)
      throws IOException
  {
    File[] staged = stagingDirectory.listFiles();
    if (staged == null) throw new IOException("Unable to list " + stagingDirectory);
    File[] live = appRoot.listFiles();
    if (live == null) throw new IOException("Unable to list " + appRoot);

    Set<String> names = new TreeSet<>();
    for (File child : staged) {
      if (!shouldInclude(child.getName(), child.isDirectory())) {
        throw new IOException("Excluded path present in staging: " + child.getName());
      }
      names.add(child.getName());
    }
    for (File child : live) {
      String name = child.getName();
      if (!name.endsWith(REPLACED_SUFFIX) && shouldInclude(name, child.isDirectory())) {
        names.add(name);
      }
    }
    List<SwapEntry> entries = new ArrayList<>();
    for (String name : names) entries.add(new SwapEntry(name, new File(appRoot, name).exists()));
    return entries;
  }

  private static void writeJournal(File appRoot, List<SwapEntry> entries) throws IOException {
    File journal = new File(appRoot, SWAP_JOURNAL);
    File parent = journal.getParentFile();
    if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
      throw new IOException("Unable to create restore journal directory");
    }
    File temporary = new File(parent, journal.getName() + ".tmp");
    deleteQuietly(temporary);
    try (FileOutputStream file = new FileOutputStream(temporary);
         DataOutputStream data = new DataOutputStream(new BufferedOutputStream(file))) {
      data.writeInt(SWAP_JOURNAL_MAGIC);
      data.writeInt(SWAP_JOURNAL_VERSION);
      data.writeInt(entries.size());
      for (SwapEntry entry : entries) {
        data.writeUTF(entry.name);
        data.writeBoolean(entry.existed);
      }
      data.flush();
      file.getFD().sync();
    }
    if (journal.exists() && !journal.delete()) throw new IOException("Unable to replace restore journal");
    if (!temporary.renameTo(journal)) throw new IOException("Unable to install restore journal");
    syncDirectory(parent);
  }

  private static List<SwapEntry> readJournal(File journal) throws IOException {
    try (DataInputStream data = new DataInputStream(
             new BufferedInputStream(new FileInputStream(journal)))) {
      if (data.readInt() != SWAP_JOURNAL_MAGIC || data.readInt() != SWAP_JOURNAL_VERSION) {
        throw new IOException("Unsupported restore journal");
      }
      int count = data.readInt();
      if (count < 0 || count > MAX_ENTRY_COUNT) throw new IOException("Invalid restore journal");
      List<SwapEntry> entries = new ArrayList<>(count);
      Set<String> names = new HashSet<>();
      for (int index = 0; index < count; index++) {
        String name = data.readUTF();
        validateTopLevelName(name);
        if (!names.add(name)) throw new IOException("Duplicate restore journal entry");
        entries.add(new SwapEntry(name, data.readBoolean()));
      }
      if (data.read() != -1) throw new IOException("Trailing restore journal data");
      return entries;
    }
  }

  private static void writeCommittedMarker(File appRoot) throws IOException {
    File marker = new File(appRoot, SWAP_COMMITTED);
    try (FileOutputStream output = new FileOutputStream(marker)) {
      output.write(1);
      output.getFD().sync();
    }
    syncDirectory(marker.getParentFile());
  }

  private static void finishJournal(File appRoot) throws IOException {
    File marker = new File(appRoot, SWAP_COMMITTED);
    File journal = new File(appRoot, SWAP_JOURNAL);
    if (journal.exists() && !journal.delete()) throw new IOException("Unable to remove restore journal");
    syncDirectory(journal.getParentFile());
    if (marker.exists() && !marker.delete()) throw new IOException("Unable to remove restore marker");
    syncDirectory(journal.getParentFile());
  }

  private static void validateTopLevelName(String name) throws IOException {
    validateEntryName(name);
    if (name.contains("/")) throw new IOException("Invalid restore journal entry");
  }

  private static void syncDirectory(File directory) throws IOException {
    if (directory == null) throw new IOException("Directory is unavailable for sync");
    java.io.FileDescriptor descriptor = null;
    try {
      descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
      Os.fsync(descriptor);
    } catch (ErrnoException error) {
      throw new IOException("Unable to sync " + directory, error);
    } catch (RuntimeException error) {
      if (error.getMessage() == null || !error.getMessage().contains("not mocked")) throw error;
    } finally {
      if (descriptor != null) {
        try {
          Os.close(descriptor);
        } catch (ErrnoException error) {
          throw new IOException("Unable to close synced directory " + directory, error);
        }
      }
    }
  }

  private static final class SwapEntry {
    final String name;
    final boolean existed;

    SwapEntry(String name, boolean existed) {
      this.name = name;
      this.existed = existed;
    }
  }

  public static final class RestoreTransaction {
    private final File appRoot;
    private final List<SwapEntry> entries;
    private boolean finished;

    private RestoreTransaction(File appRoot, List<SwapEntry> entries) {
      this.appRoot = appRoot;
      this.entries = entries;
    }

    public void commit() throws IOException {
      if (finished) throw new IOException("Restore transaction is already finished");
      markCommitted();
      finished = true;
      try {
        cleanCommitted(appRoot, entries, true);
      } catch (IOException | RuntimeException ignored) {
        // The durable marker makes the new state authoritative; startup recovery retries cleanup.
      }
    }

    void markCommitted() throws IOException {
      writeCommittedMarker(appRoot);
    }

    public void rollback() throws IOException {
      if (finished) return;
      rollBack(appRoot, entries);
      finished = true;
    }
  }

  private static void carryForwardPreserved(File appRoot, File stagingDirectory)
      throws IOException
  {
    for (String path : PRESERVED_PATHS) {
      File live = new File(appRoot, path);
      if (!live.isFile()) continue;
      File staged = new File(stagingDirectory, path);
      File parent = staged.getParentFile();
      if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
        throw new IOException("Unable to preserve " + path);
      }
      try (InputStream input = new BufferedInputStream(new FileInputStream(live));
           OutputStream output = new BufferedOutputStream(new FileOutputStream(staged))) {
        copy(input, output);
      }
    }
  }

  // Android caches SharedPreferences per file, so replacing the main preference XML leaves the
  // running process holding pre-restore values. A duplicate under an unopened name lets the
  // framework parse the restored values back into that cache, and it has to be installed by the
  // same atomic swap so no state is carried outside the journal.
  private static void stageRestoredPreferencesReplica(File stagingDirectory) throws IOException {
    File main = new File(stagingDirectory, MAIN_PREFERENCES);
    if (!main.isFile()) return;
    try (InputStream input = new BufferedInputStream(new FileInputStream(main));
         OutputStream output = new BufferedOutputStream(
             new FileOutputStream(new File(stagingDirectory, RESTORED_PREFERENCES)))) {
      copy(input, output);
    }
  }

  private static void deleteQuietly(File file) {
    try {
      deleteRecursively(file);
    } catch (IOException ignored) {}
  }

  public static void deleteStaging(File stagingDirectory) throws IOException {
    if (stagingDirectory != null && stagingDirectory.exists()) deleteRecursively(stagingDirectory);
  }

  public static boolean shouldInclude(String relativePath, boolean directory) {
    if (relativePath == null || relativePath.length() == 0) return true;
    String normalized = relativePath.replace('\\', '/');
    String first = normalized.contains("/") ? normalized.substring(0, normalized.indexOf('/'))
                                             : normalized;
    if ("cache".equals(first) || "code_cache".equals(first) || "lib".equals(first) ||
        "no_backup".equals(first)) {
      return false;
    }

    // Device-bound wrappers cannot be restored elsewhere. Portable master-secret material is
    // carried by master-secret.bin instead.
    return !normalized.equals(DEVICE_PREFERENCES) && !normalized.equals(RESTORED_PREFERENCES);
  }

  private static void addDirectory(ZipOutputStream zip, File root, File directory,
                                   String relativeDirectory)
      throws IOException
  {
    File[] children = directory.listFiles();
    if (children == null) throw new IOException("Unable to list " + directory);
    Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));

    for (File child : children) {
      String relativePath = relativeDirectory.length() == 0 ? child.getName()
                                                             : relativeDirectory + "/" + child.getName();
      if (!shouldInclude(relativePath, child.isDirectory())) continue;
      if (child.isDirectory()) {
        addDirectory(zip, root, child, relativePath);
      } else if (child.isFile()) {
        ZipEntry entry = new ZipEntry(FILES_PREFIX + relativePath);
        entry.setTime(0);
        zip.putNextEntry(entry);
        if (MAIN_PREFERENCES.equals(relativePath)) {
          byte[] preferences = readEntry(new BufferedInputStream(new FileInputStream(child)),
                                         MAX_ENTRY_BYTES);
          byte[] sanitized = ROOT_SECRET_PREFERENCES.matcher(
              new String(preferences, StandardCharsets.UTF_8)).replaceAll("\n")
              .getBytes(StandardCharsets.UTF_8);
          Arrays.fill(preferences, (byte) 0);
          zip.write(sanitized);
          Arrays.fill(sanitized, (byte) 0);
        } else {
          try (InputStream file = new BufferedInputStream(new FileInputStream(child))) {
            copy(file, zip);
          }
        }
        zip.closeEntry();
      }
    }
  }

  private static long extractEntry(ZipInputStream zip, ZipEntry entry, File stagingDirectory,
                                   String relativePath, long limit)
      throws IOException
  {
    if (relativePath.length() == 0 || !shouldInclude(relativePath, entry.isDirectory())) {
      throw new IOException("Invalid backup file entry: " + entry.getName());
    }
    File destination = new File(stagingDirectory, relativePath);
    String stagingPath = stagingDirectory.getCanonicalPath() + File.separator;
    if (!destination.getCanonicalPath().startsWith(stagingPath)) {
      throw new IOException("Backup entry escapes staging directory");
    }

    if (entry.isDirectory()) {
      if (!destination.mkdirs() && !destination.isDirectory()) {
        throw new IOException("Unable to create backup directory");
      }
      return 0;
    }

    File parent = destination.getParentFile();
    if (parent == null || (!parent.mkdirs() && !parent.isDirectory())) {
      throw new IOException("Unable to create backup parent directory");
    }
    try (OutputStream file = new BufferedOutputStream(new FileOutputStream(destination))) {
      return copy(zip, file, limit);
    }
  }

  private static byte[] manifest(long creationTimeMillis) {
    return ("formatVersion=" + FORMAT_VERSION + "\n" +
            "createdAtMillis=" + creationTimeMillis + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static boolean validateManifest(byte[] serialized) throws IOException {
    String[] lines = new String(serialized, StandardCharsets.UTF_8).split("\\n");
    Integer version = null;
    Long createdAt = null;
    for (String line : lines) {
      int separator = line.indexOf('=');
      if (separator <= 0) continue;
      String name = line.substring(0, separator);
      String value = line.substring(separator + 1);
      try {
        if ("formatVersion".equals(name)) version = Integer.valueOf(value);
        if ("createdAtMillis".equals(name)) createdAt = Long.valueOf(value);
      } catch (NumberFormatException error) {
        throw new IOException("Invalid backup manifest", error);
      }
    }
    if (version == null || version != FORMAT_VERSION || createdAt == null || createdAt < 0) {
      throw new IOException("Unsupported backup manifest");
    }
    return true;
  }

  private static byte[] wrapMasterSecret(byte[] plaintext, byte[] recoveryKey) throws IOException {
    byte[] nonce = new byte[NONCE_LENGTH];
    new SecureRandom().nextBytes(nonce);
    byte[] header = masterSecretHeader(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(recoveryKey, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header);
      byte[] ciphertext = cipher.doFinal(plaintext);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.write(header);
      bytes.write(ciphertext);
      return bytes.toByteArray();
    } catch (GeneralSecurityException error) {
      throw new IOException("Unable to wrap master secret", error);
    } finally {
      Arrays.fill(nonce, (byte) 0);
    }
  }

  private static byte[] unwrapMasterSecret(byte[] serialized, byte[] recoveryKey) throws IOException {
    int headerLength = MASTER_SECRET_MAGIC.length + 1 + 1 + NONCE_LENGTH;
    if (serialized.length != headerLength + MASTER_SECRET_LENGTH + TAG_LENGTH_BITS / Byte.SIZE) {
      throw new IOException("Invalid portable master-secret wrapper");
    }
    DataInputStream data = new DataInputStream(new ByteArrayInputStream(serialized));
    byte[] magic = new byte[MASTER_SECRET_MAGIC.length];
    data.readFully(magic);
    int version = data.readUnsignedByte();
    int nonceLength = data.readUnsignedByte();
    if (!Arrays.equals(magic, MASTER_SECRET_MAGIC) || version != FORMAT_VERSION ||
        nonceLength != NONCE_LENGTH) {
      throw new IOException("Unsupported portable master-secret wrapper");
    }
    byte[] nonce = new byte[nonceLength];
    data.readFully(nonce);
    byte[] header = masterSecretHeader(nonce);
    byte[] ciphertext = new byte[data.available()];
    data.readFully(ciphertext);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(recoveryKey, "AES"),
                  new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(header);
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException error) {
      throw new IOException("Unable to unwrap master secret", error);
    } finally {
      Arrays.fill(nonce, (byte) 0);
      Arrays.fill(ciphertext, (byte) 0);
    }
  }

  private static byte[] masterSecretHeader(byte[] nonce) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(bytes);
    data.write(MASTER_SECRET_MAGIC);
    data.writeByte(FORMAT_VERSION);
    data.writeByte(nonce.length);
    data.write(nonce);
    data.flush();
    return bytes.toByteArray();
  }

  private static byte[] combine(MasterSecret masterSecret) throws IOException {
    byte[] encryptionKey = masterSecret.getEncryptionKey().getEncoded();
    byte[] macKey = masterSecret.getMacKey().getEncoded();
    if (encryptionKey.length != 16 || macKey.length != 20) {
      throw new IOException("Invalid master-secret key lengths");
    }
    byte[] combined = new byte[MASTER_SECRET_LENGTH];
    System.arraycopy(encryptionKey, 0, combined, 0, encryptionKey.length);
    System.arraycopy(macKey, 0, combined, encryptionKey.length, macKey.length);
    Arrays.fill(encryptionKey, (byte) 0);
    Arrays.fill(macKey, (byte) 0);
    return combined;
  }

  private static MasterSecret masterSecret(byte[] combined) throws IOException {
    if (combined.length != MASTER_SECRET_LENGTH) throw new IOException("Invalid master secret");
    byte[] encryptionKey = Arrays.copyOfRange(combined, 0, 16);
    byte[] macKey = Arrays.copyOfRange(combined, 16, MASTER_SECRET_LENGTH);
    Arrays.fill(combined, (byte) 0);
    try {
      return new MasterSecret(new SecretKeySpec(encryptionKey, "AES"),
                              new SecretKeySpec(macKey, "HmacSHA1"));
    } finally {
      Arrays.fill(encryptionKey, (byte) 0);
      Arrays.fill(macKey, (byte) 0);
    }
  }

  private static void writeEntry(ZipOutputStream zip, String name, byte[] value) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setTime(0);
    zip.putNextEntry(entry);
    zip.write(value);
    zip.closeEntry();
    Arrays.fill(value, (byte) 0);
  }

  private static byte[] readEntry(InputStream input, long limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    copy(input, output, limit);
    return output.toByteArray();
  }

  private static void validateEntryName(String name) throws IOException {
    if (name == null || name.length() == 0 || name.startsWith("/") || name.startsWith("\\") ||
        name.contains("\\") || name.matches("^[A-Za-z]:.*")) {
      throw new IOException("Unsafe backup entry name");
    }
    for (String component : name.split("/")) {
      if ("..".equals(component) || ".".equals(component) || component.length() == 0) {
        throw new IOException("Unsafe backup entry name");
      }
    }
  }

  private static void prepareEmptyDirectory(File directory) throws IOException {
    if (directory.exists()) deleteRecursively(directory);
    if (!directory.mkdirs() && !directory.isDirectory()) {
      throw new IOException("Unable to create staging directory");
    }
  }

  private static void deleteRecursively(File file) throws IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null) throw new IOException("Unable to list " + file);
      for (File child : children) deleteRecursively(child);
    }
    if (file.exists() && !file.delete()) throw new IOException("Unable to delete " + file);
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    int read;
    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
  }

  private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > limit) throw new IOException("Backup entry exceeds the supported size");
      output.write(buffer, 0, read);
    }
    return total;
  }

  public static final class RestoreResult {
    private final File stagingDirectory;
    private final MasterSecret masterSecret;
    private final byte[] argon2Wrapper;

    private RestoreResult(File stagingDirectory, MasterSecret masterSecret, byte[] argon2Wrapper) {
      this.stagingDirectory = stagingDirectory;
      this.masterSecret = masterSecret;
      this.argon2Wrapper = argon2Wrapper;
    }

    public File getStagingDirectory() {
      return stagingDirectory;
    }

    public MasterSecret getMasterSecret() {
      return masterSecret;
    }

    public byte[] getArgon2Wrapper() {
      return Arrays.copyOf(argon2Wrapper, argon2Wrapper.length);
    }
  }
}