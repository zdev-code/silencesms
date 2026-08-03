package org.smssecure.smssecure.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.smssecure.smssecure.crypto.MasterSecretUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RestoredPreferencesTest {
  private Context           context;
  private SharedPreferences replica;
  private SharedPreferences main;
  private SharedPreferences.Editor mainEditor;

  @Before
  public void setUp() {
    context    = mock(Context.class);
    replica    = mock(SharedPreferences.class);
    main       = mock(SharedPreferences.class);
    mainEditor = editorReturningSelf();

    when(context.getSharedPreferences(SecureBackupArchive.RESTORED_PREFERENCES_NAME, 0))
        .thenReturn(replica);
    when(context.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, 0)).thenReturn(main);
    when(main.edit()).thenReturn(mainEditor);
    // Assign first: calling the helper inside when(...) trips UnfinishedStubbingException.
    SharedPreferences.Editor replicaEditor = editorReturningSelf();
    when(replica.edit()).thenReturn(replicaEditor);
  }

  @Test
  public void restoredValuesReplaceTheCachedMapWithTheirOriginalTypes() throws Exception {
    Map<String, Object> restored = new HashMap<>();
    restored.put("text", "value");
    restored.put("flag", Boolean.TRUE);
    restored.put("count", 7);
    restored.put("stamp", 8L);
    restored.put("ratio", 1.5f);
    restored.put("names", Collections.singleton("one"));
    doReturn(restored).when(replica).getAll();

    RestoredPreferences.resyncMainPreferences(context);

    // Clearing first is what evicts pre-restore keys the archive no longer carries.
    inOrder(mainEditor).verify(mainEditor).clear();
    verify(mainEditor).putString("text", "value");
    verify(mainEditor).putBoolean("flag", true);
    verify(mainEditor).putInt("count", 7);
    verify(mainEditor).putLong("stamp", 8L);
    verify(mainEditor).putFloat("ratio", 1.5f);
    verify(mainEditor).putStringSet("names", Collections.singleton("one"));
    verify(mainEditor).commit();
  }

  @Test
  public void absentReplicaLeavesTheCachedMapAlone() throws Exception {
    doReturn(Collections.emptyMap()).when(replica).getAll();

    RestoredPreferences.resyncMainPreferences(context);

    verify(main, never()).edit();
  }

  @Test
  public void failedResyncIsReportedRatherThanLeavingAStaleCache() {
    doReturn(Collections.singletonMap("text", "value")).when(replica).getAll();
    when(mainEditor.commit()).thenReturn(false);

    try {
      RestoredPreferences.resyncMainPreferences(context);
      fail("Expected the failed commit to be reported");
    } catch (IOException expected) {
      assertEquals("Unable to apply restored preferences", expected.getMessage());
    }
  }

  private SharedPreferences.Editor editorReturningSelf() {
    SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
    when(editor.clear()).thenReturn(editor);
    when(editor.putString(anyString(), any())).thenReturn(editor);
    when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);
    when(editor.putInt(anyString(), anyInt())).thenReturn(editor);
    when(editor.putLong(anyString(), anyLong())).thenReturn(editor);
    when(editor.putFloat(anyString(), anyFloat())).thenReturn(editor);
    when(editor.putStringSet(anyString(), any())).thenReturn(editor);
    when(editor.commit()).thenReturn(true);
    return editor;
  }
}
