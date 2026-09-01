package org.smssecure.smssecure.mms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.smssecure.smssecure.util.Util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

public class MmsRadio {

  private static final String TAG = MmsRadio.class.getSimpleName();
  private static final long WAKE_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

  private static MmsRadio instance;

  public static synchronized MmsRadio getInstance(Context context) {
    if (instance == null)
      instance = new MmsRadio(context.getApplicationContext());

    return instance;
  }

  ///

  private static final String FEATURE_ENABLE_MMS = "enableMMS";
  private static final int APN_ALREADY_ACTIVE    = 0;
  public  static final int TYPE_MOBILE_MMS       = 2;

  private final Context context;

  private ConnectivityManager   connectivityManager;
  private ConnectivityListener  connectivityListener;
  private ConnectivityManager.NetworkCallback networkCallback;
  private Network mmsNetwork;
  private boolean networkUnavailable;
  private PowerManager.WakeLock wakeLock;
  private int connectedCounter = 0;

  private MmsRadio(Context context) {
    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    this.context             = context;
    this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.wakeLock            = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "silence:mms");
    this.wakeLock.setReferenceCounted(true);
  }

  public synchronized void disconnect() {
    Log.w(TAG, "MMS Radio Disconnect Called...");
    wakeLock.release();
    connectedCounter--;

    Log.w(TAG, "Reference count: " + connectedCounter);

    if (connectedCounter == 0) {
      Log.w(TAG, "Turning off MMS radio...");
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        connectivityManager.bindProcessToNetwork(null);
        if (networkCallback != null) {
          connectivityManager.unregisterNetworkCallback(networkCallback);
          networkCallback = null;
        }
        mmsNetwork = null;
        networkUnavailable = false;
        return;
      }

      stopLegacyMmsNetwork();
    }
  }

  @SuppressWarnings("deprecation") // API 23-28 carrier MMS fallback.
  private void stopLegacyMmsNetwork() {
      try {
        final Method stopUsingNetworkFeatureMethod = connectivityManager.getClass().getMethod("stopUsingNetworkFeature", Integer.TYPE, String.class);
        stopUsingNetworkFeatureMethod.invoke(connectivityManager, ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_MMS);
      } catch (NoSuchMethodException nsme) {
        Log.w(TAG, nsme);
      } catch (IllegalAccessException iae) {
        Log.w(TAG, iae);
      } catch (InvocationTargetException ite) {
        Log.w(TAG, ite);
      }
      
      if (connectivityListener != null) {
        Log.w(TAG, "Unregistering receiver...");
        context.unregisterReceiver(connectivityListener);
        connectivityListener = null;
      }
  }

  public synchronized void connect() throws MmsRadioException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      connectModern();
    } else {
      connectLegacy();
    }
  }

  private void connectModern() throws MmsRadioException {
    if (mmsNetwork != null) {
      wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
      connectedCounter++;
      return;
    }

    networkUnavailable = false;
    networkCallback = new ConnectivityManager.NetworkCallback() {
      @Override
      public void onAvailable(Network network) {
        synchronized (MmsRadio.this) {
          mmsNetwork = network;
          connectivityManager.bindProcessToNetwork(network);
          MmsRadio.this.notifyAll();
        }
      }

      @Override
      public void onUnavailable() {
        synchronized (MmsRadio.this) {
          networkUnavailable = true;
          MmsRadio.this.notifyAll();
        }
      }

      @Override
      public void onLost(Network network) {
        synchronized (MmsRadio.this) {
          if (network.equals(mmsNetwork)) mmsNetwork = null;
        }
      }
    };

    NetworkRequest request = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_MMS)
        .build();
    connectivityManager.requestNetwork(request, networkCallback, 30000);
    Util.wait(this, 30000);

    if (mmsNetwork == null || networkUnavailable) {
      if (networkCallback != null) connectivityManager.unregisterNetworkCallback(networkCallback);
      networkCallback = null;
      throw new MmsRadioException("Unable to acquire a carrier MMS network.");
    }

    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    connectedCounter++;
  }

  @SuppressWarnings("deprecation") // API 23-28 carrier MMS fallback.
  private void connectLegacy() throws MmsRadioException {
    int status;

    try {
      final Method startUsingNetworkFeatureMethod = connectivityManager.getClass().getMethod("startUsingNetworkFeature", Integer.TYPE, String.class);
      status = (int)startUsingNetworkFeatureMethod.invoke(connectivityManager, ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_MMS);
    } catch (NoSuchMethodException nsme) {
      throw new MmsRadioException(nsme);
    } catch (IllegalAccessException iae) {
      throw new MmsRadioException(iae);
    } catch (InvocationTargetException ite) {
      throw new MmsRadioException(ite);
    }

    Log.w(TAG, "startUsingNetworkFeature status: " + status);

    if (status == APN_ALREADY_ACTIVE) {
      wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
      connectedCounter++;
      return;
    } else {
      wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
      connectedCounter++;

      if (connectivityListener == null) {
        IntentFilter filter  = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        connectivityListener = new ConnectivityListener();
        ContextCompat.registerReceiver(context, connectivityListener, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
      }

      Util.wait(this, 30000);

      if (!isConnected()) {
        Log.w(TAG, "Got back from connectivity wait, and not connected...");
        disconnect();
        throw new MmsRadioException("Unable to successfully enable MMS radio.");
      }
    }
  }

  private boolean isConnected() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return mmsNetwork != null;
    return isLegacyConnected();
  }

  @SuppressWarnings("deprecation") // API 23-28 carrier MMS fallback.
  private boolean isLegacyConnected() {
    NetworkInfo info = connectivityManager.getNetworkInfo(TYPE_MOBILE_MMS);

    Log.w(TAG, "Connected: " + info);

    if ((info == null) || (info.getType() != TYPE_MOBILE_MMS) || !info.isConnected())
      return false;

    return true;
  }

  @SuppressWarnings("deprecation") // API 23-28 carrier MMS fallback.
  private boolean isConnectivityPossible() {
    NetworkInfo networkInfo = connectivityManager.getNetworkInfo(TYPE_MOBILE_MMS);

    return networkInfo != null  && networkInfo.isAvailable();
  }

  @SuppressWarnings("deprecation") // API 23-28 carrier MMS fallback.
  private boolean isConnectivityFailure() {
    NetworkInfo networkInfo = connectivityManager.getNetworkInfo(TYPE_MOBILE_MMS);

    return networkInfo == null || networkInfo.getDetailedState() == NetworkInfo.DetailedState.FAILED;
  }

  private synchronized void issueConnectivityChange() {
    if (isConnected()) {
      Log.w(TAG, "Notifying connected...");
      notifyAll();
      return;
    }

    if (!isConnected() && (isConnectivityFailure() || !isConnectivityPossible())) {
      Log.w(TAG, "Notifying not connected...");
      notifyAll();
      return;
    }
  }

  private class ConnectivityListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      Log.w(TAG, "Got connectivity change...");
      issueConnectivityChange();
    }
  }


}
