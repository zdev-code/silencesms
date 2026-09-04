package org.smssecure.smssecure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.smssecure.smssecure.backup.SecureBackupArchive;
import org.smssecure.smssecure.crypto.MasterSecretUtil;

import java.io.File;

public class ApplicationContextTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void startupReconcilesDeviceProtectionWhenRestoreJournalIsAbsent() throws Exception {
    File appRoot = temporaryFolder.newFolder("app");
    Context context = mock(Context.class);

    try (MockedStatic<SecureBackupArchive> archive = mockStatic(SecureBackupArchive.class);
         MockedStatic<MasterSecretUtil> masterSecrets = mockStatic(MasterSecretUtil.class)) {
      archive.when(() -> SecureBackupArchive.recoverInterruptedRestore(appRoot)).thenReturn(false);

      ApplicationContext.recoverInterruptedRestore(context, appRoot);

      masterSecrets.verify(() -> MasterSecretUtil.reconcileDeviceProtection(context));
    }
  }
}