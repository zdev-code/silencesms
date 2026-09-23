package org.smssecure.smssecure.jobs.requirements;

import android.content.Context;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.RequiresApi;

import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.jobqueue.requirements.RequirementProvider;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation") // PhoneStateListener is required for the supported API 23-30 fallback.
public class ServiceRequirementProvider implements RequirementProvider {

  private final TelephonyManager     telephonyManager;
  private final PhoneStateListener   legacyServiceStateListener;
  private final ServiceStateRegistration serviceStateRegistration;
  private final AtomicBoolean        listeningForServiceState;

  private RequirementListener requirementListener;

  public ServiceRequirementProvider(Context context) {
    this.telephonyManager         = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    this.legacyServiceStateListener = new LegacyServiceStateListener();
    this.serviceStateRegistration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new Api31ServiceStateRegistration(context, telephonyManager, this::handleInService)
                    : null;
    this.listeningForServiceState = new AtomicBoolean(false);
  }

  @Override
  public void setListener(RequirementListener requirementListener) {
    this.requirementListener = requirementListener;
  }

  public void start() {
    if (listeningForServiceState.compareAndSet(false, true)) {
      registerListener();
    }
  }

  private void handleInService() {
    if (listeningForServiceState.compareAndSet(true, false)) {
      unregisterListener();
    }

    if (requirementListener != null) {
      requirementListener.onRequirementStatusChanged();
    }
  }

  private void registerListener() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      serviceStateRegistration.register();
    } else {
      registerLegacyListener(PhoneStateListener.LISTEN_SERVICE_STATE);
    }
  }

  private void unregisterListener() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      serviceStateRegistration.unregister();
    } else {
      registerLegacyListener(PhoneStateListener.LISTEN_NONE);
    }
  }

  @SuppressWarnings("deprecation")
  private void registerLegacyListener(int events) {
    telephonyManager.listen(legacyServiceStateListener, events);
  }

  private interface ServiceStateRegistration {
    void register();
    void unregister();
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private static class Api31ServiceStateRegistration extends TelephonyCallback
      implements TelephonyCallback.ServiceStateListener, ServiceStateRegistration {

    private final java.util.concurrent.Executor callbackExecutor;
    private final TelephonyManager telephonyManager;
    private final Runnable onInService;

    private Api31ServiceStateRegistration(Context context, TelephonyManager telephonyManager, Runnable onInService) {
      this.callbackExecutor = context.getMainExecutor();
      this.telephonyManager = telephonyManager;
      this.onInService      = onInService;
    }

    @Override
    public void register() {
      telephonyManager.registerTelephonyCallback(callbackExecutor, this);
    }

    @Override
    public void unregister() {
      telephonyManager.unregisterTelephonyCallback(this);
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
      if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) onInService.run();
    }
  }

  @SuppressWarnings("deprecation")
  private class LegacyServiceStateListener extends PhoneStateListener {
    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
      if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) handleInService();
    }
  }
}
