package com.infinitesoft.launcher.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class AppConfig {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.infinitesoft";
    private static final String CONFIG_FILE = CONFIG_DIR + "/launcher.properties";
    private static final String KEY_BASE_PATH = "projects.base.path";
    private static final String DEFAULT_BASE_PATH = "C:/dev/repos";

    private static AppConfig instance;
    private final Properties props = new Properties();

    private AppConfig() {
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    public String getBasePath() {
        return props.getProperty(KEY_BASE_PATH, DEFAULT_BASE_PATH);
    }

    public void setBasePath(String path) {
        props.setProperty(KEY_BASE_PATH, path);
        save();
    }

    private void save() {
        try {
            new File(CONFIG_DIR).mkdirs();
            try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
                props.store(fos, "Infinito Launcher Config");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
