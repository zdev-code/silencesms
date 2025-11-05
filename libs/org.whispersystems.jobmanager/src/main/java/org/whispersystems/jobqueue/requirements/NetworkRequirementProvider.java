/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.jobqueue.requirements;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.os.Build;

import androidx.core.content.ContextCompat;

public class NetworkRequirementProvider implements RequirementProvider {

  private RequirementListener listener;

  private final Context appContext;
  private final NetworkRequirement requirement;

  private final ConnectivityManager connectivityManager;
  private final NetworkCallback networkCallback;

  public NetworkRequirementProvider(Context context) {
    this.appContext = context.getApplicationContext();
    this.requirement = new NetworkRequirement(appContext);
    this.connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

    NetworkCallback callback = null;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connectivityManager != null) {
      callback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
          notifyListenerIfSatisfied();
        }

        @Override
        public void onLost(Network network) {
          notifyListenerIfSatisfied();
        }
      };

      try {
        connectivityManager.registerDefaultNetworkCallback(callback);
      } catch (SecurityException ignored) {
        // We do not have network state permission, requirement will remain unsatisfied.
      }
    } else {
  IntentFilter filter = new IntentFilter();
  filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");

  ContextCompat.registerReceiver(appContext, new ConnectivityChangeReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    this.networkCallback = callback;
  }

  private class ConnectivityChangeReceiver extends android.content.BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      notifyListenerIfSatisfied();
    }
  }

  private void notifyListenerIfSatisfied() {
    RequirementListener currentListener = listener;
    if (currentListener == null) {
      return;
    }

    if (hasNetworkStatePermission() && requirement.isPresent()) {
      currentListener.onRequirementStatusChanged();
    }
  }

  private boolean hasNetworkStatePermission() {
    return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void setListener(RequirementListener listener) {
    this.listener = listener;
  }

}
