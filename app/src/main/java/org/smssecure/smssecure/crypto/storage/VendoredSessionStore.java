package org.smssecure.smssecure.crypto.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.Conversions;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SessionStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static org.whispersystems.libsignal.state.StorageProtos.SessionStructure;

public class VendoredSessionStore implements SessionStore {

  public enum DeleteOutcome { DELETED, ALREADY_ABSENT, CHANGED }

  private static final String TAG                   = VendoredSessionStore.class.getSimpleName();
  private static final String SESSIONS_DIRECTORY_V2 = "sessions-v2";
  private static final String GENERATIONS = "session-generations";

  private static final int SINGLE_STATE_VERSION   = 1;
  private static final int ARCHIVE_STATES_VERSION = 2;
  private static final int CURRENT_VERSION        = 2;

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public VendoredSessionStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    Log.w(TAG, "VendoredSessionStore for subscription ID " + subscriptionId);
    if (subscriptionId == -1) Log.w(TAG, "Subscription ID should not be -1!");

    this.context        = context.getApplicationContext();
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  /**
   * Shared with {@link SilenceSessionStore} (and, for the isolated KEX directory, private to
   * {@link KeyExchangeSessionStore}) so every store touching a given directory serializes over the
   * same monitor. Keyed by the instance directory, which honours {@link #getSessionsDirectoryName()}.
   */
  private Object sessionLock() {
    return StorageFileLock.forDirectory(getSessionDirectory());
  }

  @Override
  public SessionRecord loadSession(SignalProtocolAddress address) {
    synchronized (sessionLock()) {
      try {
        MasterCipher    cipher = new MasterCipher(masterSecret);
        FileInputStream in     = new FileInputStream(getSessionFile(address));

        int versionMarker  = readInteger(in);

        if (versionMarker > CURRENT_VERSION) {
          throw new AssertionError("Unknown version: " + versionMarker);
        }

        byte[] serialized = cipher.decryptBytes(readBlob(in));
        in.close();

        if (versionMarker == SINGLE_STATE_VERSION) {
          SessionStructure sessionStructure = SessionStructure.parseFrom(serialized);
          SessionState     sessionState     = new SessionState(sessionStructure);
          return new SessionRecord(sessionState);
        } else if (versionMarker == ARCHIVE_STATES_VERSION) {
          return new SessionRecord(serialized);
        } else {
          throw new AssertionError("Unknown version: " + versionMarker);
        }
      } catch (InvalidMessageException | IOException e) {
        Log.w(TAG, "No existing session information found.");
        return new SessionRecord();
      }
    }
  }

  @Override
  public void storeSession(SignalProtocolAddress address, SessionRecord record) {
    synchronized (sessionLock()) {
      File target = getSessionFile(address);
      File temp   = null;
      try {
        MasterCipher masterCipher = new MasterCipher(masterSecret);
        invalidateSessionGeneration(context, target);
        temp = File.createTempFile("session", ".tmp", target.getParentFile());

        try (RandomAccessFile sessionFile = new RandomAccessFile(temp, "rw")) {
          FileChannel out = sessionFile.getChannel();
          out.position(0);
          writeInteger(CURRENT_VERSION, out);
          writeBlob(masterCipher.encryptBytes(record.serialize()), out);
          out.truncate(out.position());
          out.force(true);
        }

        // Atomic replace: readers on the shared lock never observe a half-written record.
        if (!temp.renameTo(target)) {
          throw new IOException("Atomic rename failed: " + temp + " -> " + target);
        }
        temp = null;
      } catch (IOException e) {
        throw new AssertionError(e);
      } finally {
        if (temp != null && temp.exists() && !temp.delete()) {
          Log.w(TAG, "Could not remove temporary session file " + temp);
        }
      }
    }
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    return getSessionFile(address).exists() &&
           loadSession(address).getSessionState().hasSenderChain();
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    synchronized (sessionLock()) {
      invalidateSessionGeneration(context, getSessionFile(address));
      getSessionFile(address).delete();
    }
  }

  @Override
  public void deleteAllSessions(String name) {
    synchronized (sessionLock()) {
      List<Integer> devices = getSubDeviceSessions(name);

      deleteSession(new SignalProtocolAddress(name, 1));

      for (int device : devices) {
        deleteSession(new SignalProtocolAddress(name, device));
      }
    }
  }

  public String snapshotSession(String name) {
    synchronized (sessionLock()) {
      SignalProtocolAddress address = new SignalProtocolAddress(name, 1);
      File file = getSessionFile(address);
      String digest = fingerprint(file);
      if ("absent".equals(digest)) return getSessionName(address) + ":absent";
      SharedPreferences generations = generations(context);
      String key = generationKey(file);
      String generation = generations.getString(key, null);
      if (generation == null || generation.startsWith("deleting:")) {
        generation = UUID.randomUUID().toString();
        if (!generations.edit().putString(key, generation).commit()) {
          throw new IllegalStateException("Unable to persist session generation");
        }
      }
      return getSessionName(address) + ":" + generation + ":" + digest;
    }
  }

