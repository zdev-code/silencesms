/**
 * Copyright (C) 2011 Whisper Systems
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
package org.smssecure.smssecure.mms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import org.smssecure.smssecure.database.ApnDatabase;
import org.smssecure.smssecure.util.ServiceUtil;
import org.smssecure.smssecure.util.TelephonyUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;


import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

@SuppressWarnings("deprecation")
public abstract class LegacyMmsConnection {

  public static final String USER_AGENT = "Android-Mms/2.0";

  private static final String TAG = LegacyMmsConnection.class.getSimpleName();

  protected final Context context;
  protected final Apn     apn;

  protected LegacyMmsConnection(Context context) throws ApnUnavailableException {
    this.context = context;
    this.apn     = getApn(context);
  }

  public static Apn getApn(Context context) throws ApnUnavailableException {

    try {
      Optional<Apn> params = ApnDatabase.getInstance(context)
                                        .getMmsConnectionParameters(TelephonyUtil.getMccMnc(context),
                                                                    TelephonyUtil.getApn(context));

      if (!params.isPresent()) {
        throw new ApnUnavailableException("No parameters available from ApnDefaults.");
      }

      return params.get();
    } catch (IOException ioe) {
      throw new ApnUnavailableException("ApnDatabase threw an IOException", ioe);
    }
  }

  protected boolean isDirectConnect() {
    // We think Sprint supports direct connection over wifi/data, but not Verizon
    Set<String> sprintMccMncs = new HashSet<String>() {{
      add("312530");
      add("311880");
      add("311870");
      add("311490");
      add("310120");
      add("316010");
      add("312190");
    }};

    return ServiceUtil.getTelephonyManager(context).getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA &&
           sprintMccMncs.contains(TelephonyUtil.getMccMnc(context));
  }

  @SuppressWarnings("TryWithIdenticalCatches")
  protected static boolean checkRouteToHost(Context context, String host, boolean usingMmsRadio)
      throws IOException
  {
    InetAddress inetAddress = InetAddress.getByName(host);
    if (!usingMmsRadio) {
      if (inetAddress.isSiteLocalAddress()) {
        throw new IOException("RFC1918 address in non-MMS radio situation!");
      }
      Log.w(TAG, "returning vacuous success since MMS radio is not in use");
      return true;
    }

    if (inetAddress == null) {
      throw new IOException("Unable to lookup host: InetAddress.getByName() returned null.");
    }

    byte[] ipAddressBytes = inetAddress.getAddress();
    if (ipAddressBytes == null) {
      Log.w(TAG, "resolved IP address bytes are null, returning true to attempt a connection anyway.");
      return true;
    }

    Log.w(TAG, "Checking route to address: " + host + ", " + inetAddress.getHostAddress());
    ConnectivityManager manager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
    try {
      final Method  requestRouteMethod  = manager.getClass().getMethod("requestRouteToHostAddress", Integer.TYPE, InetAddress.class);
      final boolean routeToHostObtained = (Boolean) requestRouteMethod.invoke(manager, MmsRadio.TYPE_MOBILE_MMS, inetAddress);
      Log.w(TAG, "requestRouteToHostAddress(" + inetAddress + ") -> " + routeToHostObtained);
      return routeToHostObtained;
    } catch (NoSuchMethodException nsme) {
      Log.w(TAG, nsme);
    } catch (IllegalAccessException iae) {
      Log.w(TAG, iae);
    } catch (InvocationTargetException ite) {
      Log.w(TAG, ite);
    }

    return false;
  }

  protected byte[] execute(Request request, @Nullable Proxy proxy) throws IOException {
  Log.w(TAG, "connecting to " + request.url());

    OkHttpClient client = buildHttpClient(proxy);

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("Unhandled response code: " + response.code());
      }

      ResponseBody body = response.body();
      if (body == null) {
        throw new IOException("Empty response body");
      }

      byte[] data = body.bytes();
      Log.w(TAG, "Received full server response, " + data.length + " bytes");
      return data;
    }
  }

  private OkHttpClient buildHttpClient(@Nullable Proxy proxy) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS));

    if (proxy != null) {
      builder.proxy(proxy);
    }

    return builder.build();
  }

  protected void applyBaseHeaders(Request.Builder builder) {
    Headers headers = getBaseHeaders();
    for (int i = 0; i < headers.size(); i++) {
      builder.addHeader(headers.name(i), headers.value(i));
    }
  }

  @SuppressLint("HardwareIds")
  protected Headers getBaseHeaders() {
    Headers.Builder builder = new Headers.Builder();
    String number = null;

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
      try {
        TelephonyManager telephonyManager = TelephonyUtil.getManager(context);

        if (telephonyManager != null) {
          number = telephonyManager.getLine1Number();
        }
      } catch (SecurityException securityException) {
        Log.w(TAG, "Unable to read line1 number due to missing permission", securityException);
      }
    }

    builder.add("Accept", "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic");
    builder.add("x-wap-profile", "http://www.google.com/oha/rdf/ua-profile-kila.xml");
    builder.add("x-carrier-magic", "http://magic.google.com");

    if (!TextUtils.isEmpty(number)) {
      builder.add("x-up-calling-line-id", number);
      builder.add("X-MDN", number);
    }

    String userAgent = SilencePreferences.getMmsUserAgent(context, USER_AGENT);
    if (!TextUtils.isEmpty(userAgent)) {
      builder.add("User-Agent", userAgent);
    }

    if (apn.hasAuthentication()) {
      builder.add("Authorization", Credentials.basic(apn.getUsername(), apn.getPassword() == null ? "" : apn.getPassword()));
    }

    return builder.build();
  }

  @Nullable
  protected Proxy buildProxy(@Nullable String host, int port) {
    if (TextUtils.isEmpty(host)) {
      return null;
    }

    return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
  }

  public static class Apn {

    public static Apn EMPTY = new Apn("", "", "", "", "");

    private final String mmsc;
    private final String proxy;
    private final String port;
    private final String username;
    private final String password;

    public Apn(String mmsc, String proxy, String port, String username, String password) {
      this.mmsc     = mmsc;
      this.proxy    = proxy;
      this.port     = port;
      this.username = username;
      this.password = password;
    }

    public Apn(Apn customApn, Apn defaultApn,
               boolean useCustomMmsc,
               boolean useCustomProxy,
               boolean useCustomProxyPort,
               boolean useCustomUsername,
               boolean useCustomPassword)
    {
      this.mmsc     = useCustomMmsc ? customApn.mmsc : defaultApn.mmsc;
      this.proxy    = useCustomProxy ? customApn.proxy : defaultApn.proxy;
      this.port     = useCustomProxyPort ? customApn.port : defaultApn.port;
      this.username = useCustomUsername ? customApn.username : defaultApn.username;
      this.password = useCustomPassword ? customApn.password : defaultApn.password;
    }

    public boolean hasProxy() {
      return !TextUtils.isEmpty(proxy);
    }

    public String getMmsc() {
      return mmsc;
    }

    public String getProxy() {
      return hasProxy() ? proxy : null;
    }

    public int getPort() {
      return TextUtils.isEmpty(port) ? 80 : Integer.parseInt(port);
    }

    public boolean hasAuthentication() {
      return !TextUtils.isEmpty(username);
    }

    public String getUsername() {
      return username;
    }

    public String getPassword() {
      return password;
    }

    @Override
    public String toString() {
      return Apn.class.getSimpleName() +
          "{ mmsc: \"" + mmsc + "\"" +
          ", proxy: " + (proxy == null ? "none" : '"' + proxy + '"') +
          ", port: " + (port == null ? "(none)" : port) +
          ", user: " + (username == null ? "none" : '"' + username + '"') +
          ", pass: " + (password == null ? "none" : '"' + password + '"') + " }";
    }
  }
}
