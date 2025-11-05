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
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import org.whispersystems.jobqueue.dependencies.ContextDependent;

import androidx.core.content.ContextCompat;

/**
 * A requirement that is satisfied when a network connection is present.
 */
public class NetworkRequirement implements Requirement, ContextDependent {

  private transient Context context;

  public NetworkRequirement(Context context) {
    this.context = context;
  }

  public NetworkRequirement() {}

  @SuppressLint("MissingPermission")
  @Override
  public boolean isPresent() {
    final Context localContext = context;

    if (localContext == null) {
      return false;
    }

    if (ContextCompat.checkSelfPermission(localContext, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
      return false;
    }

    ConnectivityManager cm = (ConnectivityManager) localContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) {
      return false;
    }

    try {
      Network activeNetwork = cm.getActiveNetwork();
      if (activeNetwork == null) {
        return false;
      }

      NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
      return hasInternetCapability(capabilities);
    } catch (SecurityException e) {
      return false;
    }
  }

  private static boolean hasInternetCapability(NetworkCapabilities capabilities) {
    if (capabilities == null) {
      return false;
    }

    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
      return false;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
      return true;
    }

    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) {
      return true;
    }

    return false;
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }
}
