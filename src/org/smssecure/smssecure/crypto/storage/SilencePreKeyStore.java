package org.smssecure.smssecure.crypto.storage;

import android.content.Context;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.util.Conversions;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;

/**
 * New-API ({@code org.signal.libsignal.protocol}) prekey / signed-prekey store for the hybrid
 * crypto setup (maintained libsignal for message encrypt/decrypt, vendored library for Key Exchange).
 * Backs the SAME on-disk files as
 * the vendored {@link VendoredPreKeyStore} ({@code prekeys} / {@code signed_prekeys} directories,
 * MasterCipher-encrypted, version marker {@code 1} = serialized record). Record byte-compatibility
 * across the two libraries is proven by the migration test ({@code OldToNewKeyRecordsTest}).
 */
public class SilencePreKeyStore implements PreKeyStore, SignedPreKeyStore {

  public  static final String PREKEY_DIRECTORY        = "prekeys";
  public  static final String SIGNED_PREKEY_DIRECTORY = "signed_prekeys";

  private static final int    CURRENT_VERSION_MARKER = 1;
  private static final String TAG                    = SilencePreKeyStore.class.getSimpleName();

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public SilencePreKeyStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context        = context;
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  // Shared with VendoredPreKeyStore so both libraries serialize over the same on-disk records.
  private Object preKeyLock() {
    return StorageFileLock.forDirectory(getPreKeyDirectory());
  }

  private Object signedPreKeyLock() {
    return StorageFileLock.forDirectory(getSignedPreKeyDirectory());
  }

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    synchronized (preKeyLock()) {
      try {
        return new PreKeyRecord(loadSerializedRecord(getPreKeyFile(preKeyId)));
      } catch (IOException | InvalidMessageException e) {
        Log.w(TAG, e);
        throw new InvalidKeyIdException(e);
      }
    }
  }

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    synchronized (signedPreKeyLock()) {
      try {
        return new SignedPreKeyRecord(loadSerializedRecord(getSignedPreKeyFile(signedPreKeyId)));
      } catch (IOException | InvalidMessageException e) {
        Log.w(TAG, e);
        throw new InvalidKeyIdException(e);
      }
    }
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    synchronized (signedPreKeyLock()) {
      File                     directory = getSignedPreKeyDirectory();
      List<SignedPreKeyRecord> results   = new LinkedList<>();

      for (File signedPreKeyFile : directory.listFiles()) {
        try {
          results.add(new SignedPreKeyRecord(loadSerializedRecord(signedPreKeyFile)));
        } catch (IOException | InvalidMessageException e) {
          Log.w(TAG, e);
        }
      }

      return results;
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    synchronized (preKeyLock()) {
      try {
        storeSerializedRecord(getPreKeyFile(preKeyId), record.serialize());
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    synchronized (signedPreKeyLock()) {
      try {
        storeSerializedRecord(getSignedPreKeyFile(signedPreKeyId), record.serialize());
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    synchronized (preKeyLock()) {
      return getPreKeyFile(preKeyId).exists();
    }
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    synchronized (signedPreKeyLock()) {
      return getSignedPreKeyFile(signedPreKeyId).exists();
    }
  }

  @Override
  public void removePreKey(int preKeyId) {
    synchronized (preKeyLock()) {
      getPreKeyFile(preKeyId).delete();
    }
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    synchronized (signedPreKeyLock()) {
      getSignedPreKeyFile(signedPreKeyId).delete();
    }
  }

  private byte[] loadSerializedRecord(File recordFile)
      throws IOException, InvalidMessageException
  {
    MasterCipher    masterCipher  = new MasterCipher(masterSecret);
    FileInputStream fin           = new FileInputStream(recordFile);
    int             recordVersion = readInteger(fin);

    if (recordVersion != CURRENT_VERSION_MARKER) {
      throw new AssertionError("Invalid version: " + recordVersion);
    }

    return masterCipher.decryptBytes(readBlob(fin));
  }

  private void storeSerializedRecord(File file, byte[] serialized) throws IOException {
    MasterCipher masterCipher = new MasterCipher(masterSecret);
    File         temp         = File.createTempFile("record", ".tmp", file.getParentFile());

    try {
      try (RandomAccessFile recordFile = new RandomAccessFile(temp, "rw")) {
        FileChannel out = recordFile.getChannel();
        out.position(0);
        writeInteger(CURRENT_VERSION_MARKER, out);
        writeBlob(masterCipher.encryptBytes(serialized), out);
        out.truncate(out.position());
        out.force(true);
      }

      // Atomic replace: readers on the shared lock never observe a half-written record.
      if (!temp.renameTo(file)) {
        throw new IOException("Atomic rename failed: " + temp + " -> " + file);
      }
      temp = null;
    } finally {
      if (temp != null && temp.exists() && !temp.delete()) {
        Log.w(TAG, "Could not remove temporary prekey file " + temp);
      }
    }
  }

  private File getPreKeyFile(int preKeyId) {
    String subscriptionFile = subscriptionId != -1 ? subscriptionId + "" : "";
    return new File(getPreKeyDirectory(), String.valueOf(preKeyId) + subscriptionFile);
  }

  private File getSignedPreKeyFile(int signedPreKeyId) {
    String subscriptionFile = subscriptionId != -1 ? subscriptionId + "" : "";
    return new File(getSignedPreKeyDirectory(), String.valueOf(signedPreKeyId) + subscriptionFile);
  }

  private File getPreKeyDirectory() {
    return getRecordsDirectory(PREKEY_DIRECTORY);
  }

  private File getSignedPreKeyDirectory() {
    return getRecordsDirectory(SIGNED_PREKEY_DIRECTORY);
  }

  private File getRecordsDirectory(String directoryName) {
    File directory = new File(context.getFilesDir(), directoryName);

    if (!directory.exists()) {
      if (!directory.mkdirs()) {
        Log.w(TAG, "PreKey directory creation failed!");
      }
    }

    return directory;
  }

  private byte[] readBlob(FileInputStream in) throws IOException {
    int    length    = readInteger(in);
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
