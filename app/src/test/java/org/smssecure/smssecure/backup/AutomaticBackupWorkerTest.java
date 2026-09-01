package org.smssecure.smssecure.backup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.documentfile.provider.DocumentFile;

import org.junit.Test;

public class AutomaticBackupWorkerTest {
  @Test
  public void retryCleanupDeletesOnlyInterruptedAutomaticBackups() {
    DocumentFile interrupted = file("Silence-20260804-120000.silencebackup.partial");
    DocumentFile complete = file("Silence-20260803-120000.silencebackup");
    DocumentFile unrelated = file("Other.silencebackup.partial");
    DocumentFile directory = mock(DocumentFile.class);
    when(directory.listFiles()).thenReturn(new DocumentFile[] {interrupted, complete, unrelated});

    AutomaticBackupWorker.cleanInterruptedBackups(directory);

    verify(interrupted).delete();
    verify(complete, never()).delete();
    verify(unrelated, never()).delete();
  }

  private static DocumentFile file(String name) {
    DocumentFile file = mock(DocumentFile.class);
    when(file.isFile()).thenReturn(true);
    when(file.getName()).thenReturn(name);
    return file;
  }
}
