package com.cms.hsm;

import com.cms.hsm.PayShieldClient.PinBlockFormat;

/**
 * The three PIN flows the CMS needs, expressed as HSM command sequences.
 * No clear PIN ever exists in this process: PIN blocks arrive under a ZPK,
 * are handled only by the payShield, and only the PVV is returned for storage.
 *
 * PIN try counting, card status checks and persistence live in the auth engine,
 * not here - this class is pure cryptographic orchestration.
 */
public final class PinService {

    private final PayShieldClient hsm;

    public PinService(PayShieldClient hsm) {
        this.hsm = hsm;
    }

    /**
     * Activation / first PIN set from the kiosk (after successful print).
     * JE (ZPK -> LMK)  then  DG (generate PVV).
     * @return PVV to store on the card record together with the PVKI.
     */
    public String setPin(String kioskZpk, String pvk, char pvki,
                         String pinBlock, PinBlockFormat fmt, String pan) {
        String pinUnderLmk = hsm.translatePinZpkToLmk(kioskZpk, pinBlock, fmt, pan);
        return hsm.generatePvv(pvk, pinUnderLmk, pan, pvki);
    }

    /**
     * PIN verification for balance inquiry / withdrawal from the ATM corehost.
     * EC (verify interchange PIN, VISA PVV method).
     */
    public boolean verifyPin(String corehostZpk, String pvk, char pvki, String storedPvv,
                             String pinBlock, PinBlockFormat fmt, String pan) {
        return hsm.verifyPinVisa(corehostZpk, pvk, pinBlock, fmt, pan, pvki, storedPvv);
    }

    /**
     * PIN change: verify old PIN, then derive PVV for the new PIN.
     * EC (old)  ->  JE (new, ZPK -> LMK)  ->  DG (new PVV).
     * Caller updates the stored PVV atomically only if a new PVV is returned.
     * @return new PVV, or null if the old PIN was wrong.
     */
    public String changePin(String corehostZpk, String pvk, char pvki, String storedPvv,
                            String oldPinBlock, String newPinBlock,
                            PinBlockFormat fmt, String pan) {
        boolean oldOk = hsm.verifyPinVisa(corehostZpk, pvk, oldPinBlock, fmt, pan, pvki, storedPvv);
        if (!oldOk) {
            return null;
        }
        String newPinUnderLmk = hsm.translatePinZpkToLmk(corehostZpk, newPinBlock, fmt, pan);
        return hsm.generatePvv(pvk, newPinUnderLmk, pan, pvki);
    }
}
