package com.infinitesoft.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Copia a disco los scripts de preparación (firewall / PATH) y los ejecuta
 * según el sistema operativo. El túnel de Cloudflare no necesita puertos
 * públicos: estas reglas son para tablets/cajas en la LAN de la tienda.
 */
public final class PrepareHost {
    private static final Path SCRIPTS_DIR =
            Path.of(System.getProperty("user.home"), ".infinitesoft", "scripts");

    private PrepareHost() {}

    public static Path ensureScriptsOnDisk() throws IOException {
        Files.createDirectories(SCRIPTS_DIR);
        copyResource("scripts/prepare-host.sh", SCRIPTS_DIR.resolve("prepare-host.sh"));
        copyResource("scripts/prepare-host.ps1", SCRIPTS_DIR.resolve("prepare-host.ps1"));
        Path cloudflaredDir = Path.of(System.getProperty("user.home"), ".cloudflared");
        Files.createDirectories(cloudflaredDir);
        Path tunnelExample = cloudflaredDir.resolve("config-tienda-infinito.yml.example");
        copyResource("cloudflared/config-tienda-infinito.yml.example", tunnelExample);
        if (!OsSupport.isWindows()) {
            SCRIPTS_DIR.resolve("prepare-host.sh").toFile().setExecutable(true);
        }
        return SCRIPTS_DIR;
    }

    public static void run(LaunchEnvironment env, Consumer<String> log) throws IOException, InterruptedException {
        Path dir = ensureScriptsOnDisk();
        List<String> command = new ArrayList<>();
        if (OsSupport.isWindows()) {
            command.add("powershell.exe");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(dir.resolve("prepare-host.ps1").toString());
        } else {
            command.add("sh");
            command.add(dir.resolve("prepare-host.sh").toString());
        }
        command.add(env.getId());
        for (Integer port : OsSupport.lanListenPorts(env)) {
            command.add(String.valueOf(port));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
            }
        }
        int code = process.waitFor();
        Path example = Path.of(System.getProperty("user.home"), ".cloudflared", "config-tienda-infinito.yml.example");
        if (Files.exists(example)) {
            log.accept("Plantilla de túnel Tienda Infinito: " + example);
        }
        try {
            CloudflaredAutostart.ensureInstalledAndRunning(env, log);
        } catch (Exception ex) {
            log.accept("Túnel autostart: " + ex.getMessage());
        }
        if (code != 0) {
            log.accept("El script terminó con código " + code + ". En Linux/macOS puede hacer falta ejecutarlo con sudo.");
        } else {
            log.accept("Preparación de esta máquina terminada.");
        }
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = PrepareHost.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IOException("No está empaquetado el recurso " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