  public DeleteOutcome deleteSessionIfUnchanged(String name, String snapshot) {
    synchronized (sessionLock()) {
      SignalProtocolAddress address = new SignalProtocolAddress(name, 1);
      File file = getSessionFile(address);
      String prefix = getSessionName(address) + ":";
      if (snapshot == null || !snapshot.startsWith(prefix)) return DeleteOutcome.CHANGED;
      if (snapshot.equals(prefix + "absent")) {
        return "absent".equals(fingerprint(file)) ? DeleteOutcome.ALREADY_ABSENT
        : DeleteOutcome.CHANGED;
      }
      String[] parts = snapshot.substring(prefix.length()).split(":", 2);
      if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return DeleteOutcome.CHANGED;
      SharedPreferences generations = generations(context);
      String key = generationKey(file);
      String generation = generations.getString(key, null);
      if (!parts[0].equals(generation) && !("deleting:" + parts[0]).equals(generation)) {
        return DeleteOutcome.CHANGED;
      }
      String current = fingerprint(file);
      if ("absent".equals(current)) {
        return ("deleting:" + parts[0]).equals(generation) ? DeleteOutcome.ALREADY_ABSENT
            : DeleteOutcome.CHANGED;
      }
      if (!parts[1].equals(current)) return DeleteOutcome.CHANGED;
      if (!("deleting:" + parts[0]).equals(generation) &&
          !generations.edit().putString(key, "deleting:" + parts[0]).commit()) {
        throw new IllegalStateException("Unable to persist session deletion");
      }
      if (!file.delete()) {
        throw new IllegalStateException("Unable to delete selected session");
      }
      return DeleteOutcome.DELETED;
    }
  }

  static void invalidateSessionGeneration(Context context, File file) {
    if (!generations(context).edit().putString(generationKey(file), UUID.randomUUID().toString()).commit()) {
      throw new IllegalStateException("Unable to invalidate session generation");
    }
  }

  private static SharedPreferences generations(Context context) {
    return context.getSharedPreferences(GENERATIONS, Context.MODE_PRIVATE);
  }

  private static String generationKey(File file) {
    return file.getParentFile().getName() + "/" + file.getName();
  }

  private static String fingerprint(File file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (FileInputStream input = new FileInputStream(file)) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      } catch (java.io.FileNotFoundException error) {
        if (!file.exists()) return "absent";
        throw error;
      }
      return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new IllegalStateException("Unable to snapshot selected session", error);
    }
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    synchronized (sessionLock()) {
      long          recipientId = RecipientFactory.getRecipientsFromString(context, name, true).getPrimaryRecipient().getRecipientId();
      List<Integer> results     = new LinkedList<>();
      File          parent      = getSessionDirectory();
      String[]      children    = parent.list();

      if (children == null) return results;

      for (String child : children) {
        try {
          String[] parts              = child.split("[.]", 2);
          long     sessionRecipientId = Long.parseLong(parts[0]);

          if (sessionRecipientId == recipientId && parts.length > 1) {
            results.add(Integer.parseInt(parts[1]));
          }
        } catch (NumberFormatException e) {
          Log.w(TAG, e);
        }
      }

      return results;
    }
  }

  private File getSessionFile(SignalProtocolAddress address) {
    String sessionName = getSessionName(address);
    return new File(getSessionDirectory(), sessionName);
  }

  /**
   * The on-disk directory name used by this store. Subclasses may override this to isolate
   * their session records from the shared {@code sessions-v2} directory (for example, the
   * transitional Key-Exchange handshake state that must not be seen by the libsignal cipher).
   */
  protected String getSessionsDirectoryName() {
    return SESSIONS_DIRECTORY_V2;
  }

  private File getSessionDirectory() {
    File directory = new File(context.getFilesDir(), getSessionsDirectoryName());

    if (!directory.exists()) {
      if (!directory.mkdirs()) {
        Log.w(TAG, "Session directory creation failed!");
      }
    }

    return directory;
  }

  public static File getSessionDirectory(Context context) {
    File directory = new File(context.getFilesDir(), SESSIONS_DIRECTORY_V2);

    if (!directory.exists()) {
      if (!directory.mkdirs()) {
        Log.w(TAG, "Session directory creation failed!");
      }
    }

    return directory;
  }

  private String getSessionName(SignalProtocolAddress axolotlAddress) {
    Recipient recipient   = RecipientFactory.getRecipientsFromString(context, axolotlAddress.getName(), true).getPrimaryRecipient();
    long      recipientId = recipient.getRecipientId();

    return recipientId + (subscriptionId == -1 ? "" : "." + subscriptionId);
  }

  private byte[] readBlob(FileInputStream in) throws IOException {
    int length       = readInteger(in);
    byte[] blobBytes = new byte[length];

    in.read(blobBytes, 0, blobBytes.length);
    return blobBytes;
  }

  private void writeBlob(byte[] blobBytes, FileChannel out) throws IOException {
    writeInteger(blobBytes.length, out);
    out.write(ByteBuffer.wrap(blobBytes));
  }

  private int readInteger(FileInputStream in) throws IOException {
    byte[] integer = new byte[4];
    in.read(integer, 0, integer.length);
    return Conversions.byteArrayToInt(integer);
  }

  private void writeInteger(int value, FileChannel out) throws IOException {
    byte[] valueBytes = Conversions.intToByteArray(value);
    out.write(ByteBuffer.wrap(valueBytes));
  }

}
