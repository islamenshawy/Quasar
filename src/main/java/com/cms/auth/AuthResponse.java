package com.cms.auth;

/**
 * Result of an authorization. Balances are in the account currency, minor units,
 * and are only filled for approved requests (field 54 in the response).
 */
public record AuthResponse(String actionCode, String authId, String currencyCode, Long ledgerBalance,
                           Long availableBalance, Long txnId, String reason, String iccResponse) {

    public AuthResponse(String actionCode, String authId, String currencyCode, Long ledgerBalance,
                        Long availableBalance, Long txnId, String reason) {
        this(actionCode, authId, currencyCode, ledgerBalance, availableBalance, txnId, reason, null);
    }

    /** Same answer with field 55 for the chip (tag 91: ARPC + ARC). */
    public AuthResponse withIcc(String icc) {
        return new AuthResponse(actionCode, authId, currencyCode, ledgerBalance, availableBalance, txnId, reason, icc);
    }

    public boolean approved() {
        return ActionCode.isApproval(actionCode);
    }

    public static AuthResponse decline(String code, String reason, Long txnId) {
        return new AuthResponse(code, null, null, null, null, txnId, reason);
    }
}
