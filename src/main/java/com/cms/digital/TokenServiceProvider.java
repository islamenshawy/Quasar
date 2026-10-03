package com.cms.digital;

/**
 * Issuer -> token service provider messages (CMS-115): the issuer suspends, resumes or deletes a token, or moves it
 * to a replacement card so the wallet keeps working.
 *
 * PROVISIONAL contract (IN-08) until the scheme TSP specifications (Visa VTS issuer API / Mastercard MDES issuer
 * lifecycle API) are received. REST + JSON:
 * <pre>
 *   POST {base}/tokens/{tokenRef}/lifecycle   {action: SUSPEND | RESUME | DELETE | UPDATE_CARD, reason,
 *                                              pan + expiry (UPDATE_CARD only)}
 *   2xx = done; anything else is retried by the outbox.
 * </pre>
 */
public interface TokenServiceProvider {

    /** UPDATE_CARD only: the card the token now points to. Sensitive: never log. */
    record CardUpdate(String pan, String expiryYYMM) {
        @Override public String toString() {
            return "CardUpdate[expiry=" + expiryYYMM + "]";
        }
    }

    boolean configured();

    /** @throws RuntimeException when the TSP did not accept the message (the outbox retries) */
    void lifecycle(String tokenRef, String action, String reason, CardUpdate card);
}
