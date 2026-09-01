package org.smssecure.smssecure.jobs.requirements;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.whispersystems.jobqueue.dependencies.ContextDependent;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.jobqueue.requirements.Requirement;

import androidx.core.content.ContextCompat;

public class NetworkOrServiceRequirement implements Requirement, ContextDependent {

  private transient Context context;

  public NetworkOrServiceRequirement(Context context) {
    this.context = context;
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }

  @Override
  public boolean isPresent() {
    if (context == null) {
      return false;
    }

    ServiceRequirement serviceRequirement = new ServiceRequirement(context);

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
      return serviceRequirement.isPresent();
    }

    NetworkRequirement networkRequirement = new NetworkRequirement(context);

    return networkRequirement.isPresent() || serviceRequirement.isPresent();
  }
}
