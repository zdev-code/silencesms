package org.smssecure.smssecure;

import android.app.Instrumentation;
import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

public class SilenceTestCase {

  public void setUp() throws Exception {
    System.setProperty("dexmaker.dexcache", getInstrumentation().getTargetContext().getCacheDir().getPath());
  }

  protected Context getContext() {
    return getInstrumentation().getContext();
  }

  protected Instrumentation getInstrumentation() {
    return InstrumentationRegistry.getInstrumentation();
  }
}
