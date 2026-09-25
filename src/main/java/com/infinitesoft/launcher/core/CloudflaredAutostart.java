package com.infinitesoft.launcher.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Deja {@code cloudflared} como servicio de inicio de sesión (login), no como hijo del launcher.
 * Tras reiniciar el computador el túnel vuelve solo; el launcher solo lo instala / reanima.
 */
public final class CloudflaredAutostart {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private CloudflaredAutostart() {}

    public static String connectorId(LaunchEnvironment env) {
        if (env == LaunchEnvironment.TIENDA_INFINITO) {
            return "tienda-infinito";
        }
        return "pos-local";
    }

    public static String serviceLabel(LaunchEnvironment env) {
        return "com.infinitesoft.cloudflared." + connectorId(env);
    }

    public static void ensureInstalledAndRunning(LaunchEnvironment env, Consumer<String> log) throws IOException {
        Consumer<String> out = log != null ? log : msg -> {};
        Path binary = CloudflaredSupport.ensureBinary();
        if (isReady(env) && autostartPresent(env)) {
            out.accept("Túnel " + connectorId(env) + " ya en ejecución (autostart).");
            return;
        }
        List<String> args = programArguments(env, binary);
        Path logs = Path.of(System.getProperty("user.home"), ".infinitesoft", "logs");
        Files.createDirectories(logs);

        if (OsSupport.isMac()) {
            installMac(env, args, logs, out);
        } else if (OsSupport.isWindows()) {
            installWindows(env, args, logs, out);
        } else {
            installLinux(env, args, logs, out);
        }

        if (!waitUntilReady(env, Duration.ofSeconds(20))) {
            out.accept("cloudflared quedó instalado al inicio de sesión, pero /ready aún no responde. Revisa "
                    + logs.resolve("cloudflared-" + connectorId(env) + ".log"));
        } else {
            out.accept("Túnel " + connectorId(env) + " en ejecución (autostart de inicio de sesión).");
        }
    }

    public static void restart(LaunchEnvironment env, Consumer<String> log) throws IOException {
        Consumer<String> out = log != null ? log : msg -> {};
        if (!autostartPresent(env)) {
            ensureInstalledAndRunning(env, out);
            return;
        }
        String label = serviceLabel(env);
        int code;
        if (OsSupport.isMac()) {
            code = runLogged(List.of("launchctl", "kickstart", "-k", "gui/" + userId() + "/" + label), out);
        } else if (OsSupport.isWindows()) {
            String task = windowsTaskName(env);
            runLogged(List.of("schtasks", "/End", "/TN", task), out);
            code = runLogged(List.of("schtasks", "/Run", "/TN", task), out);
        } else {
            code = runLogged(List.of("systemctl", "--user", "restart", systemdUnit(env)), out);
        }
        if (code != 0) {
            out.accept("El gestor del SO no reanimó el túnel; reinstalando autostart…");
            ensureInstalledAndRunning(env, out);
            return;
        }
        waitUntilReady(env, Duration.ofSeconds(20));
    }

    static List<String> programArguments(LaunchEnvironment env, Path binary) throws IOException {
        Path cfHome = Path.of(System.getProperty("user.home"), ".cloudflared");
        List<String> args = new ArrayList<>();
        args.add(binary.toAbsolutePath().toString());
        args.add("tunnel");
        args.add("--no-autoupdate");
        args.add("--metrics");
        args.add("127.0.0.1:" + env.getTunnelMetricsPort());
        if (env == LaunchEnvironment.TIENDA_INFINITO) {
            Path token = cfHome.resolve("tienda-infinito.token");
            if (Files.isRegularFile(token)) {
                args.add("run");
                args.add("--token-file");
                args.add(token.toAbsolutePath().toString());
                return args;
            }
            Path yml = cfHome.resolve("config-tienda-infinito.yml");
            if (!Files.isRegularFile(yml)) {
                throw new IOException(
                        "Falta el token del túnel Tienda Infinito: " + token
                                + " (o " + yml + "). Sin él Cloudflare no puede entregar los correos "
                                + "a esta máquina (error 1033). Copia tienda-infinito.token a esa ruta.");
            }
            args.add("--config");
            args.add(yml.toAbsolutePath().toString());
            args.add("run");
            args.add(env.getTunnelName());
            return args;
        }
        Path yml = cfHome.resolve("config.yml");
        if (!Files.isRegularFile(yml)) {
            throw new IOException("Falta " + yml + " del túnel pos-local.");
        }
        args.add("--config");
        args.add(yml.toAbsolutePath().toString());
        args.add("run");
        return args;
    }

