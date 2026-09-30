package com.cms.auth;

/**
 * Authorization request in the CMS's own terms, independent of BASE24 / ISO 8583 layout.
 * The ISO channel (and the dev endpoint) map incoming messages into this record.
 *
 * Sensitive fields (pan, track2, pinBlock, newPinBlock) must never be logged; toString masks them.
 */
public record AuthRequest(
        TxnType type,
        Channel channel,
        String mti,                 // e.g. 1200, 1100, 1420, 1220 (advice)
        String processingCode,      // field 3, 6 digits
        String pan,                 // field 2 (or from track 2)
        String expiryYYMM,          // field 14 or from track 2; optional
        String track2,              // field 35; optional
        String pinBlock,            // field 52 under the acquirer ZPK; optional
        String newPinBlock,         // PIN change only
        long amount,                // field 4, minor units of the transaction currency
        String currencyNumeric,     // field 49, ISO 4217 numeric
        String stan,                // field 11
        String rrn,                 // field 37
        String transmissionDt,      // field 7, MMDDhhmmss
        String localDt,             // field 12
        String acquirerId,          // field 32
        String terminalId,          // field 41
        String merchantType,        // field 26 (MCC); optional
        String cardAcceptor,        // field 43; optional
        boolean advice,             // stand-in advice from the switch: record and post, never decline
        OriginalRef original,       // reversal / completion: the original message
        Long amountCompleted,       // reversal: amount actually completed (dispensed); 0 or null = full
        String iccData) {           // field 55 as hex; chip transactions only. Never logged.

    /** Without chip data (magstripe, manual, tests). */
    public AuthRequest(TxnType type, Channel channel, String mti, String processingCode, String pan, String expiryYYMM,
                       String track2, String pinBlock, String newPinBlock, long amount, String currencyNumeric,
                       String stan, String rrn, String transmissionDt, String localDt, String acquirerId,
                       String terminalId, String merchantType, String cardAcceptor, boolean advice,
                       OriginalRef original, Long amountCompleted) {
        this(type, channel, mti, processingCode, pan, expiryYYMM, track2, pinBlock, newPinBlock, amount, currencyNumeric,
                stan, rrn, transmissionDt, localDt, acquirerId, terminalId, merchantType, cardAcceptor, advice, original,
                amountCompleted, null);
    }

    /**
     * Original data elements (field 56): identify the transaction a reversal or completion refers to.
     * transmissionDt matches either the original's transmission date-time (field 7, MMDDhhmmss)
     * or its local date-time (field 12, YYMMDDhhmmss), whichever the switch sends.
     */
    public record OriginalRef(String mti, String stan, String transmissionDt, String acquirerId) {
        public String key() {
            return mti + "|" + stan + "|" + transmissionDt + "|" + acquirerId;
        }
    }

    public boolean hasPin() {
        return pinBlock != null && !pinBlock.isBlank();
    }

    @Override
    public String toString() {
        String masked = pan == null || pan.length() < 10 ? "?" : pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
        return "AuthRequest[" + type + " " + channel + " mti=" + mti + " pan=" + masked + " amount=" + amount
                + " ccy=" + currencyNumeric + " stan=" + stan + " term=" + terminalId + " advice=" + advice + "]";
    }
}
