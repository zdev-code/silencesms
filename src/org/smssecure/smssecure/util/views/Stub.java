package org.smssecure.smssecure.util.views;


import androidx.annotation.NonNull;
import android.view.View;
import android.view.ViewStub;

public class Stub<T extends View> {

  private ViewStub viewStub;
  private final Class<T> viewClass;
  private T view;

  public Stub(@NonNull ViewStub viewStub, @NonNull Class<T> viewClass) {
    this.viewStub = viewStub;
    this.viewClass = viewClass;
  }

  public T get() {
    if (view == null) {
      view = viewClass.cast(viewStub.inflate());
      viewStub = null;
    }

    return view;
  }

  public boolean resolved() {
    return view != null;
  }

}
