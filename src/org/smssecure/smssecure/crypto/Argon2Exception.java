package org.smssecure.smssecure.crypto;

import java.security.GeneralSecurityException;

public class Argon2Exception extends GeneralSecurityException {
  public Argon2Exception(String message) {
    super(message);
  }
}