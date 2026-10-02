package com.cms.auth;

import java.util.Map;

/**
 * ISO 8583:1993 action codes (field 39) used by the CMS.
 * Confirm the exact set and any private-use codes against the BASE24 interface spec (IN-01).
 */
public final class ActionCode {

    private ActionCode() {}

    public static final String APPROVED = "000";
    public static final String DO_NOT_HONOUR = "100";
    public static final String SUSPECTED_FRAUD = "102";
    public static final String EXPIRED_CARD = "101";
    public static final String RESTRICTED_CARD = "104";
    public static final String PIN_TRIES_EXCEEDED = "106";
    public static final String INVALID_AMOUNT = "110";
    public static final String INVALID_CARD = "111";
    public static final String PIN_REQUIRED = "112";
    public static final String NO_ACCOUNT = "114";
    public static final String FUNCTION_NOT_SUPPORTED = "115";
    public static final String INSUFFICIENT_FUNDS = "116";
    public static final String INCORRECT_PIN = "117";
    public static final String NOT_PERMITTED_CARDHOLDER = "119";
    public static final String EXCEEDS_AMOUNT_LIMIT = "121";
    public static final String EXCEEDS_FREQUENCY_LIMIT = "123";
    public static final String CARD_NOT_EFFECTIVE = "125";
    public static final String SUSPECTED_COUNTERFEIT = "129";
    public static final String LOST_CARD = "208";
    public static final String STOLEN_CARD = "209";
    public static final String REVERSAL_ACCEPTED = "400";
    public static final String INVALID_TRANSACTION = "902";
    public static final String FORMAT_ERROR = "904";
    public static final String ISSUER_INOPERATIVE = "907";
    public static final String SYSTEM_MALFUNCTION = "909";
    public static final String ISSUER_TIMEOUT = "911";

    private static final Map<String, String> TEXT = Map.ofEntries(
            Map.entry(APPROVED, "Approved"),
            Map.entry(DO_NOT_HONOUR, "Do not honour"),
            Map.entry(SUSPECTED_FRAUD, "Suspected fraud"),
            Map.entry(EXPIRED_CARD, "Expired card"),
            Map.entry(RESTRICTED_CARD, "Restricted card"),
            Map.entry(PIN_TRIES_EXCEEDED, "Allowable PIN tries exceeded"),
            Map.entry(INVALID_AMOUNT, "Invalid amount"),
            Map.entry(INVALID_CARD, "Invalid card number"),
            Map.entry(PIN_REQUIRED, "PIN data required"),
            Map.entry(NO_ACCOUNT, "No account of type requested"),
            Map.entry(FUNCTION_NOT_SUPPORTED, "Requested function not supported"),
            Map.entry(INSUFFICIENT_FUNDS, "Not sufficient funds"),
            Map.entry(INCORRECT_PIN, "Incorrect PIN"),
            Map.entry(NOT_PERMITTED_CARDHOLDER, "Transaction not permitted to cardholder"),
            Map.entry(EXCEEDS_AMOUNT_LIMIT, "Exceeds amount limit"),
            Map.entry(EXCEEDS_FREQUENCY_LIMIT, "Exceeds frequency limit"),
            Map.entry(CARD_NOT_EFFECTIVE, "Card not effective"),
            Map.entry(SUSPECTED_COUNTERFEIT, "Suspected counterfeit card"),
            Map.entry(LOST_CARD, "Lost card, pick up"),
            Map.entry(STOLEN_CARD, "Stolen card, pick up"),
            Map.entry(REVERSAL_ACCEPTED, "Reversal accepted"),
            Map.entry(INVALID_TRANSACTION, "Invalid transaction"),
            Map.entry(FORMAT_ERROR, "Format error"),
            Map.entry(ISSUER_INOPERATIVE, "Issuer or switch inoperative"),
            Map.entry(SYSTEM_MALFUNCTION, "System malfunction"),
            Map.entry(ISSUER_TIMEOUT, "Card issuer timed out"));

    public static boolean isApproval(String code) {
        return APPROVED.equals(code) || REVERSAL_ACCEPTED.equals(code);
    }

    public static String text(String code) {
        return TEXT.getOrDefault(code, "Declined");
    }

    public static Map<String, String> all() {
        return TEXT;
    }
}
