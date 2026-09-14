package com.infinitesoft.launcher.core;

import java.nio.file.Path;

/**
 * Ambiente que el launcher levanta. Cambia puertos, carpeta de repos y hostname público.
 * En la PC de tienda: {@link #CAJA_ACTUAL} = copia productiva
 * ({@code controlneg_rmx_db}, :4200/:8088, sin Caddy ni notificaciones);
 * {@link #TIENDA_INFINITO} = producción (:4220/:8288/{@code controlneg_rmx_db_v02})
 * con túnel y correo Cloudflare. La BD ya va migrada; el launcher no aplica scripts.
 * En la laptop, {@link #DEV_LOCAL} apunta el POS/puente a {@code controlneg_rmx_db_v02};
 * el login sigue en {@code controlneg_rmx_db}.
 */
public enum LaunchEnvironment {
    DEV_LOCAL(
            "dev-local",
            "Dev local",
            "",
            "",
            "controlneg_rmx_db_v02",
            "Caddyfile",
            "cotiza.mayaksoluciones.com",
            "pos-local",
            4200,
            8080,
            8081,
            8082,
            8088,
            8095,
            3001,
            46494,
            true,
            true,
            true,
            true
    ),
    SANDBOX(
            "sandbox",
            "Sandbox",
            "sandbox",
            "sandbox",
            "controlneg_rmx_db_sandbox",
            "Caddyfile.sandbox",
            "pos-sandbox.mayaksoluciones.com",
            "pos-local",
            4210,
            8180,
            8181,
            8082,
            8188,
            8195,
            3011,
            46494,
            true,
            true,
            true,
            true
    ),
    CAJA_ACTUAL(
            "caja-actual",
            "Caja actual",
            "",
            "",
            "controlneg_rmx_db",
            "",
            "",
            "",
            4200,
            8080,
            8081,
            8082,
            8088,
            8095,
            3001,
            46494,
            false,
            false,
            false,
            false
    ),
    TIENDA_INFINITO(
            "tienda-infinito",
            "Tienda Infinito",
            "",
            "tienda-infinito",
            "controlneg_rmx_db_v02",
            "Caddyfile.tienda-infinito",
            "tienda-infinito.mayaksoluciones.com",
            "tienda-infinito",
            4220,
            8280,
            8281,
            8282,
            8288,
            8295,
            3021,
            46495,
            true,
            true,
            true,
            true
    );

    private final String id;
    private final String label;
    private final String reposSubdir;
    private final String springProfile;
    private final String databaseName;
    private final String caddyfileName;
    private final String publicHostname;
    private final String tunnelName;
    private final int frontPort;
    private final int caddyPort;
    private final int authPort;
    private final int smtpPort;
    private final int storePort;
    private final int puentePort;
    private final int healthPort;
    private final int tunnelMetricsPort;
    private final boolean startsSmtp;
    private final boolean startsPuente;
    private final boolean startsCaddy;
    private final boolean startsTunnel;

    LaunchEnvironment(
            String id,
            String label,
            String reposSubdir,
            String springProfile,
            String databaseName,
            String caddyfileName,
            String publicHostname,
            String tunnelName,
            int frontPort,
            int caddyPort,
            int authPort,
            int smtpPort,
            int storePort,
            int puentePort,
            int healthPort,
            int tunnelMetricsPort,
            boolean startsSmtp,
            boolean startsPuente,
            boolean startsCaddy,
            boolean startsTunnel
    ) {
        this.id = id;
        this.label = label;
        this.reposSubdir = reposSubdir;
        this.springProfile = springProfile;
        this.databaseName = databaseName;
        this.caddyfileName = caddyfileName;
        this.publicHostname = publicHostname;
        this.tunnelName = tunnelName;
        this.frontPort = frontPort;
        this.caddyPort = caddyPort;
        this.authPort = authPort;
        this.smtpPort = smtpPort;
        this.storePort = storePort;
        this.puentePort = puentePort;
        this.healthPort = healthPort;
        this.tunnelMetricsPort = tunnelMetricsPort;
        this.startsSmtp = startsSmtp;
        this.startsPuente = startsPuente;
        this.startsCaddy = startsCaddy;
        this.startsTunnel = startsTunnel;
    }

    public static LaunchEnvironment fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEV_LOCAL;
        }
        String v = raw.trim();
        for (LaunchEnvironment env : values()) {
            if (env.id.equalsIgnoreCase(v) || env.name().equalsIgnoreCase(v)) {
                return env;
            }
        }
        return DEV_LOCAL;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public String getSpringProfile() {
        return springProfile;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCaddyfileName() {
        return caddyfileName;
    }

    public String getPublicHostname() {
        return publicHostname;
    }

    public String getPublicUrl() {
        if (publicHostname == null || publicHostname.isBlank()) {
            return "http://localhost:" + frontPort;
        }
        return "https://" + publicHostname;
    }

    public boolean startsSmtp() {
        return startsSmtp;
    }

    public boolean startsPuente() {
        return startsPuente;
    }

    public boolean startsCaddy() {
        return startsCaddy;
    }

    public boolean startsTunnel() {
        return startsTunnel;
    }

    public String getTunnelName() {
        return tunnelName;
    }

    public int getFrontPort() {
        return frontPort;
    }

    public int getCaddyPort() {
        return caddyPort;
    }

    public int getAuthPort() {
        return authPort;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public int getStorePort() {
        return storePort;
    }

    public int getPuentePort() {
        return puentePort;
    }

    public int getHealthPort() {
        return healthPort;
    }

    public int getTunnelMetricsPort() {
        return tunnelMetricsPort;
    }

    public String getTunnelReadyUrl() {
        return "http://127.0.0.1:" + tunnelMetricsPort + "/ready";
    }

    public Path reposRoot(Path projectsBase) {
        if (reposSubdir == null || reposSubdir.isBlank()) {
            return projectsBase;
        }
        return projectsBase.resolve(reposSubdir);
    }

    public String jdbcUrl(String schema) {
        String extra = (schema == null || schema.isBlank()) ? "" : "?currentSchema=" + schema;
        return "jdbc:postgresql://localhost:5432/" + databaseName + extra;
    }

    /**
     * Auth de Dev local se queda en {@code controlneg_rmx_db} (clave de esta laptop).
     * POS/puente usan {@link #jdbcUrl(String)} → v02.
     */
    public String jdbcUrlAuth() {
        if (this == DEV_LOCAL) {
            return "jdbc:postgresql://localhost:5432/controlneg_rmx_db?currentSchema=security";
        }
        return jdbcUrl("security");
    }

    public String springProfileArg() {
        if (springProfile == null || springProfile.isBlank()) {
            return "";
        }
        return "--spring.profiles.active=" + springProfile;
    }
}
