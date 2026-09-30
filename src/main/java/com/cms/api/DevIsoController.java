package com.cms.api;

import com.cms.iso.CorehostSimulator;
import com.cms.iso.CorehostSimulator.SimRequest;
import com.cms.iso.CorehostSimulator.SimResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DEV ONLY: drive the corehost simulator, which sends real BASE24 ISO messages over TCP to this
 * CMS's ISO server. Not registered outside the dev profile.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/dev/iso")
public class DevIsoController {

    private final CorehostSimulator sim;

    public DevIsoController(CorehostSimulator sim) {
        this.sim = sim;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return sim.status();
    }

    @PostMapping("/send")
    public SimResult send(@RequestBody SimRequest req) throws Exception {
        return sim.send(req);
    }

    /** function: 801 sign-on, 802 sign-off, 831 echo, 811 key change (optional zpkClear, else random). */
    @PostMapping("/network")
    public SimResult network(@RequestBody Map<String, String> body) throws Exception {
        return sim.network(body.getOrDefault("function", "831"), body.get("zpkClear"));
    }
}
