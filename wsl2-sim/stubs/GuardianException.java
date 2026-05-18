package com.tandem.ext.guardian;

/** Stub de GuardianException para compilar sin tdmext.jar en WSL2. */
public class GuardianException extends Exception {
    public GuardianException(String message) { super(message); }
    public GuardianException(String message, Throwable cause) { super(message, cause); }
}
