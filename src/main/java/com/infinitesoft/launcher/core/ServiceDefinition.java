package com.infinitesoft.launcher.core;

import java.nio.file.Path;

public class ServiceDefinition {
    private final String name;
    private final ServiceType type;
    private final Path workingDir;
    private final String startCommand; // full command to run (Windows friendly)
    private final String stopCommand;  // optional for Docker/Node
    private final String healthUrl;    // may be null
    private final Integer tcpPort;     // optional, for DB/front
    private final int order;           // startup order

    public ServiceDefinition(String name, ServiceType type, Path workingDir, String startCommand, String stopCommand, String healthUrl, Integer tcpPort, int order) {
        this.name = name;
        this.type = type;
        this.workingDir = workingDir;
        this.startCommand = startCommand;
        this.stopCommand = stopCommand;
        this.healthUrl = healthUrl;
        this.tcpPort = tcpPort;
        this.order = order;
    }

    public String getName() { return name; }
    public ServiceType getType() { return type; }
    public Path getWorkingDir() { return workingDir; }
    public String getStartCommand() { return startCommand; }
    public String getStopCommand() { return stopCommand; }
    public String getHealthUrl() { return healthUrl; }
    public Integer getTcpPort() { return tcpPort; }
    public int getOrder() { return order; }
}
