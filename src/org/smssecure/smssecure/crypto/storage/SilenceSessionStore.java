package org.smssecure.smssecure.crypto.storage;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.smssecure.smssecure.crypto.MasterCipher;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.util.Conversions;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SessionStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * New-API ({@code org.signal.libsignal.protocol}) session store for the hybrid crypto setup
 * (maintained libsignal for message encrypt/decrypt, vendored library for Key Exchange). Backs the
 * SAME on-disk files as the
 * vendored {@link VendoredSessionStore} (directory {@code sessions-v2}, MasterCipher-encrypted, version
 * marker {@code 2} = serialized record), so the maintained {@code SessionCipher} and the vendored
 * Key-Exchange {@code SessionBuilder} read/write the same sessions. Record byte-compatibility across
 * the two libraries is proven by the migration test ({@code OldToNewSessionRecordTest}).
 */
public class SilenceSessionStore implements SessionStore {

  private static final String TAG                   = SilenceSessionStore.class.getSimpleName();
  private static final String SESSIONS_DIRECTORY_V2 = "sessions-v2";
  private static final Object FILE_LOCK             = new Object();

  private static final int SINGLE_STATE_VERSION   = 1;
  private static final int ARCHIVE_STATES_VERSION = 2;
  private static final int CURRENT_VERSION        = 2;

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public SilenceSessionStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context        = context.getApplicationContext();
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public SessionRecord loadSession(SignalProtocolAddress address) {
    synchronized (FILE_LOCK) {
      File sessionFile = getSessionFile(address);
      if (!sessionFile.exists()) {
        return new SessionRecord();   // no session yet — normal
      }

      try {
        MasterCipher    cipher     = new MasterCipher(masterSecret);
        FileInputStream in         = new FileInputStream(sessionFile);
        int             marker     = readInteger(in);
        byte[]          serialized = cipher.decryptBytes(readBlob(in));
        in.close();

        if (marker == ARCHIVE_STATES_VERSION) {
          return new SessionRecord(serialized);
        }

        // A very old raw SessionStructure (marker 1) that the maintained library cannot parse. Fall
        // through to a fresh session so the next message triggers a re-handshake. No stored message
        // is lost.
        Log.w(TAG, "Legacy single-state session (marker " + marker + ") unreadable by the "
                   + "maintained library; re-handshake will occur.");
        return new SessionRecord();
      } catch (InvalidMessageException e) {
        // A stored record that will not deserialize under the maintained library; re-handshake.
        Log.w(TAG, "Stored session failed to deserialize; re-handshake will occur.", e);
        return new SessionRecord();
      } catch (IOException e) {
        Log.w(TAG, "Session read error; treating as no session.", e);
        return new SessionRecord();
      }
    }
  }

  @Override
  public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
    List<SessionRecord> result = new ArrayList<>(addresses.size());
    for (SignalProtocolAddress address : addresses) {
      if (!containsSession(address)) throw new NoSessionException("No session for: " + address);
      result.add(loadSession(address));
    }
    return result;
  }

  @Override
  public void storeSession(SignalProtocolAddress address, SessionRecord record) {
    synchronized (FILE_LOCK) {
      try {
        MasterCipher     masterCipher = new MasterCipher(masterSecret);
        RandomAccessFile sessionFile  = new RandomAccessFile(getSessionFile(address), "rw");
        FileChannel      out          = sessionFile.getChannel();

        out.position(0);
        writeInteger(CURRENT_VERSION, out);
        writeBlob(masterCipher.encryptBytes(record.serialize()), out);
        out.truncate(out.position());

        sessionFile.close();
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    return getSessionFile(address).exists() && loadSession(address).hasSenderChain();
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    getSessionFile(address).delete();
  }

  @Override
  public void deleteAllSessions(String name) {
    List<Integer> devices = getSubDeviceSessions(name);

    deleteSession(new SignalProtocolAddress(name, 1));

    for (int device : devices) {
      deleteSession(new SignalProtocolAddress(name, device));
    }
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
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

  private File getSessionFile(SignalProtocolAddress address) {
    return new File(getSessionDirectory(), getSessionName(address));
  }

  private File getSessionDirectory() {
    return VendoredSessionStore.getSessionDirectory(context);
  }

  private String getSessionName(SignalProtocolAddress axolotlAddress) {
    Recipient recipient   = RecipientFactory.getRecipientsFromString(context, axolotlAddress.getName(), true).getPrimaryRecipient();
    long      recipientId = recipient.getRecipientId();

    return recipientId + ((Build.VERSION.SDK_INT < 22 || subscriptionId == -1) ? "" : "." + subscriptionId);
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
