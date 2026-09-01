package org.smssecure.smssecure;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.smssecure.smssecure.crypto.MasterSecret;

import javax.crypto.spec.SecretKeySpec;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class BaseUnitTest {
  protected MasterSecret masterSecret;

  protected Context           context           = mock(Context.class);
  protected SharedPreferences sharedPreferences = mock(SharedPreferences.class);

  private MockedStatic<Looper> looperMock;
  private MockedStatic<Log> logMock;
  private MockedStatic<TextUtils> textUtilsMock;
  private MockedStatic<PreferenceManager> preferenceManagerMock;
  private MockedConstruction<Handler> handlerConstruction;

  @Before
  public void setUp() throws Exception {
    masterSecret = new MasterSecret(new SecretKeySpec(new byte[16], "AES"),
                                    new SecretKeySpec(new byte[16], "HmacSHA1"));
    looperMock = Mockito.mockStatic(Looper.class);
    looperMock.when(Looper::getMainLooper).thenReturn(null);

    handlerConstruction = Mockito.mockConstruction(Handler.class, (mock, context) -> {});

    logMock = Mockito.mockStatic(Log.class);
    Answer<Integer> logAnswer = new Answer<Integer>() {
      @Override
      public Integer answer(InvocationOnMock invocation) {
        final String tag = invocation.getArgument(0);
        final String msg = invocation.getArgument(1);
        System.out.println(invocation.getMethod().getName().toUpperCase() + "/[" + tag + "] " + msg);
        return 0;
      }
    };
    logMock.when(() -> Log.d(anyString(), anyString())).thenAnswer(logAnswer);
    logMock.when(() -> Log.i(anyString(), anyString())).thenAnswer(logAnswer);
    logMock.when(() -> Log.w(anyString(), anyString())).thenAnswer(logAnswer);
    logMock.when(() -> Log.e(anyString(), anyString())).thenAnswer(logAnswer);
    logMock.when(() -> Log.wtf(anyString(), anyString())).thenAnswer(logAnswer);
    logMock.when(() -> Log.wtf(anyString(), any(Throwable.class))).thenAnswer(invocation -> {
      final String tag = invocation.getArgument(0);
      final Throwable throwable = invocation.getArgument(1);
      System.out.println("WTF/[" + tag + "] " + throwable);
      return 0;
    });

    textUtilsMock = Mockito.mockStatic(TextUtils.class);
    textUtilsMock.when(() -> TextUtils.isEmpty(any(CharSequence.class))).thenAnswer(invocation -> {
      CharSequence s = invocation.getArgument(0);
      return s == null || s.length() == 0;
    });

    preferenceManagerMock = Mockito.mockStatic(PreferenceManager.class);
    preferenceManagerMock.when(() -> PreferenceManager.getDefaultSharedPreferences(any(Context.class)))
                         .thenReturn(sharedPreferences);

    when(sharedPreferences.getString(anyString(), anyString())).thenReturn("");
    when(sharedPreferences.getLong(anyString(), anyLong())).thenReturn(0L);
    when(sharedPreferences.getInt(anyString(), anyInt())).thenReturn(0);
    when(sharedPreferences.getBoolean(anyString(), anyBoolean())).thenReturn(false);
    when(sharedPreferences.getFloat(anyString(), anyFloat())).thenReturn(0f);
    when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
    when(context.getPackageName()).thenReturn("org.smssecure.smssecure");
  }

  @After
  public void tearDown() {
    if (handlerConstruction != null) {
      handlerConstruction.close();
    }
    if (preferenceManagerMock != null) {
      preferenceManagerMock.close();
    }
    if (textUtilsMock != null) {
      textUtilsMock.close();
    }
    if (logMock != null) {
      logMock.close();
    }
    if (looperMock != null) {
      looperMock.close();
    }
  }
}
