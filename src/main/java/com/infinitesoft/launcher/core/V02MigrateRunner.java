package com.infinitesoft.launcher.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Copia {@code controlneg_rmx_db} → {@code controlneg_rmx_db_v02} y aplica
 * el manifiesto de scripts (una vez por máquina).
 */
public final class V02MigrateRunner {
    public static final String DEST_DB = "controlneg_rmx_db_v02";
    public static final String SRC_DB = "controlneg_rmx_db";
    public static final String PG_USER = "romax-admin";
    public static final String PG_PASSWORD = "f4ast3rv3rs10n*";
    public static final String PG_HOST = "localhost";
    public static final String PG_PORT = "5432";

    private static final Path MARKER = Path.of(
            System.getProperty("user.home"),
            ".infinitesoft",
            "tienda-infinito-v02-migrate.done"
    );
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private V02MigrateRunner() {}

    public static boolean alreadyApplied() {
        return Files.isRegularFile(MARKER);
    }

    public static Path markerPath() {
        return MARKER;
    }

    public static void run(Path projectsBase, Consumer<String> log) throws Exception {
        if (alreadyApplied()) {
            throw new IllegalStateException(
                    "Los scripts ya se aplicaron en esta máquina (" + MARKER + ").");
        }
        Path sqlDir = resolveSqlDir(projectsBase);
        Path manifest = sqlDir.resolve("migrate-tienda-infinito-v02.files");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("No está el manifiesto: " + manifest);
        }
        List<String> files = readManifest(manifest);
        if (files.isEmpty()) {
            throw new IllegalStateException("Manifiesto vacío: " + manifest);
        }

        String psql = requireCmd("psql");
        String pgDump = requireCmd("pg_dump");
        String pgRestore = requireCmd("pg_restore");
        log.accept("SQL dir: " + sqlDir);
        log.accept("Scripts: " + files.size());

        if (!databaseExists(psql, DEST_DB)) {
            log.accept("Creando " + DEST_DB + " desde dump de " + SRC_DB + " (sin --create)");
            Path dump = Path.of(System.getProperty("java.io.tmpdir"))
                    .resolve("controlneg_rmx_db-" + STAMP.format(LocalDateTime.now()) + ".dump");
            exec(pgDump, log,
                    "-Fc", "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", SRC_DB,
                    "-f", dump.toString());
            exec(psql, log,
                    "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", "postgres",
                    "-v", "ON_ERROR_STOP=1",
                    "-c", "CREATE DATABASE " + DEST_DB + " OWNER \"" + PG_USER + "\";");
            exec(pgRestore, log,
                    "--no-owner", "--no-acl",
                    "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", DEST_DB,
                    dump.toString());
            log.accept("Dump: " + dump);
        } else {
            log.accept(DEST_DB + " ya existe; solo scripts idempotentes.");
        }

        String jdbc = "postgresql://" + PG_USER + ":" + PG_PASSWORD
                + "@" + PG_HOST + ":" + PG_PORT + "/" + DEST_DB;
        for (String f : files) {
            Path sql = sqlDir.resolve(f);
            if (!Files.isRegularFile(sql)) {
                throw new IllegalStateException("Falta SQL: " + sql);
            }
            log.accept(">>> " + f);
            exec(psql, log, jdbc, "-v", "ON_ERROR_STOP=1", "-f", sql.toString());
        }

        Files.createDirectories(MARKER.getParent());
        Files.writeString(
                MARKER,
                "appliedAt=" + LocalDateTime.now() + "\ndest=" + DEST_DB + "\n",
                StandardCharsets.UTF_8
        );
        log.accept("Listo. Marcador: " + MARKER);
    }

    private static Path resolveSqlDir(Path projectsBase) {
        Path p = projectsBase
                .resolve("pos-relational-data-service")
                .resolve("src/main/resources/doc/contextos/database");
        if (Files.isDirectory(p)) {
            return p;
        }
        throw new IllegalStateException(
                "No se encontró la carpeta de SQL. Revisa la ruta de proyectos: " + p);
    }

    private static List<String> readManifest(Path manifest) throws IOException {
        List<String> out = new ArrayList<>();
        for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String line = raw.replaceFirst("#.*", "").trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean databaseExists(String psql, String name) throws Exception {
        String out = execCapture(psql,
                "-h", PG_HOST, "-p", PG_PORT, "-U", PG_USER, "-d", "postgres",
                "-Atqc", "SELECT 1 FROM pg_database WHERE datname='" + name + "'");
        return "1".equals(out.trim());
    }

    private static String requireCmd(String name) {
        String found = which(name);
        if (found == null) {
            throw new IllegalStateException(
                    "No está `" + name + "` en PATH. Instala el cliente PostgreSQL.");
        }
        return found;
    }

    private static String which(String name) {
        try {
            Process p = new ProcessBuilder(OsSupport.isWindows() ? "where" : "which", name)
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            if (p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0 && line != null && !line.isBlank()) {
                return line.trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static void exec(String cmd, Consumer<String> log, String... args) throws Exception {
        int code = run(cmd, args, log, null);
        if (code != 0) {
            throw new IllegalStateException(cmd + " salió con código " + code);
        }
    }

    private static String execCapture(String cmd, String... args) throws Exception {
        StringBuilder sb = new StringBuilder();
        int code = run(cmd, args, null, sb);
        if (code != 0) {
            throw new IllegalStateException(cmd + " salió con código " + code + ": " + sb);
        }
        return sb.toString();
    }

    private static int run(String cmd, String[] args, Consumer<String> log, StringBuilder capture)
            throws Exception {
        List<String> full = new ArrayList<>();
        full.add(cmd);
        for (String a : args) {
            full.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", PG_PASSWORD);
        Process p = pb.start();
        try (InputStream in = p.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (log != null) {
                    log.accept(line);
                }
                if (capture != null) {
                    capture.append(line).append('\n');
                }
            }
        }
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("Timeout: " + cmd);
        }
        return p.exitValue();
    }
}
