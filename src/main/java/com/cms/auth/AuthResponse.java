package com.cms.auth;

/**
 * Result of an authorization. Balances are in the account currency, minor units,
 * and are only filled for approved requests (field 54 in the response).
 */
public record AuthResponse(String actionCode, String authId, String currencyCode, Long ledgerBalance,
                           Long availableBalance, Long txnId, String reason) {

    public boolean approved() {
        return ActionCode.isApproval(actionCode);
    }

    static AuthResponse decline(String code, String reason, Long txnId) {
        return new AuthResponse(code, null, null, null, null, txnId, reason);
    }
}
