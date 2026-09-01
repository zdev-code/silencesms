package org.whispersystems.libsignal.migration;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.state.IdentityKeyStore.IdentityChange;

/**
 * Locks the trust-on-first-use {@link IdentityChange} contract that the production
 * {@code SilenceIdentityKeyStore#saveIdentity} must honour under libsignal 0.72.1:
 *
 * <ul>
 *   <li>first use of an address &rarr; {@code NEW_OR_UNCHANGED}</li>
 *   <li>re-saving the same identity &rarr; {@code NEW_OR_UNCHANGED}</li>
 *   <li>overwriting with a different identity &rarr; {@code REPLACED_EXISTING}</li>
 * </ul>
 *
 * <p>libsignal requires {@code REPLACED_EXISTING} when a previously stored identity is replaced;
 * returning {@code NEW_OR_UNCHANGED} in that case suppresses the library's identity-change handling
 * and can leave session state inconsistent after a peer changes keys. The production store computes
 * this from {@code IdentityDatabase} (compare the stored key, then persist); this test exercises the
 * identical decision on the in-memory design stand-in ({@link SilenceStyleSignalProtocolStore}) with
 * real 0.72.1 keys so it runs on the host without a device.
 */
public class SilenceIdentityChangeTest {

  private static final SignalProtocolAddress ADDRESS = new SignalProtocolAddress("+15551234567", 1);

  private static IdentityKey generateIdentityKey() {
    return new IdentityKey(Curve.generateKeyPair().getPublicKey());
  }

  private static SilenceStyleSignalProtocolStore newStore() {
    ECKeyPair       pair     = Curve.generateKeyPair();
    IdentityKeyPair identity = new IdentityKeyPair(new IdentityKey(pair.getPublicKey()), pair.getPrivateKey());
    return new SilenceStyleSignalProtocolStore(identity, 1);
  }

  @Test
  public void firstUse_reportsNewOrUnchanged() {
    SilenceStyleSignalProtocolStore store = newStore();

    assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(ADDRESS, generateIdentityKey()));
  }

  @Test
  public void unchangedIdentity_reportsNewOrUnchanged() {
    SilenceStyleSignalProtocolStore store = newStore();
    IdentityKey                     key   = generateIdentityKey();

    store.saveIdentity(ADDRESS, key);

    assertEquals(IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(ADDRESS, key));
  }

  @Test
  public void replacedIdentity_reportsReplacedExisting() {
    SilenceStyleSignalProtocolStore store = newStore();

    store.saveIdentity(ADDRESS, generateIdentityKey());

    assertEquals(IdentityChange.REPLACED_EXISTING, store.saveIdentity(ADDRESS, generateIdentityKey()));
  }
}
