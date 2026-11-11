package com.optionmgr.health;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check for the frontend / E2E setup. See api-contract.md "GET /api/health". */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    static final String UNKNOWN_VERSION = "dev";

    private final Clock clock;
    private final String version;

    public HealthController(Clock clock, ObjectProvider<BuildProperties> buildProperties) {
        this.clock = clock;
        BuildProperties props = buildProperties.getIfAvailable();
        this.version = props != null && props.getVersion() != null ? props.getVersion() : UNKNOWN_VERSION;
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", version, clock.instant());
    }
}
