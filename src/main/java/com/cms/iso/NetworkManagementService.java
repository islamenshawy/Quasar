package com.cms.iso;

import com.cms.card.IssuanceException;
import com.cms.card.KeyRepository;
import com.cms.common.AuditLog;
import com.cms.hsm.HsmException;
import com.cms.hsm.PayShieldClient;
import com.cms.hsm.PayShieldClient.ImportedKey;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 1804 network management from the switch (function code in field 24):
 *   801 sign-on, 802 sign-off, 831 echo test, 811 key change (dynamic ZPK exchange, CMS-054).
 * Answers 1814 with action code 800 (accepted) or 909.
 *
 * Key change: field 96 carries the new acquirer ZPK under the ZMK (U + 32H) followed by its KCV (6H).
 * The HSM translates it to LMK encryption (FA); the KCV must match before the new key is stored
 * as the next version of the acquirer ZPK. The old version is retired, never deleted.
 */
@Service
public class NetworkManagementService {

    private static final Logger log = LoggerFactory.getLogger(NetworkManagementService.class);
    public static final String ACCEPTED = "800";

    private final JdbcTemplate jdbc;
    private final PayShieldClient hsm;
    private final KeyRepository keys;
    private final AuditLog audit;
    private final String zmkName;
    private final String zpkName;

    public NetworkManagementService(JdbcTemplate jdbc, PayShieldClient hsm, KeyRepository keys, AuditLog audit,
                                    @Value("${cms.keys.corehost-zmk-name:ZMK_COREHOST}") String zmkName,
                                    @Value("${cms.keys.corehost-zpk-name}") String zpkName) {
        this.jdbc = jdbc;
        this.hsm = hsm;
        this.keys = keys;
        this.audit = audit;
        this.zmkName = zmkName;
        this.zpkName = zpkName;
    }

    public ISOMsg handle(ISOMsg req, IsoCodec codec, String peer) throws ISOException {
        String fc = req.hasField(24) ? req.getString(24) : "831";
        String action = switch (fc) {
            case "801" -> { log.info("Sign-on from {}", peer); yield ACCEPTED; }
            case "802" -> { log.info("Sign-off from {}", peer); yield ACCEPTED; }
            case "831" -> ACCEPTED;
            case "811" -> keyChange(req.getString(96), peer);
            default -> "904";
        };
        ISOMsg resp = codec.newMessage();
        resp.setMTI(IsoMapper.responseMti(req.getMTI()));
        for (int f : new int[]{7, 11, 12, 24, 32, 33, 37}) {
            if (req.hasField(f)) resp.set(f, req.getString(f));
        }
        resp.set(39, action);
        return resp;
    }

    private String keyChange(String f96, String peer) {
        if (f96 == null || f96.length() < 39) {
            log.warn("Key change from {} without key data", peer);
            return "904";
        }
        try {
            int version = changeAcquirerZpk(f96.substring(0, 33), f96.substring(33, 39), "SWITCH:" + peer);
            log.info("Acquirer ZPK changed to version {} (from {})", version, peer);
            return ACCEPTED;
        } catch (IssuanceException | HsmException e) {
            log.error("Key change from {} rejected: {}", peer, e.getMessage());
            return "909";
        }
    }

    /** Imports a ZPK received under the ZMK and makes it the active acquirer ZPK. @return new version */
    @Transactional
    public int changeAcquirerZpk(String zpkUnderZmk, String kcv, String actor) {
        ImportedKey k = hsm.importZpk(keys.requireActiveKey(zmkName), zpkUnderZmk);
        int n = Math.min(kcv.length(), k.kcv().length());
        if (n < 6 || !k.kcv().substring(0, n).equalsIgnoreCase(kcv.substring(0, n))) {
            throw new IssuanceException("KCV_MISMATCH", "KCV of the received ZPK does not match");
        }
        Integer current = jdbc.query("SELECT version FROM hsm_key WHERE key_name = ? AND active FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, zpkName);
        int next = (current == null ? 0 : current) + 1;
        jdbc.update("UPDATE hsm_key SET active = FALSE, retired_at = now() WHERE key_name = ? AND active", zpkName);
        jdbc.update("""
                INSERT INTO hsm_key (key_name, key_type, key_scheme, key_under_lmk, kcv, version, active)
                VALUES (?, 'ZPK', ?, ?, ?, ?, TRUE)
                """, zpkName, k.keyUnderLmk().substring(0, 1), k.keyUnderLmk(), k.kcv(), next);
        audit.record(actor, "KEY_CHANGE", "hsm_key", null, Map.of("key", zpkName, "version", next, "kcv", k.kcv()));
        return next;
    }
}
