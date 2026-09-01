package org.smssecure.smssecure.notifications;

final class NotificationActionIdentity {

  private NotificationActionIdentity() {}

  static String data(String action, long identity) {
    return "silence://notification/" + action + "/" + identity;
  }

  static int requestCode(String action, long identity) {
    long value = 31L * action.hashCode() + identity;
    return (int) (value ^ (value >>> 32)) & Integer.MAX_VALUE;
  }
}