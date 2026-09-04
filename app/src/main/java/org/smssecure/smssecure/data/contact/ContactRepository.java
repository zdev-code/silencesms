package org.smssecure.smssecure.data.contact;

import org.smssecure.smssecure.util.concurrent.AppTaskExecutor.TaskHandle;

import java.util.List;

public interface ContactRepository {
  TaskHandle load(String filter, Callback callback);

  interface Callback {
    void onSuccess(List<ContactEntry> contacts);
    void onFailure(Exception exception);
  }
}