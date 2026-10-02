package com.cms.core;

/**
 * Funds interface to core banking for accounts whose type has ledger_mode CORE_BANKING (CMS-090).
 *
 * PROVISIONAL contract (IN-06) until the bank's core banking API spec is received. REST + JSON:
 * <pre>
 *   GET  {base}/health                                  200 when up
 *   POST {base}/accounts/{ref}/balance    {currency}
 *   POST {base}/accounts/{ref}/debits     Posting       amount + fee leave the account (force = may go negative)
 *   POST {base}/accounts/{ref}/credits    Posting
 *   POST {base}/accounts/{ref}/holds      Posting       earmark; answer carries coreRef = hold reference
 *   POST {base}/holds/{holdRef}/capture   Posting       debit up to the hold, hold closed
 *   POST {base}/holds/{holdRef}/release   {reference}
 *   POST {base}/postings/{originalRef}/reverse  Posting amount = principal to give back; fee included when includeFee
 * </pre>
 * Every answer: {status: APPROVED | DECLINED, reason, ledgerBalance, availableBalance, coreRef}.
 * Core must de-duplicate on Posting.reference: the CMS retries (store-and-forward) with the same reference.
 * Authentication: X-Api-Key header. Amounts are minor units of the account currency.
 */
public interface CoreBankingClient {

    enum Status { APPROVED, DECLINED, UNAVAILABLE }

    /** reason for DECLINED: INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND, ACCOUNT_CLOSED, ACCOUNT_BLOCKED, DEBIT_BLOCKED, LIMIT_EXCEEDED, ... */
    record Result(Status status, String reason, Long ledgerBalance, Long availableBalance, String coreRef) {
        public boolean approved() { return status == Status.APPROVED; }
        public boolean unavailable() { return status == Status.UNAVAILABLE; }
        public static Result unavailable(String why) { return new Result(Status.UNAVAILABLE, why, null, null, null); }
    }

    /**
     * @param reference  unique per request, used by core for idempotency
     * @param type       WITHDRAWAL, PURCHASE, PREAUTH, COMPLETION, REFUND, FEE, ADVICE ...
     * @param force      post even if it takes the account negative (advices, store-and-forward replays)
     * @param includeFee reversals only: also give back the fee posted with the original
     */
    record Posting(String reference, String accountRef, long amount, long fee, String currency, String type,
                   String narrative, boolean force, boolean includeFee) {}

    boolean configured();

    Result health();

    Result balance(String accountRef, String currency);

    Result debit(Posting p);

    Result credit(Posting p);

    Result hold(Posting p);

    Result capture(String holdRef, Posting p);

    Result release(String holdRef, String reference);

    Result reverse(String originalReference, Posting p);
}
