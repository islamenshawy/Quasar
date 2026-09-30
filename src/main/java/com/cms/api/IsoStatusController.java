package com.cms.api;

import com.cms.iso.IsoServer;
import com.cms.iso.IsoServer.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/admin/iso/status: whether the switch interface is listening and how many links are up. */
@RestController
public class IsoStatusController {

    private final IsoServer server;

    public IsoStatusController(IsoServer server) {
        this.server = server;
    }

    @GetMapping("/api/admin/iso/status")
    public Status status() {
        return server.status();
    }
}
