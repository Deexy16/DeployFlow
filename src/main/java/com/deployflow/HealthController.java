package com.deployflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${app.version:dev}")
    private String version;

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", version);
    }

    @GetMapping("/info")
    public AppInfo info() {
        return new AppInfo(
                "DeployFlow",
                "Java application deployment system",
                version,
                Runtime.version().feature());
    }

    public record HealthResponse(String status, String version) {
    }

    public record AppInfo(String name, String description, String version, int javaVersion) {
    }
}