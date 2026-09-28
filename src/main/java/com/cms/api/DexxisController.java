package com.cms.api;

import com.cms.card.CardIssuanceService;
import com.cms.card.CardIssuanceService.ActivateCardRequest;
import com.cms.card.CardIssuanceService.PersoData;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dexxis / kiosk interface. TEMPORARY REST form of the three operations; the SOAP endpoint
 * generated from the Dexxis WSDL will call the same service methods.
 *
 * PAN is always in the POST body, never in the URL (URLs end up in access logs).
 * In test, protect with mutual TLS / IP allow-list before connecting a real Dexxis.
 */
@RestController
@RequestMapping("/api/dexxis")
public class DexxisController {

    private final CardIssuanceService cards;

    public DexxisController(CardIssuanceService cards) {
        this.cards = cards;
    }

    /** Dexxis searches by card number and receives perso data. */
    @PostMapping("/cards/search")
    public PersoData search(@RequestBody Map<String, String> body) {
        return cards.getPersoData(body.get("pan"), "DEXXIS");
    }

    /** After successful print: activate and set PIN (PIN block under kiosk ZPK). */
    @PostMapping("/cards/activate")
    public Map<String, String> activate(@RequestBody ActivateCardRequest req) {
        cards.activateCard(req);
        return Map.of("result", "ACTIVE");
    }

    /** Print failure. */
    @PostMapping("/cards/cancel")
    public Map<String, String> cancel(@RequestBody Map<String, String> body) {
        cards.cancelCard(body.get("pan"), body.getOrDefault("reason", "print failure"), "DEXXIS");
        return Map.of("result", "CANCELLED");
    }
}
