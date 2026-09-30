package com.cms.auth;

public enum TxnType {
    BALANCE_INQUIRY,
    WITHDRAWAL,      // ATM cash, single message: posts immediately
    PURCHASE,        // POS / e-commerce, single message: posts immediately
    PREAUTH,         // POS pre-authorisation: places a hold
    COMPLETION,      // captures a pre-authorisation hold
    REFUND,          // credit to the card account
    PIN_CHANGE,
    REVERSAL;

    /** Types that take money from the account. */
    public boolean isDebit() {
        return this == WITHDRAWAL || this == PURCHASE || this == PREAUTH || this == COMPLETION;
    }
}
