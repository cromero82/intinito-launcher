package com.infinitesoft.launcher;

import com.infinitesoft.launcher.core.AppConfig;
import com.infinitesoft.launcher.core.HostConfig;
import com.infinitesoft.launcher.core.LaunchEnvironment;
import com.infinitesoft.launcher.core.PrepareHost;
import com.infinitesoft.launcher.core.ServiceManager;
import com.infinitesoft.launcher.core.ServiceStatus;
import com.infinitesoft.launcher.core.V02MigrateRunner;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HelloController {
    private final ServiceManager serviceManager = new ServiceManager();
    private final ScheduledExecutorService uiRefresher = Executors.newSingleThreadScheduledExecutor();

    @FXML private ComboBox<LaunchEnvironment> comboEnvironment;
    @FXML private Button btnEjecutarScripts;
    @FXML private Label labelMigrateStatus;
    @FXML private Button launchAppButton;
    @FXML private Label labelGeneralStatus;
    @FXML private Label labelTunnelUrl;
    @FXML private Label labelLanHost;
    @FXML private Label labelBasePath;
    @FXML private Label statusDb;
    @FXML private Label statusSecurity;
    @FXML private Label statusSmtp;
    @FXML private Label statusStore;
    @FXML private Label statusPuente;
    @FXML private Label statusFront;
    @FXML private Label statusCaddy;
    @FXML private Label statusTunnel;
    private boolean ignoreEnvironmentCallback;

    @FXML
    public void initialize() {
        ignoreEnvironmentCallback = true;
        comboEnvironment.getItems().setAll(LaunchEnvironment.values());
        comboEnvironment.setValue(serviceManager.getEnvironment());
        ignoreEnvironmentCallback = false;
        labelBasePath.setText(AppConfig.getInstance().getBasePath());
        refreshLanHostLabel();
        try {
            HostConfig.getInstance().syncProjectMirror(AppConfig.getInstance().getBasePath());
        } catch (IOException ignored) {
            // El mirror es informativo; la fuente de verdad es ~/.infinitesoft/host-ip.txt
        }
        refreshMigrateUi();
        uiRefresher.scheduleAtFixedRate(this::refreshUI, 0, 1500, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        serviceManager.shutdown();
        uiRefresher.shutdownNow();
    }

    private void refreshLanHostLabel() {
        labelLanHost.setText("IP LAN: " + HostConfig.getInstance().getLanHost());
    }

    private void refreshUI() {
        Map<String, ServiceStatus> map = serviceManager.snapshotStatuses();
        Platform.runLater(() -> {
            setStatusLabel(statusDb, map.getOrDefault(ServiceManager.NAME_DB, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSecurity, map.getOrDefault(ServiceManager.NAME_SECURITY, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusSmtp, map.getOrDefault(ServiceManager.NAME_SMTP, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusStore, map.getOrDefault(ServiceManager.NAME_STORE, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusPuente, map.getOrDefault(ServiceManager.NAME_PUENTE, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusFront, map.getOrDefault(ServiceManager.NAME_FRONT, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusCaddy, map.getOrDefault(ServiceManager.NAME_CADDY, ServiceStatus.NOT_RUNNING));
            setStatusLabel(statusTunnel, map.getOrDefault(ServiceManager.NAME_TUNNEL, ServiceStatus.NOT_RUNNING));

            computeGeneralStatus(map);

            LaunchEnvironment env = serviceManager.getEnvironment();
            if (env.startsTunnel() && env.getPublicHostname() != null && !env.getPublicHostname().isBlank()) {
                labelTunnelUrl.setText("Público: https://" + env.getPublicHostname());
            } else {
                labelTunnelUrl.setText("Sin túnel / sin notificaciones (copia local)");
            }
            if (!env.startsSmtp()) {
                statusSmtp.setText("No aplica");
            }
            if (!env.startsPuente()) {
                statusPuente.setText("No aplica");
            }
            if (!env.startsCaddy()) {
                statusCaddy.setText("No aplica");
            }
            if (!env.startsTunnel()) {
                statusTunnel.setText("No aplica");
            }
            refreshMigrateUi();

            boolean allRequiredRunning = map.get(ServiceManager.NAME_FRONT) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_STORE) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_SECURITY) == ServiceStatus.RUNNING &&
                    map.get(ServiceManager.NAME_DB) == ServiceStatus.RUNNING;
            launchAppButton.setDisable(!allRequiredRunning);
        });
    }

    private void setStatusLabel(Label label, ServiceStatus status) {
        String text;
        String style;
        switch (status) {
            case RUNNING:
                text = "Ejecutándose";
                style = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
                break;
            case STARTING:
                text = "Iniciando";
                style = "-fx-background-color: #f9a825; -fx-text-fill: white;";
                break;
            case FAILED:
                text = "Falló";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
            default:
                text = "Detenido";
                style = "-fx-background-color: #c62828; -fx-text-fill: white;";
                break;
        }
        label.setText(text);
        label.setStyle(style);
    }

    private void computeGeneralStatus(Map<String, ServiceStatus> map) {
        java.util.Collection<ServiceStatus> managed = map.entrySet().stream()
                .filter(e -> !ServiceManager.NAME_DB.equals(e.getKey())
                        && !ServiceManager.NAME_TUNNEL.equals(e.getKey()))
                .map(java.util.Map.Entry::getValue)
                .collect(java.util.stream.Collectors.toList());

        boolean anyFailed = managed.stream().anyMatch(s -> s == ServiceStatus.FAILED);
        boolean anyStarting = managed.stream().anyMatch(s -> s == ServiceStatus.STARTING);
        boolean allRunning = !managed.isEmpty() && managed.stream().allMatch(s -> s == ServiceStatus.RUNNING);
        boolean allStopped = managed.stream().allMatch(s -> s == ServiceStatus.NOT_RUNNING);

        String statusText;
        String statusStyle;

        if (allRunning) {
            statusText = "Ejecutándose";
            statusStyle = "-fx-background-color: #2e7d32; -fx-text-fill: white;";
        } else if (allStopped) {
            statusText = "Detenido";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        } else if (anyStarting) {
            statusText = "Iniciando";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        } else {
            statusText = "Ejecución parcial";
            statusStyle = "-fx-background-color: #f9a825; -fx-text-fill: white;";
        }

        if (anyFailed) {
            statusText = "Ejecución parcial con fallos";
            statusStyle = "-fx-background-color: #c62828; -fx-text-fill: white;";
        }

        labelGeneralStatus.setText(statusText);
        labelGeneralStatus.setStyle(statusStyle);
    }

    @FXML
    protected void onLaunchApp() {
        final String url = "http://localhost:"
                + AppConfig.getInstance().getEnvironment().getFrontPort()
                + "/login";
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("mac")) {
            try {
                new ProcessBuilder("open", "-a", "Google Chrome", "--args", "--app=" + url).start();
            } catch (IOException e1) {
                try {
                    new ProcessBuilder("open", "-a", "Chromium", "--args", "--app=" + url).start();
                } catch (IOException e2) {
                    try {
                        new ProcessBuilder("open", "-a", "Firefox", "--args", "--new-window", url).start();
                    } catch (IOException e3) {
                        try {
                            new ProcessBuilder("open", url).start();
                        } catch (IOException e4) {
                            e4.printStackTrace();
                        }
                    }
                }
            }
        } else if (osName.contains("linux")) {
            try {
                new ProcessBuilder("google-chrome", "--app=" + url).start();
            } catch (IOException e1) {
                try {
                    new ProcessBuilder("chromium-browser", "--app=" + url).start();
                } catch (IOException e2) {
                    try {
                        new ProcessBuilder("firefox", "--new-window", url).start();
                    } catch (IOException e3) {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            try {
                                Desktop.getDesktop().browse(new URI(url));
                            } catch (Exception e4) {
                                e4.printStackTrace();
                            }
                        }
                    }
                }
            }
        } else {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(new URI(url));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @FXML
    protected void onOpenBrowser() {
        serviceManager.ensurePosServicesRunning();
        openInBrowser(HostConfig.getInstance().getTicketsUrl());
    }

    @FXML
    protected void onOpenMicotizacionTunnel() {
        serviceManager.ensurePosServicesRunning();

        Executors.newSingleThreadExecutor().execute(() -> {
            Optional<String> tunnelUrl = serviceManager.waitForTunnelUrl(Duration.ofSeconds(120));
            Platform.runLater(() -> {
                if (tunnelUrl.isPresent()) {
                    openInBrowser(tunnelUrl.get());
                    return;
                }
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Túnel no disponible");
                alert.setHeaderText(null);
                alert.setContentText(
                        "No se pudo obtener la URL HTTPS de micotización.\n\n"
                                + "Verifica que caddy y cloudflared estén instalados "
                                + "(brew install caddy cloudflared), que el frontend esté en ejecución "
                                + "y revisa los logs en ~/.infinitesoft/logs.");
                alert.showAndWait();
            });
        });
    }

    @FXML
    protected void onConfigureLanHost() {
        HostConfig hostConfig = HostConfig.getInstance();
        TextField ipField = new TextField(hostConfig.getLanHost());
        ipField.setPromptText(HostConfig.DEFAULT_LAN_HOST);

        Label help = new Label(
                "IP de esta máquina en la red local.\n"
                        + "Se guarda en ~/.infinitesoft/host-ip.txt\n"
                        + "Si difiere de " + HostConfig.DEFAULT_LAN_HOST + ", se reinicia el túnel de micotización."
        );
        help.setWrapText(true);
        help.setStyle("-fx-text-fill: #555; -fx-font-size: 11px;");

        Label fileLabel = new Label("Archivo: " + hostConfig.getRuntimeHostFile());
        fileLabel.setStyle("-fx-text-fill: #777; -fx-font-size: 10px;");

        Button saveBtn = new Button("Guardar y aplicar");
        Button cancelBtn = new Button("Cancelar");

        VBox root = new VBox(10,
                new Label("IP LAN del servidor POS:"),
                ipField,
                help,
                fileLabel,
                new javafx.scene.layout.HBox(10, saveBtn, cancelBtn));
        root.setPadding(new Insets(16));

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(labelLanHost.getScene().getWindow());
        stage.setTitle("Configurar IP LAN");
        stage.setScene(new Scene(root, 460, 220));

        cancelBtn.setOnAction(e -> stage.close());
        saveBtn.setOnAction(e -> {
            try {
                String newIp = ipField.getText();
                boolean changed = hostConfig.setLanHost(newIp);
                hostConfig.syncProjectMirror(AppConfig.getInstance().getBasePath());
                refreshLanHostLabel();

                if (changed && hostConfig.requiresTunnelRedeploy(newIp)) {
                    serviceManager.redeployFrontTunnelStack();
                    showInfo(
                            "IP actualizada",
                            "Se guardó " + hostConfig.getLanHost() + " y se reinició el frontend "
                                    + "(Caddy + Cloudflare) para regenerar el túnel con la nueva red.\n\n"
                                    + "POS LAN: " + hostConfig.getTicketsUrl()
                    );
                } else if (changed) {
                    showInfo(
                            "IP actualizada",
                            "Se guardó " + hostConfig.getLanHost() + ".\n\nPOS LAN: " + hostConfig.getTicketsUrl()
                    );
                }
                stage.close();
            } catch (IllegalArgumentException ex) {
                showWarning("IP inválida", ex.getMessage());
            } catch (IOException ex) {
                showWarning("Error al guardar", ex.getMessage());
            }
        });

        stage.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void openInBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @FXML
    protected void onEnvironmentChanged() {
        if (ignoreEnvironmentCallback) {
            return;
        }
        LaunchEnvironment selected = comboEnvironment.getValue();
        if (selected == null || selected == serviceManager.getEnvironment()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Cambiar ambiente");
        confirm.setHeaderText("Pasar a «" + selected.getLabel() + "»");
        confirm.setContentText(
                "Se detendrán los servicios que este launcher haya arrancado y se usarán "
                        + "los puertos y carpetas de ese ambiente.\n\n"
                        + "Dev local: laptop (4200 / 8088), negocio en controlneg_rmx_db_v02, login en controlneg_rmx_db.\n"
                        + "Sandbox: repos/sandbox (4210 / 8188), BD sandbox.\n"
                        + "Caja actual: copia productiva (4200 / 8088 / controlneg_rmx_db), sin Caddy ni correos.\n"
                        + "Tienda Infinito: pila nueva (4220 / 8288 / 8295 / Caddy 8280), "
                        + "BD controlneg_rmx_db_v02, correos tienda-infinito@mayaksoluciones.com.\n"
                        + "No elijas Tienda Infinito en la laptop salvo que ella sea la caja."
        );
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK) {
                ignoreEnvironmentCallback = true;
                comboEnvironment.setValue(serviceManager.getEnvironment());
                ignoreEnvironmentCallback = false;
                return;
            }
            serviceManager.applyEnvironment(selected);
            refreshLanHostLabel();
            refreshMigrateUi();
        });
    }

    private void refreshMigrateUi() {
        if (btnEjecutarScripts == null || labelMigrateStatus == null) {
            return;
        }
        LaunchEnvironment env = serviceManager.getEnvironment();
        boolean allowed = env.allowsV02Migrate();
        boolean done = V02MigrateRunner.alreadyApplied();
        btnEjecutarScripts.setDisable(!allowed || done);
        if (!allowed) {
            labelMigrateStatus.setText("Scripts: solo en Tienda Infinito");
        } else if (done) {
            labelMigrateStatus.setText("Scripts ya aplicados (una vez)");
        } else {
            labelMigrateStatus.setText("Una vez: copia BD + migrate v02");
        }
    }

    @FXML
    protected void onEjecutarScripts() {
        LaunchEnvironment env = serviceManager.getEnvironment();
        if (!env.allowsV02Migrate()) {
            showWarning("Ambiente", "Elige Tienda Infinito para ejecutar los scripts.");
            return;
        }
        if (V02MigrateRunner.alreadyApplied()) {
            showWarning("Ya aplicado", "Los scripts de v02 ya se ejecutaron en esta máquina.");
            refreshMigrateUi();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Ejecutar scripts v02");
        confirm.setHeaderText("Una sola vez — controlneg_rmx_db_v02");
        confirm.setContentText(
                "1) Si no existe, copia controlneg_rmx_db → controlneg_rmx_db_v02 (dump, no pisa la productiva).\n"
                        + "2) Aplica schema dian-v2 + correcciones (Caja Menor / Dist. 64_, ventas 65_, correo 66_).\n"
                        + "3) Deja email_alerta_pagos = tienda-infinito@mayaksoluciones.com.\n\n"
                        + "No toca controlneg_rmx_db. Después el botón queda bloqueado."
        );
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefHeight(360);
        Button closeBtn = new Button("Cerrar");
        closeBtn.setDisable(true);
        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new Insets(10));
        Stage stage = new Stage();
        stage.setTitle("Migración Tienda Infinito v02");
        stage.setScene(new Scene(root, 720, 440));
        stage.show();
        closeBtn.setOnAction(e -> stage.close());

        Path projects = Path.of(AppConfig.getInstance().getBasePath());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                V02MigrateRunner.run(projects, line -> Platform.runLater(() -> {
                    logArea.appendText(line + "\n");
                    logArea.setScrollTop(Double.MAX_VALUE);
                }));
                Platform.runLater(() -> {
                    closeBtn.setDisable(false);
                    refreshMigrateUi();
                    showInfo("Scripts aplicados", "v02 listo. Inicia Tienda Infinito (Caddy + puente + túnel) para correos.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    logArea.appendText("ERROR: " + ex.getMessage() + "\n");
                    closeBtn.setDisable(false);
                    showWarning("Migración incompleta", ex.getMessage());
                });
            }
        });
    }

    @FXML
    protected void onPrepareHost() {
        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefHeight(360);
        Button closeBtn = new Button("Cerrar");
        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new Insets(10));
        Stage stage = new Stage();
        stage.setTitle("Preparar esta máquina");
        stage.setScene(new Scene(root, 680, 420));
        stage.show();
        closeBtn.setOnAction(e -> stage.close());
        LaunchEnvironment env = serviceManager.getEnvironment();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                PrepareHost.run(env, line -> Platform.runLater(() -> {
                    logArea.appendText(line + "\n");
                    logArea.setScrollTop(Double.MAX_VALUE);
                }));
            } catch (Exception ex) {
                Platform.runLater(() -> logArea.appendText("ERROR: " + ex.getMessage() + "\n"));
            }
        });
    }

    @FXML
    protected void onSelectProjectsFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Seleccionar carpeta de proyectos");
        File basePath = new File(AppConfig.getInstance().getBasePath());
        if (basePath.isDirectory()) {
            chooser.setInitialDirectory(basePath);
        }
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
        File logsDir = new File(System.getProperty("user.home"), ".infinitesoft/logs");
        if (!logsDir.exists()) logsDir.mkdirs();
        try {
            Desktop.getDesktop().open(logsDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    protected void onOpenDbManager() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("mac")) {
            try {
                new ProcessBuilder("open", "-a", "DBeaver").start();
            } catch (IOException e1) {
                try {
                    new ProcessBuilder("open", "-a", "DBeaverEE").start();
                } catch (IOException e2) {
                    try {
                        new ProcessBuilder("dbeaver").start();
                    } catch (IOException e3) {
                        e3.printStackTrace();
                    }
                }
            }
        } else if (osName.contains("win")) {
            try {
                new ProcessBuilder("cmd.exe", "/c", "start", "", "dbeaver").start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                new ProcessBuilder("dbeaver").start();
            } catch (IOException e1) {
                try {
                    new ProcessBuilder("dbeaver-ce").start();
                } catch (IOException e2) {
                    e2.printStackTrace();
                }
            }
        }
    }

    // Botones globales
    @FXML
    protected void onStartAll() {
        serviceManager.startAllSequential(Duration.ofSeconds(60));
    }

    @FXML
    protected void onStopAll() {
        serviceManager.stopAll();
    }

    @FXML
    protected void onRestartAll() {
        serviceManager.stopAll();
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            serviceManager.startAllSequential(Duration.ofSeconds(60));
        }).start();
    }

    // DB
    @FXML protected void onStartDb() { serviceManager.start(ServiceManager.NAME_DB); }
    @FXML protected void onStopDb() { serviceManager.stop(ServiceManager.NAME_DB); }
    @FXML protected void onRestartDb() { serviceManager.restart(ServiceManager.NAME_DB); }

    // Security
    @FXML protected void onStartSecurity() { serviceManager.start(ServiceManager.NAME_SECURITY); }
    @FXML protected void onStopSecurity() { serviceManager.stop(ServiceManager.NAME_SECURITY); }
    @FXML protected void onRestartSecurity() { serviceManager.restart(ServiceManager.NAME_SECURITY); }
    @FXML protected void onUpdateSecurity() { openUpdateWindow("Seguridad", ServiceManager.NAME_SECURITY); }

    // SMTP
    @FXML protected void onStartSmtp() {
        if (!serviceManager.getEnvironment().startsSmtp()) {
            showWarning("No aplica", "Caja actual no levanta SMTP ni notificaciones.");
            return;
        }
        serviceManager.start(ServiceManager.NAME_SMTP);
    }
    @FXML protected void onStopSmtp() { serviceManager.stop(ServiceManager.NAME_SMTP); }
    @FXML protected void onRestartSmtp() { serviceManager.restart(ServiceManager.NAME_SMTP); }
    @FXML protected void onUpdateSmtp() { openUpdateWindow("Correos (SMTP)", ServiceManager.NAME_SMTP); }

    // Store
    @FXML protected void onStartStore() { serviceManager.start(ServiceManager.NAME_STORE); }
    @FXML protected void onStopStore() { serviceManager.stop(ServiceManager.NAME_STORE); }
    @FXML protected void onRestartStore() { serviceManager.restart(ServiceManager.NAME_STORE); }
    @FXML protected void onUpdateStore() { openUpdateWindow("Lógica Tienda", ServiceManager.NAME_STORE); }

    // Front
    @FXML protected void onStartFront() { serviceManager.start(ServiceManager.NAME_FRONT); }
    @FXML protected void onStopFront() { serviceManager.stop(ServiceManager.NAME_FRONT); }
    @FXML protected void onRestartFront() { serviceManager.restart(ServiceManager.NAME_FRONT); }
    @FXML protected void onUpdateFront() { openUpdateWindow("Frontend", ServiceManager.NAME_FRONT); }

    @FXML protected void onStartPuente() {
        if (!serviceManager.getEnvironment().startsPuente()) {
            showWarning("No aplica", "Caja actual no recibe correo de banco (sin puente).");
            return;
        }
        serviceManager.start(ServiceManager.NAME_PUENTE);
    }
    @FXML protected void onStopPuente() { serviceManager.stop(ServiceManager.NAME_PUENTE); }
    @FXML protected void onRestartPuente() { serviceManager.restart(ServiceManager.NAME_PUENTE); }
    @FXML protected void onUpdatePuente() { openUpdateWindow("Puente", ServiceManager.NAME_PUENTE); }

    @FXML protected void onStartCaddy() {
        if (!serviceManager.getEnvironment().startsCaddy()) {
            showWarning("No aplica", "Caja actual no usa Caddy.");
            return;
        }
        serviceManager.start(ServiceManager.NAME_CADDY);
    }
    @FXML protected void onStopCaddy() { serviceManager.stop(ServiceManager.NAME_CADDY); }
    @FXML protected void onRestartCaddy() { serviceManager.restart(ServiceManager.NAME_CADDY); }

    @FXML protected void onStartTunnel() {
        if (!serviceManager.getEnvironment().startsTunnel()) {
            showWarning("No aplica", "Caja actual no usa túnel Cloudflare.");
            return;
        }
        serviceManager.start(ServiceManager.NAME_TUNNEL);
    }
    @FXML protected void onRestartTunnel() { serviceManager.restart(ServiceManager.NAME_TUNNEL); }

    private void openUpdateWindow(String serviceName, String serviceKey) {
        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 11px;");

        Button closeBtn = new Button("Cerrar");
        closeBtn.setDisable(true);

        VBox root = new VBox(8, logArea, closeBtn);
        root.setPadding(new Insets(10));
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
