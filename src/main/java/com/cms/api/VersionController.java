package com.cms.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /api/version -> build version and time, used in deployment verification and bug reports. */
@RestController
public class VersionController {

    private final ObjectProvider<BuildProperties> build;

    public VersionController(ObjectProvider<BuildProperties> build) {
        this.build = build;
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        BuildProperties b = build.getIfAvailable();
        if (b == null) return Map.of("version", "unknown (run from IDE without build-info)");
        return Map.of(
                "artifact", b.getArtifact(),
                "version", b.getVersion(),
                "buildTime", String.valueOf(b.getTime()));
    }
}
