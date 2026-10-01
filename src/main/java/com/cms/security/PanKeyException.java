package com.cms.security;

/**
 * A stored PAN cannot be decrypted with the configured key: the card was encrypted under a different
 * CMS_PAN_ENC_KEY. A configuration fault, not a request error; the API answers 503 PAN_KEY_MISMATCH.
 */
public class PanKeyException extends IllegalStateException {

    public PanKeyException(Throwable cause) {
        super("Stored card number cannot be decrypted with the configured PAN key (CMS_PAN_ENC_KEY differs from the one used when the card was issued)", cause);
    }
}
