package com.infinitesoft.launcher;

import com.infinitesoft.launcher.core.AppConfig;
import com.infinitesoft.launcher.core.MonitoreoManager;
import com.infinitesoft.launcher.core.ServiceManager;
import com.infinitesoft.launcher.core.ServiceStatus;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HelloController {
    private final ServiceManager serviceManager = new ServiceManager();
    private final ScheduledExecutorService uiRefresher = Executors.newSingleThreadScheduledExecutor();

    @FXML private Button launchAppButton;
    @FXML private Label labelGeneralStatus;
    @FXML private Label labelBasePath;
    @FXML private Label statusDb;
    @FXML private Label statusSecurity;
    @FXML private Label statusSmtp;
    @FXML private Label statusStore;
    @FXML private Label statusFront;
    @FXML private Label statusInflux;

    @FXML
    public void initialize() {
        labelBasePath.setText(AppConfig.getInstance().getBasePath());
        uiRefresher.scheduleAtFixedRate(this::refreshUI, 0, 1500, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        serviceManager.shutdown();
        uiRefresher.shutdownNow();
    }

    private void refreshUI() {
        Map<String, ServiceStatus> map = serviceManager.snapshotStatuses();
        ServiceStatus influxStatus = serviceManager.getMonitoreo().getInfluxStatus();

        Platform.runLater(() -> {
            setStatusLabel(statusDb,       map.getOrDefault(ServiceManager.NAME_DB,       ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSecurity, map.getOrDefault(ServiceManager.NAME_SECURITY, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSmtp,     map.getOrDefault(ServiceManager.NAME_SMTP,     ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusStore,    map.getOrDefault(ServiceManager.NAME_STORE,    ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusFront,    map.getOrDefault(ServiceManager.NAME_FRONT,    ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusInflux,   influxStatus);

            computeGeneralStatus(map);

            // Monitoreo NO es requerido para habilitar "Ejecutar aplicacion"
            // (el POS funciona aunque InfluxDB este caido).
            boolean allRequiredRunning =
                    map.get(ServiceManager.NAME_FRONT)    == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_STORE)    == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_SECURITY) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_DB)       == ServiceStatus.RUNNING;
            launchAppButton.setDisable(!allRequiredRunning);
        });
    }

    private void setStatusLabel(Label label, ServiceStatus status) {
        String text;
        String style;
        switch (status) {
            case RUNNING:
                text  = "Ejecutándose";
                style = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
                break;
            case STARTING:
                text  = "Iniciando";
                style = "-fx-background-color: #f9a825; -fx-text-fill: white;";
                break;
            case FAILED:
                text  = "Falló";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
            default:
                text  = "Detenido";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
        }
        label.setText(text);
        label.setStyle(style);
    }

    private void computeGeneralStatus(Map<String, ServiceStatus> map) {
        boolean anyFailed   = map.values().stream().anyMatch(s -> s == ServiceStatus.FAILED);
        boolean anyStarting = map.values().stream().anyMatch(s -> s == ServiceStatus.STARTING);
        boolean allRunning  = map.values().stream().allMatch(s -> s == ServiceStatus.RUNNING);
        boolean allStopped  = map.values().stream().allMatch(s -> s == ServiceStatus.NOT_RUNNING);

        String statusText;
        String statusStyle;

        if (allRunning) {
            statusText  = "Ejecutándose";
            statusStyle = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
        } else if (allStopped) {
            statusText  = "Detenido";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        } else if (anyStarting) {
            statusText  = "Iniciando";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        } else {
            statusText  = "Ejecución parcial";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        }

        if (anyFailed) {
            statusText  = "Ejecución parcial con fallos";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        }

        labelGeneralStatus.setText(statusText);
        labelGeneralStatus.setStyle(statusStyle);
    }

    @FXML
    protected void onLaunchApp() {
        final String url = "http://localhost:4200/login";
        try {
            new ProcessBuilder("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "--app=" + url).start();
        } catch (IOException e1) {
            try {
                new ProcessBuilder("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe", "--app=" + url).start();
            } catch (IOException e2) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try { Desktop.getDesktop().browse(new URI(url)); }
                    catch (Exception e3) { e3.printStackTrace(); }
                }
            }
        }
    }

    @FXML
    protected void onOpenBrowser() {
        final String url = "http://localhost:4200/login";
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try { Desktop.getDesktop().browse(new URI(url)); }
            catch (Exception e) { e.printStackTrace(); }
        }
    }

    @FXML
    protected void onSelectProjectsFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Seleccionar carpeta de proyectos");
        chooser.setInitialDirectory(new File(AppConfig.getInstance().getBasePath()));
        Stage stage = (Stage) labelBasePath.getScene().getWindow();
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            AppConfig.getInstance().setBasePath(selected.getAbsolutePath().replace("\\", "/"));
            labelBasePath.setText(AppConfig.getInstance().getBasePath());
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Ruta actualizada");
            alert.setHeaderText(null);
            alert.setContentText("Ruta guardada. Reinicia la aplicación para aplicar los cambios.");
            alert.showAndWait();
        }
    }

    @FXML
    protected void onOpenLogs() {
        try {
            Desktop.getDesktop().open(new File("C:/dev/repos/intinito-launcher/logs"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    protected void onOpenDbManager() {
        try {
            new ProcessBuilder("C:\\Program Files\\DBeaver\\dbeaver.exe").start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===================== Botones globales =================================

    @FXML protected void onStartAll()   { serviceManager.startAllSequential(Duration.ofSeconds(60)); }
    @FXML protected void onStopAll()    { serviceManager.stopAll(); }
    @FXML protected void onRestartAll() {
        serviceManager.stopAll();
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            serviceManager.startAllSequential(Duration.ofSeconds(60));
        }).start();
    }

    // ===================== Security =========================================
    @FXML protected void onStartSecurity()  { serviceManager.start(ServiceManager.NAME_SECURITY); }
    @FXML protected void onStopSecurity()   { serviceManager.stop(ServiceManager.NAME_SECURITY); }
    @FXML protected void onRestartSecurity(){ serviceManager.restart(ServiceManager.NAME_SECURITY); }
    @FXML protected void onUpdateSecurity() { openUpdateWindow("Seguridad", ServiceManager.NAME_SECURITY); }

    // ===================== SMTP =============================================
    @FXML protected void onStartSmtp()   { serviceManager.start(ServiceManager.NAME_SMTP); }
    @FXML protected void onStopSmtp()    { serviceManager.stop(ServiceManager.NAME_SMTP); }
    @FXML protected void onRestartSmtp() { serviceManager.restart(ServiceManager.NAME_SMTP); }
    @FXML protected void onUpdateSmtp()  { openUpdateWindow("Correos (SMTP)", ServiceManager.NAME_SMTP); }

    // ===================== Store ============================================
    @FXML protected void onStartStore()   { serviceManager.start(ServiceManager.NAME_STORE); }
    @FXML protected void onStopStore()    { serviceManager.stop(ServiceManager.NAME_STORE); }
    @FXML protected void onRestartStore() { serviceManager.restart(ServiceManager.NAME_STORE); }
    @FXML protected void onUpdateStore()  { openUpdateWindow("Lógica Tienda", ServiceManager.NAME_STORE); }

    // ===================== Front ============================================
    @FXML protected void onStartFront()   { serviceManager.start(ServiceManager.NAME_FRONT); }
    @FXML protected void onStopFront()    { serviceManager.stop(ServiceManager.NAME_FRONT); }
    @FXML protected void onRestartFront() { serviceManager.restart(ServiceManager.NAME_FRONT); }
    @FXML protected void onUpdateFront()  { openUpdateWindow("Frontend", ServiceManager.NAME_FRONT); }

    // ===================== InfluxDB (logs-infinito) =========================
    // Cada accion va al hilo de fondo via *Async() para no bloquear la UI.
    // Los scripts son idempotentes (start-influx no hace nada si ya esta UP).

    @FXML protected void onStartInflux() {
        serviceManager.getMonitoreo().startInfluxAsync(this::reportarResultadoInflux);
    }
    @FXML protected void onStopInflux() {
        serviceManager.getMonitoreo().stopInfluxAsync(this::reportarResultadoInflux);
    }
    @FXML protected void onRestartInflux() {
        serviceManager.getMonitoreo().restartInfluxAsync(this::reportarResultadoInflux);
    }

    @FXML protected void onDiagnoseMonitoreo() {
        TextArea logArea = new TextArea("Ejecutando diagnóstico de InfluxDB...\n");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px;");
        Button closeBtn = new Button("Cerrar");
        closeBtn.setDisable(true);
        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new javafx.geometry.Insets(10));
        logArea.setPrefHeight(420);
        Stage stage = new Stage();
        stage.setTitle("Diagnóstico - InfluxDB (logs-infinito)");
        stage.setScene(new Scene(root, 760, 500));
        stage.show();
        closeBtn.setOnAction(e -> stage.close());

        serviceManager.getMonitoreo().runDiagnosticoAsync(result ->
            Platform.runLater(() -> {
                logArea.setText(result.fullOutput);
                logArea.appendText("\n--- exit code: " + result.exitCode + " ---");
                logArea.setScrollTop(Double.MAX_VALUE);
                closeBtn.setDisable(false);
            })
        );
    }

    /**
     * Callback para acciones de InfluxDB.
     * Si el script falla (exit != 0) muestra una ventana con el output completo
     * para que el usuario pueda diagnosticar el problema.
     */
    private void reportarResultadoInflux(MonitoreoManager.ScriptResult r) {
        System.out.printf("[influx] exit=%d  json=%s%n", r.exitCode, r.jsonSummary);
        if (!r.isOk()) {
            Platform.runLater(() -> mostrarErrorInflux(r));
        }
    }

    private void mostrarErrorInflux(MonitoreoManager.ScriptResult r) {
        TextArea logArea = new TextArea(r.fullOutput);
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px;");
        logArea.appendText("\n--- exit code: " + r.exitCode + " ---");

        Button closeBtn = new Button("Cerrar");
        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new javafx.geometry.Insets(10));
        logArea.setPrefHeight(400);

        Stage stage = new Stage();
        stage.setTitle("⚠ InfluxDB - Error al ejecutar script");
        stage.setScene(new Scene(root, 760, 480));
        stage.show();
        closeBtn.setOnAction(e -> stage.close());
    }

    // ===================== Scripts Administrador ============================

    @FXML
    protected void onOpenAdminScripts() {
        String base = AppConfig.getInstance().getBasePath().replace("/", "\\");

        // ----- Script #1 (NUEVO): instalacion de InfluxDB en laptop nuevo ----
        // All-in-one e idempotente. Hace en orden:
        //   a) Verifica que influxdb3.exe exista (si no, instruye al usuario).
        //   b) Unblock-File de todos los .exe/.ps1 de logs-infinito (Zone.Identifier).
        //   c) Crea object-store y carpeta logs.
        //   d) Mata cualquier influxdb3 viejo y arranca uno nuevo en background.
        //   e) Espera /health hasta 45s.
        //   f) Crea la database 'infinito_logs' con retencion 90d (409 = OK).
        //   g) "Seed" de schema: escribe 1 linea dummy de cada measurement con
        //      TODOS los tags+fields. Esto evita el gotcha de schema dinamico
        //      de InfluxDB 3 (los queries del componente Angular fallarian si
        //      una columna nunca se escribio). Ver AI-ONBOARDING.md gotcha A.
        //   h) Reporta resultado.
        String scriptInstalarInflux =
            "$ErrorActionPreference = 'Stop'; " +
            "$base = \"" + base + "\"; " +
            "$influxHome = \"$base\\logs-infinito\\influxdb3-core-3.9.1-windows_amd64\"; " +
            "$influxBin  = \"$influxHome\\influxdb3.exe\"; " +
            "$objectDir  = \"$influxHome\\object-store\"; " +
            "$logsDir    = \"$base\\logs-infinito\\logs\"; " +
            "$db         = 'infinito_logs'; " +
            "$retention  = '90d'; " +
            "$bind       = '127.0.0.1:8181'; " +
            "if (-not (Test-Path $influxBin)) { " +
            "  Write-Host \"FALTA $influxBin\" -ForegroundColor Red; " +
            "  Write-Host 'Descomprime la release oficial influxdb3-core-3.9.1-windows_amd64 en logs-infinito\\ y reintenta.' -ForegroundColor Yellow; " +
            "  return " +
            "} " +
            "Write-Host '==> 1/7 Desbloqueando archivos (Zone.Identifier)...' -ForegroundColor Cyan; " +
            "Get-ChildItem \"$base\\logs-infinito\" -Recurse -Include '*.exe','*.ps1' -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue; " +
            "Write-Host '==> 2/7 Creando carpetas de datos...' -ForegroundColor Cyan; " +
            "New-Item -ItemType Directory -Force -Path $objectDir,$logsDir | Out-Null; " +
            "Write-Host '==> 3/7 Matando instancias previas de influxdb3...' -ForegroundColor Cyan; " +
            "Get-Process -Name 'influxdb3' -ErrorAction SilentlyContinue | Stop-Process -Force; " +
            "Start-Sleep -Milliseconds 600; " +
            "Write-Host '==> 4/7 Arrancando InfluxDB en background...' -ForegroundColor Cyan; " +
            "Start-Process -FilePath $influxBin -WorkingDirectory $influxHome " +
            "-ArgumentList @('serve','--node-id','node1','--object-store','file','--data-dir',$objectDir,'--http-bind',$bind,'--without-auth') " +
            "-WindowStyle Hidden -RedirectStandardOutput \"$logsDir\\influxdb-install.log\" -RedirectStandardError \"$logsDir\\influxdb-install.err\"; " +
            "Write-Host '==> 5/7 Esperando /health (hasta 45s)...' -ForegroundColor Cyan; " +
            "$ready = $false; for ($i=0; $i -lt 60; $i++) { try { $r = Invoke-WebRequest -Uri \"http://$bind/health\" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop; if ($r.StatusCode -eq 200) { $ready=$true; break } } catch {}; Start-Sleep -Milliseconds 750 }; " +
            "if (-not $ready) { Write-Host '!! InfluxDB no respondio en 45s. Revisa $logsDir\\influxdb-install.err' -ForegroundColor Red; return }; " +
            "Write-Host \"    OK InfluxDB UP en http://$bind\" -ForegroundColor Green; " +
            "Write-Host '==> 6/7 Creando database (idempotente)...' -ForegroundColor Cyan; " +
            "try { " +
            "  $body = @{ db = $db; retention_period = $retention } | ConvertTo-Json -Compress; " +
            "  Invoke-WebRequest -Uri \"http://$bind/api/v3/configure/database\" -Method POST -Body $body -ContentType 'application/json' -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop | Out-Null; " +
            "  Write-Host \"    OK database '$db' creada con retencion $retention\" -ForegroundColor Green " +
            "} catch { " +
            "  if ($_.Exception.Response.StatusCode.value__ -eq 409) { Write-Host \"    OK database '$db' ya existia\" -ForegroundColor Green } " +
            "  else { Write-Host \"!! creando database: $($_.Exception.Message)\" -ForegroundColor Red; return } " +
            "}; " +
            "Write-Host '==> 7/7 Seed del schema (registra todos los tags/fields)...' -ForegroundColor Cyan; " +
            "$ts = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - 60000) * 1000000; " +
            "$lp = \"backend_log,app=pos-negocio,level=INFO,logger=Installer,thread=ps message=`\"schema seed`\",exception=`\"`\" $ts`nfrontend_error,app=pos-frontend,url=/seed,error_type=Seed error=`\"schema seed`\",actividad=`\"installer`\",reporte_id=`\"seed-init`\" $ts\"; " +
            "Invoke-WebRequest -Uri \"http://$bind/api/v3/write_lp?db=$db&precision=ns\" -Method POST -Body $lp -ContentType 'text/plain' -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop | Out-Null; " +
            "Write-Host '    OK schema backend_log + frontend_error inicializado' -ForegroundColor Green; " +
            "Write-Host ''; " +
            "Write-Host '==================================================' -ForegroundColor Green; " +
            "Write-Host '  INSTALACION COMPLETA' -ForegroundColor Green; " +
            "Write-Host \"  Database  : $db (retencion $retention)\"; " +
            "Write-Host \"  HTTP      : http://$bind\"; " +
            "Write-Host \"  Logs      : $logsDir\"; " +
            "Write-Host '==================================================' -ForegroundColor Green; " +
            "Write-Host 'InfluxDB queda corriendo en background. Para detener:'; " +
            "Write-Host '  Get-Process influxdb3 | Stop-Process -Force'";

        String scriptCertificado =
            "$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject \"CN=Infinito POS, O=Mi Tienda, C=CO\" " +
            "-KeyUsage DigitalSignature -FriendlyName \"Infinito POS LocalSign\" " +
            "-CertStoreLocation \"Cert:\\CurrentUser\\My\" -NotAfter (Get-Date).AddYears(5); " +
            "$s1 = New-Object System.Security.Cryptography.X509Certificates.X509Store(\"Root\",\"LocalMachine\"); " +
            "$s1.Open(\"ReadWrite\"); $s1.Add($cert); $s1.Close(); " +
            "$s2 = New-Object System.Security.Cryptography.X509Certificates.X509Store(\"TrustedPublisher\",\"LocalMachine\"); " +
            "$s2.Open(\"ReadWrite\"); $s2.Add($cert); $s2.Close(); " +
            "$pw = ConvertTo-SecureString -String \"InfinitoPOS2024!\" -Force -AsPlainText; " +
            "Export-PfxCertificate -Cert $cert -FilePath \"C:\\infinito-pos-sign.pfx\" -Password $pw; " +
            "Write-Host \"Listo! Thumbprint: $($cert.Thumbprint)\"";

        String scriptDesbloquearScript =
            "Unblock-File -Path \"" + base + "\\logs-infinito\\scripts\\start-influx.ps1\"; Write-Host \"Script desbloqueado OK\"";

        String scriptDesbloquearInflux =
            "Get-ChildItem \"" + base + "\\logs-infinito\" -Recurse -Filter \"influxdb3.exe\" " +
            "-ErrorAction SilentlyContinue | ForEach-Object { Unblock-File -Path $_.FullName; Write-Host \"Desbloqueado: $($_.FullName)\" }; " +
            "Write-Host \"influxdb3 desbloqueado OK\"";

        String scriptVerificarZona =
            "Get-ChildItem \"" + base + "\\logs-infinito\" -Recurse -Include \"*.exe\",\"*.ps1\" " +
            "-ErrorAction SilentlyContinue | ForEach-Object { " +
            "$zone = Get-Item $_.FullName -Stream \"Zone.Identifier\" -ErrorAction SilentlyContinue; " +
            "if ($zone) { Write-Host \"BLOQUEADO: $($_.FullName)\" } }";

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(12);
        root.setPadding(new javafx.geometry.Insets(15));
        root.setStyle("-fx-background-color: #fafafa;");

        Label titulo = new Label("Scripts de Administración - PowerShell (ejecutar como Administrador)");
        titulo.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Label instruccion = new Label("Copia cada script y pégalo en PowerShell abierto como Administrador.");
        instruccion.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        root.getChildren().addAll(titulo, instruccion, new javafx.scene.control.Separator());

        // Ayuda inline para el script de instalacion: troubleshooting + checks de validacion.
        // Va dentro de la propia ventana porque este script es el mas critico para un laptop nuevo.
        String ayudaInstalar =
            "SI FALLA:\n" +
            "  - 'FALTA <path>\\influxdb3.exe'  ->  la release no esta descomprimida en logs-infinito\\.\n" +
            "                                       Descomprime influxdb3-core-3.9.1-windows_amd64.zip ahi y reintenta.\n" +
            "  - 'InfluxDB no respondio en 45s' ->  revisa el log de error:\n" +
            "                                       " + base + "\\logs-infinito\\logs\\influxdb-install.err\n" +
            "  - 'creando database: <error>'    ->  cualquier error distinto a 409 (ya existe).\n" +
            "                                       Verifica que el puerto 8181 no este ocupado por otra app.\n" +
            "\n" +
            "PARA PROBAR (despues de ejecutar el script, en otra consola):\n" +
            "  curl http://127.0.0.1:8181/health\n" +
            "    -> debe responder: OK\n" +
            "\n" +
            "  curl -G \"http://127.0.0.1:8181/api/v3/query_sql\" --data-urlencode \"db=infinito_logs\" ^\n" +
            "       --data-urlencode \"q=SELECT COUNT(*) FROM backend_log\" --data-urlencode \"format=jsonl\"\n" +
            "    -> debe devolver al menos 1 (la linea seed).\n" +
            "\n" +
            "  curl -G \"http://127.0.0.1:8181/api/v3/query_sql\" --data-urlencode \"db=infinito_logs\" ^\n" +
            "       --data-urlencode \"q=SELECT COUNT(*) FROM frontend_error\" --data-urlencode \"format=jsonl\"\n" +
            "    -> debe devolver al menos 1 (la linea seed de frontend_error).\n" +
            "\n" +
            "PARA DETENER InfluxDB:  Get-Process influxdb3 | Stop-Process -Force\n" +
            "PARA REARRANCAR:        Pulsa 'Iniciar' en la fila 'InfluxDB (Monitoreo)' del launcher,\n" +
            "                        o ejecuta logs-infinito\\scripts\\start-influx.ps1.";

        root.getChildren().add(crearSeccionScript(
            "1. Instalar InfluxDB en este laptop (all-in-one: desbloquea, arranca, crea database 'infinito_logs' con retencion 90d, y registra el schema de backend_log + frontend_error)",
            scriptInstalarInflux,
            ayudaInstalar));

        root.getChildren().add(crearSeccionScript(
            "2. Crear certificado autofirmado y confiar localmente (resolver apps bloqueadas por firma desconocida)",
            scriptCertificado));

        root.getChildren().add(crearSeccionScript(
            "3. Desbloquear script de arranque InfluxDB (Zone.Identifier de Windows)",
            scriptDesbloquearScript));

        root.getChildren().add(crearSeccionScript(
            "4. Desbloquear ejecutable influxdb3.exe",
            scriptDesbloquearInflux));

        root.getChildren().add(crearSeccionScript(
            "5. Verificar archivos aún bloqueados por Windows en logs-infinito",
            scriptVerificarZona));

        Button cerrarBtn = new Button("Cerrar");
        cerrarBtn.setStyle("-fx-background-color: #5c6bc0; -fx-text-fill: white;");
        javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(cerrarBtn);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        root.getChildren().add(footer);

        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(root);
        scroll.setFitToWidth(true);

        Stage stage = new Stage();
        stage.setTitle("Scripts Administrador - Infinito POS");
        stage.setScene(new Scene(scroll, 820, 560));
        stage.show();
        cerrarBtn.setOnAction(e -> stage.close());
    }

    private javafx.scene.layout.VBox crearSeccionScript(String descripcion, String script) {
        return crearSeccionScript(descripcion, script, null);
    }

    /**
     * Versión con texto de ayuda opcional que se muestra debajo del script.
     * Útil para incluir instrucciones de troubleshooting y validación al lado
     * del script copiable, sin contaminar el contenido que el usuario va a pegar
     * en PowerShell.
     */
    private javafx.scene.layout.VBox crearSeccionScript(String descripcion, String script, String ayuda) {
        Label label = new Label(descripcion);
        label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #444; -fx-wrap-text: true;");
        label.setMaxWidth(780);

        TextArea area = new TextArea(script);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefHeight(80);
        area.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 10px; -fx-background-color: #1e1e1e; -fx-text-fill: #d4d4d4;");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4, label, area);

        if (ayuda != null && !ayuda.isEmpty()) {
            TextArea ayudaArea = new TextArea(ayuda);
            ayudaArea.setEditable(false);
            ayudaArea.setWrapText(true);
            ayudaArea.setPrefHeight(140);
            ayudaArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 10px; "
                    + "-fx-background-color: #fff8e1; -fx-text-fill: #555; "
                    + "-fx-control-inner-background: #fff8e1;");
            box.getChildren().add(ayudaArea);
        }
        return box;
    }

    // ===================== Helpers ==========================================

    private void openUpdateWindow(String serviceName, String serviceKey) {
        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px;");

        Button closeBtn = new Button("Cerrar");
        closeBtn.setDisable(true);

        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new javafx.geometry.Insets(10));
        logArea.setPrefHeight(400);

        Stage stage = new Stage();
        stage.setTitle("Actualizar: " + serviceName);
        stage.setScene(new Scene(root, 700, 460));
        stage.show();

        closeBtn.setOnAction(e -> stage.close());

        logArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.contains("=== Proceso completado ===") || newVal.contains("ERROR:")) {
                Platform.runLater(() -> closeBtn.setDisable(false));
            }
        });

        serviceManager.updateProject(serviceKey, line ->
            Platform.runLater(() -> {
                logArea.appendText(line + "\n");
                logArea.setScrollTop(Double.MAX_VALUE);
            })
        );
    }
}
