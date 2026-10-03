package com.cms.api;

import com.cms.digital.CardholderService;
import com.cms.digital.CardholderService.AppCard;
import com.cms.digital.CardholderService.AppTransaction;
import com.cms.digital.CardholderService.CardDetails;
import com.cms.digital.CardholderService.Controls;
import com.cms.digital.ThreeDsService;
import com.cms.digital.ThreeDsService.AuthenticationRequest;
import com.cms.digital.ThreeDsService.AuthenticationResult;
import com.cms.digital.TokenService.TokenView;
import com.cms.notify.OtpService.Sent;
import com.cms.notify.OtpService.Verified;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Digital channels (CMS-115), X-Api-Key = cms.channel.api-key (role CHANNEL).
 * <pre>
 *   3-D Secure ACS
 *     POST /api/channel/3ds/authenticate                 AuthenticationRequest -> {authId, transStatus Y|C|N|R, eci, cavv...}
 *     POST /api/channel/3ds/{authId}/challenge           {code}                -> same, after the cardholder's code
 *   Cardholder app (the bank's mobile / internet banking back end, customer already signed in)
 *     GET  /api/channel/app/customers/{cif}/cards
 *     GET  /api/channel/app/customers/{cif}/cards/{id}
 *     POST .../cards/{id}/freeze                         {frozen}
 *     PUT  .../cards/{id}/controls                       {atm, pos, ecom, contactless, international}
 *     GET  .../cards/{id}/transactions?size=20
 *     POST .../cards/{id}/otp                            {purpose: CARD_DETAILS | PIN_SET | ACTIVATION}
 *     POST .../cards/{id}/otp/verify                     {otpId, code}
 *     POST .../cards/{id}/details                        {otpId}               -> {pan, expiry, cvv2, name}
 *     POST .../cards/{id}/pin                            {otpId, pinBlock}     (ISO-0 under the channel ZPK)
 *     POST .../cards/{id}/activate                       {otpId}
 *     POST .../cards/{id}/report                         {status: LOST | STOLEN, note}
 *     GET  .../cards/{id}/tokens
 *     POST .../cards/{id}/tokens/{tokenId}/{action}      action: suspend | resume | delete
 * </pre>
 */
@RestController
@RequestMapping("/api/channel")
public class CardholderChannelController {

    private final ThreeDsService threeDs;
    private final CardholderService app;

    public CardholderChannelController(ThreeDsService threeDs, CardholderService app) {
        this.threeDs = threeDs;
        this.app = app;
    }

    // ---------------- 3-D Secure ----------------

    @PostMapping("/3ds/authenticate")
    public AuthenticationResult authenticate(@RequestBody AuthenticationRequest r) {
        return threeDs.authenticate(r, "ACS");
    }

    @PostMapping("/3ds/{authId}/challenge")
    public AuthenticationResult challenge(@PathVariable UUID authId, @RequestBody Map<String, String> body) {
        return threeDs.challenge(authId, body.get("code"), "ACS");
    }

    // ---------------- cardholder app ----------------

    @GetMapping("/app/customers/{cif}/cards")
    public List<AppCard> cards(@PathVariable String cif) {
        return app.cards(cif);
    }

    @GetMapping("/app/customers/{cif}/cards/{id}")
    public AppCard card(@PathVariable String cif, @PathVariable long id) {
        return app.card(cif, id);
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/freeze")
    public AppCard freeze(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, Boolean> body) {
        return app.freeze(cif, id, Boolean.TRUE.equals(body.get("frozen")));
    }

    @PutMapping("/app/customers/{cif}/cards/{id}/controls")
    public AppCard controls(@PathVariable String cif, @PathVariable long id, @RequestBody Controls c) {
        return app.controls(cif, id, c);
    }

    @GetMapping("/app/customers/{cif}/cards/{id}/transactions")
    public List<AppTransaction> transactions(@PathVariable String cif, @PathVariable long id,
                                             @RequestParam(defaultValue = "20") int size) {
        return app.transactions(cif, id, size);
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/otp")
    public Sent otp(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.sendOtp(cif, id, body.get("purpose"));
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/otp/verify")
    public Verified verify(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.verifyOtp(cif, id, uuid(body.get("otpId")), body.get("code"));
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/details")
    public CardDetails details(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.details(cif, id, uuid(body.get("otpId")));
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/pin")
    public AppCard pin(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.setPin(cif, id, uuid(body.get("otpId")), body.get("pinBlock"));
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/activate")
    public AppCard activate(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.activate(cif, id, uuid(body.get("otpId")));
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/report")
    public AppCard report(@PathVariable String cif, @PathVariable long id, @RequestBody Map<String, String> body) {
        return app.report(cif, id, body.get("status"), body.get("note"));
    }

    @GetMapping("/app/customers/{cif}/cards/{id}/tokens")
    public List<TokenView> tokens(@PathVariable String cif, @PathVariable long id) {
        return app.tokens(cif, id);
    }

    @PostMapping("/app/customers/{cif}/cards/{id}/tokens/{tokenId}/{action}")
    public TokenView tokenAction(@PathVariable String cif, @PathVariable long id, @PathVariable long tokenId,
                                 @PathVariable String action) {
        return app.tokenAction(cif, id, tokenId, action.toUpperCase());
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