    static Path resolveCloudflaredBinary() {
        return CloudflaredSupport.resolveBinary();
    }

    private static boolean autostartPresent(LaunchEnvironment env) {
        if (OsSupport.isMac()) {
            Path plist = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents",
                    serviceLabel(env) + ".plist");
            return Files.isRegularFile(plist);
        }
        if (OsSupport.isWindows()) {
            return firstLine(List.of("schtasks", "/Query", "/TN", windowsTaskName(env))) != null;
        }
        Path unit = Path.of(System.getProperty("user.home"), ".config", "systemd", "user", systemdUnit(env));
        return Files.isRegularFile(unit);
    }

    private static void installMac(
            LaunchEnvironment env,
            List<String> args,
            Path logs,
            Consumer<String> log
    ) throws IOException {
        Path agents = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents");
        Files.createDirectories(agents);
        String label = serviceLabel(env);
        Path plist = agents.resolve(label + ".plist");
        Path outLog = logs.resolve("cloudflared-" + connectorId(env) + ".log");
        Path errLog = logs.resolve("cloudflared-" + connectorId(env) + ".err.log");
        Files.writeString(plist, macPlist(label, args, outLog, errLog), StandardCharsets.UTF_8);
        String uid = userId();
        String target = "gui/" + uid + "/" + label;
        runLogged(List.of("launchctl", "bootout", target), log);
        int boot = runLogged(List.of("launchctl", "bootstrap", "gui/" + uid, plist.toAbsolutePath().toString()), log);
        if (boot != 0) {
            runLogged(List.of("launchctl", "kickstart", "-k", target), log);
        }
        runLogged(List.of("launchctl", "enable", target), log);
        runLogged(List.of("launchctl", "kickstart", "-k", target), log);
        log.accept("LaunchAgent: " + plist);
    }

    private static void installLinux(
            LaunchEnvironment env,
            List<String> args,
            Path logs,
            Consumer<String> log
    ) throws IOException {
        Path unitDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user");
        Files.createDirectories(unitDir);
        String unit = systemdUnit(env);
        Path unitFile = unitDir.resolve(unit);
        Path outLog = logs.resolve("cloudflared-" + connectorId(env) + ".log");
        String exec = args.stream().map(CloudflaredAutostart::shellQuote).collect(Collectors.joining(" "));
        String body = "[Unit]\n"
                + "Description=Infinito Cloudflare tunnel " + connectorId(env) + "\n"
                + "After=network-online.target\n\n"
                + "[Service]\n"
                + "ExecStart=" + exec + "\n"
                + "Restart=always\n"
                + "RestartSec=5\n"
                + "StandardOutput=append:" + outLog.toAbsolutePath() + "\n"
                + "StandardError=append:" + outLog.toAbsolutePath() + "\n\n"
                + "[Install]\n"
                + "WantedBy=default.target\n";
        Files.writeString(unitFile, body, StandardCharsets.UTF_8);
        runLogged(List.of("systemctl", "--user", "daemon-reload"), log);
        int enabled = runLogged(List.of("systemctl", "--user", "enable", "--now", unit), log);
        writeXdgAutostart(env, args, log);
        if (enabled != 0) {
            log.accept("systemd --user no pudo arrancar; queda el acceso directo XDG al iniciar sesión.");
        }
        log.accept("systemd user: " + unitFile);
    }

    private static void installWindows(
            LaunchEnvironment env,
            List<String> args,
            Path logs,
            Consumer<String> log
    ) throws IOException {
        Path scripts = Path.of(System.getProperty("user.home"), ".infinitesoft", "scripts");
        Files.createDirectories(scripts);
        Path cmd = scripts.resolve("cloudflared-" + connectorId(env) + ".cmd");
        Files.writeString(
                cmd,
                windowsKeepAliveCmd(args, logs.resolve("cloudflared-" + connectorId(env) + ".log")),
                StandardCharsets.UTF_8
        );
        String task = windowsTaskName(env);
        String tr = "\"" + cmd.toAbsolutePath() + "\"";
        runLogged(List.of(
                "schtasks", "/Create", "/TN", task, "/SC", "ONLOGON", "/RL", "LIMITED", "/IT", "/F",
                "/TR", tr
        ), log);
        runLogged(List.of("schtasks", "/Run", "/TN", task), log);
        log.accept("Tarea programada: " + task + " → " + cmd);
    }

    private static String macPlist(String label, List<String> args, Path outLog, Path errLog) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        xml.append("<plist version=\"1.0\">\n<dict>\n");
        xml.append("  <key>Label</key>\n  <string>").append(xmlEscape(label)).append("</string>\n");
        xml.append("  <key>ProgramArguments</key>\n  <array>\n");
        for (String arg : args) {
            xml.append("    <string>").append(xmlEscape(arg)).append("</string>\n");
        }
        xml.append("  </array>\n");
        xml.append("  <key>RunAtLoad</key>\n  <true/>\n");
        xml.append("  <key>KeepAlive</key>\n  <true/>\n");
        xml.append("  <key>StandardOutPath</key>\n  <string>")
                .append(xmlEscape(outLog.toAbsolutePath().toString())).append("</string>\n");
        xml.append("  <key>StandardErrorPath</key>\n  <string>")
                .append(xmlEscape(errLog.toAbsolutePath().toString())).append("</string>\n");
        xml.append("  <key>EnvironmentVariables</key>\n  <dict>\n");
        xml.append("    <key>PATH</key>\n    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>\n");
        xml.append("  </dict>\n");
        xml.append("</dict>\n</plist>\n");
        return xml.toString();
    }

    private static boolean waitUntilReady(LaunchEnvironment env, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isReady(env) || OsSupport.isProcessMatching("cloudflared tunnel")) {
                if (isReady(env)) {
                    return true;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return isReady(env);
            }
        }
        return isReady(env) || OsSupport.isProcessMatching("cloudflared tunnel");
    }

    static boolean isReady(LaunchEnvironment env) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(env.getTunnelReadyUrl()))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String systemdUnit(LaunchEnvironment env) {
        return "infinito-cloudflared-" + connectorId(env) + ".service";
    }

    private static String windowsTaskName(LaunchEnvironment env) {
        return "InfinitoCloudflared-" + connectorId(env);
    }

    private static String userId() {
        String uid = firstLine(List.of("id", "-u"));
        return uid == null || uid.isBlank() ? "501" : uid.trim();
    }

    private static String firstLine(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(3, TimeUnit.SECONDS);
                return line;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int runLogged(List<String> cmd, Consumer<String> log) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder buf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        buf.append(line).append('\n');
                    }
                }
            }
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            int code = finished ? p.exitValue() : -1;
            if (buf.length() > 0 && code != 0) {
                log.accept(buf.toString().trim());
            }
            return code;
        } catch (Exception e) {
            log.accept(e.getMessage());
            return -1;
        }
    }

    private static String windowsKeepAliveCmd(List<String> args, Path logFile) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                line.append(' ');
            }
            line.append(windowsQuote(args.get(i)));
        }
        String log = windowsQuote(logFile.toAbsolutePath().toString());
        return "@echo off\r\n"
                + "title Infinito Cloudflare tunnel\r\n"
                + ":infinito_cf_loop\r\n"
                + line
                + " >> "
                + log
                + " 2>&1\r\n"
                + "timeout /t 5 /nobreak > nul\r\n"
                + "goto infinito_cf_loop\r\n";
    }

    private static void writeXdgAutostart(LaunchEnvironment env, List<String> args, Consumer<String> log)
            throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".config", "autostart");
        Files.createDirectories(dir);
        Path desktop = dir.resolve("infinito-cloudflared-" + connectorId(env) + ".desktop");
        String exec = args.stream().map(CloudflaredAutostart::shellQuote).collect(Collectors.joining(" "));
        String body = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=Infinito Cloudflare " + connectorId(env) + "\n"
                + "Exec=" + exec + "\n"
                + "X-GNOME-Autostart-enabled=true\n"
                + "Terminal=false\n";
        Files.writeString(desktop, body, StandardCharsets.UTF_8);
        log.accept("XDG autostart: " + desktop);
    }

    private static String xmlEscape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String shellQuote(String raw) {
        if (raw.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '\'' || ch == '"')) {
            return raw;
        }
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    private static String windowsQuote(String raw) {
        if (!raw.contains(" ") && !raw.contains("\"")) {
            return raw;
        }
        return "\"" + raw.replace("\"", "\\\"") + "\"";
    }
}
