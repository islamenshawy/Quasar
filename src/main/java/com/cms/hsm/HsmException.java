package com.cms.hsm;

/** Thrown for transport failures or non-"00" payShield error codes that are not a business outcome. */
public class HsmException extends RuntimeException {

    private final String command;
    private final String errorCode;

    public HsmException(String command, String errorCode, String message) {
        super(command + " failed, error " + errorCode + ": " + message);
        this.command = command;
        this.errorCode = errorCode;
    }

    public HsmException(String command, String message, Throwable cause) {
        super(command + " failed: " + message, cause);
        this.command = command;
        this.errorCode = null;
    }

    public String command()   { return command; }
    /** payShield 2-digit error code, or null for transport errors. */
    public String errorCode() { return errorCode; }
}
