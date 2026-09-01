package org.smssecure.smssecure.util;

import android.content.Intent;
import android.os.Bundle;

import androidx.core.content.IntentCompat;
import androidx.core.os.BundleCompat;

import org.junit.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CompatExtrasTest {

  @Test
  @SuppressWarnings("deprecation")
  public void intentParcelableHandlesPresentAbsentAndWrongType() {
    Intent source = mock(Intent.class);
    Intent expected = mock(Intent.class);
    Bundle wrongType = mock(Bundle.class);

    when(source.getParcelableExtra("present")).thenReturn(expected);
    when(source.getParcelableExtra("wrong")).thenReturn(wrongType);

    assertThat(IntentCompat.getParcelableExtra(source, "present", Intent.class)).isSameAs(expected);
    assertThat(IntentCompat.getParcelableExtra(source, "absent", Intent.class)).isNull();
    assertThat(IntentCompat.getParcelableExtra(source, "wrong", Intent.class)).isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void bundleParcelableHandlesPresentAbsentAndWrongType() {
    Bundle source = mock(Bundle.class);
    Intent expected = mock(Intent.class);
    Bundle wrongType = mock(Bundle.class);

    when(source.getParcelable("present")).thenReturn(expected);
    when(source.getParcelable("wrong")).thenReturn(wrongType);

    assertThat(BundleCompat.getParcelable(source, "present", Intent.class)).isSameAs(expected);
    assertThat(BundleCompat.getParcelable(source, "absent", Intent.class)).isNull();
    assertThat(BundleCompat.getParcelable(source, "wrong", Intent.class)).isNull();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void bundleSerializableHandlesPresentAbsentAndWrongType() {
    Bundle source = mock(Bundle.class);
    Locale expected = Locale.CANADA;

    when(source.getSerializable("present")).thenReturn(expected);
    when(source.getSerializable("wrong")).thenReturn("not-a-locale");

    assertThat(BundleCompat.getSerializable(source, "present", Locale.class)).isSameAs(expected);
    assertThat(BundleCompat.getSerializable(source, "absent", Locale.class)).isNull();
    assertThat(BundleCompat.getSerializable(source, "wrong", Locale.class)).isNull();
  }
}
