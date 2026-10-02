package com.cms.api;

import com.cms.notify.OtpService;
import com.cms.notify.OtpService.Sent;
import com.cms.notify.OtpService.Verified;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * API for authentication channels (3-D Secure ACS, mobile app, IVR), X-Api-Key = cms.channel.api-key (CMS-105).
 * <pre>
 *   POST /api/channel/otp/send    {pan, purpose}   -> {otpId, expiresAt, destination (masked mobile)}
 *   POST /api/channel/otp/verify  {otpId, code}    -> {verified, status, attemptsLeft}
 * </pre>
 * The PAN travels in the body only (never in the URL or logs).
 */
@RestController
@RequestMapping("/api/channel")
public class ChannelController {

    private final OtpService otp;

    public ChannelController(OtpService otp) {
        this.otp = otp;
    }

    @PostMapping("/otp/send")
    public Sent send(@RequestBody Map<String, String> body) {
        return otp.send(body.get("pan"), body.get("purpose"), "CHANNEL");
    }

    @PostMapping("/otp/verify")
    public Verified verify(@RequestBody Map<String, String> body) {
        return otp.verify(UUID.fromString(body.getOrDefault("otpId", "")), body.get("code"), "CHANNEL");
    }
}
