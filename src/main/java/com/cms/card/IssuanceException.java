package com.cms.card;

/**
 * Business error returned to Dexxis / kiosk with a stable code.
 * Codes: INVALID_REQUEST, DUPLICATE, ACCOUNT_NOT_FOUND, CARD_NOT_FOUND, INVALID_STATUS,
 *        PRODUCT_NOT_ELIGIBLE, LIMIT_REACHED, RANGE_EXHAUSTED, CARD_EXPIRED, KEY_MISSING
 */
public class IssuanceException extends RuntimeException {

    private final String code;

    public IssuanceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
