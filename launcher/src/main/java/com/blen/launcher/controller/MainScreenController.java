package com.blen.launcher.controller;

import com.blen.core.assets.AssetManager;
import com.blen.core.auth.AuthManager;
import com.blen.core.auth.Session;
import com.blen.core.launch.ForgeProfileManager;
import com.blen.core.launch.GameLauncher;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.blen.launcher.LauncherPreferences;
import com.blen.launcher.SettingsDialog;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller for the main launcher screen. Handles version selection and game launching.
 */
public class MainScreenController {
    @FXML
    private Label welcomeLabel;
    @FXML
    private Label profileHeading;
    @FXML
    private Label gameHeading;
    @FXML
    private Label profileDescription;
    @FXML
    private Label modName;
    @FXML
    private Label modCompatibility;
    @FXML
    private Button settingsButton;
    @FXML
    private Button launchButton;
    @FXML
    private Label statusLabel;
    @FXML
    private ProgressIndicator progressIndicator;
    private AuthManager authManager;
    private VersionManager versionManager;
    private AssetManager assetManager;
    private ForgeProfileManager forgeProfileManager;
    private GameLauncher gameLauncher;
    private Session session;
    private Process currentProcess;

    public void initialize() {
        Path installDir = Paths.get(System.getProperty("user.home"), ".blen-launcher", "minecraft");
        authManager = new AuthManager(Paths.get(System.getProperty("user.home"), ".blen-launcher"));
        versionManager = new VersionManager(installDir);
        assetManager = new AssetManager(installDir);
        gameLauncher = new GameLauncher(installDir, versionManager);
        forgeProfileManager = new ForgeProfileManager(installDir, versionManager);

        session = authManager.getCurrentSession();
        if (session != null) {
            welcomeLabel.setText(session.getUsername());
        } else {
            welcomeLabel.setText(LauncherPreferences.getLanguage().equals("ru") ? "Игрок" : "Player");
        }
        applyLanguage();
        Platform.runLater(() ->
                LauncherPreferences.applyTheme(welcomeLabel.getScene().getRoot()));
    }

    @FXML
    private void handleSettings() {
        Stage stage = (Stage) settingsButton.getScene().getWindow();
        SettingsDialog.show(stage).ifPresent(selection -> {
            LauncherPreferences.applyTheme(settingsButton.getScene().getRoot());
            applyLanguage();
        });
    }

    private void applyLanguage() {
        boolean russian = LauncherPreferences.getLanguage().equals("ru");
        profileHeading.setText(russian ? "ПРОФИЛЬ" : "YOUR PROFILE");
        gameHeading.setText(russian ? "MINECRAFT" : "MINECRAFT");
        profileDescription.setText(russian
                ? "Классический мир Minecraft с модами."
                : "The classic modded survival experience.");
        modName.setText("AutoModpack 4.0.7");
        modCompatibility.setText(russian
                ? "Совместимо • Forge 1.20.1"
                : "Compatible • Forge 1.20.1");
        launchButton.setText(russian ? "ЗАПУСТИТЬ ИГРУ" : "LAUNCH GAME");
        settingsButton.setText(russian ? "Настройки" : "Settings");
        if (statusLabel.getText() == null || statusLabel.getText().isBlank()
                || statusLabel.getText().startsWith("Configured")
                || statusLabel.getText().startsWith("Настроено")) {
            statusLabel.setText(russian ? "Настроено для Forge 1.20.1."
                    : "Configured for Forge 1.20.1.");
        }
    }

    @FXML
    private void handleLaunch() {
        if (session == null) {
            boolean russian = LauncherPreferences.getLanguage().equals("ru");
            showAlert(russian ? "Требуется вход" : "Sign in required",
                    russian ? "Введите имя игрока, чтобы запустить игру."
                            : "Enter a Minecraft username before launching.");
            return;
        }

        launchButton.setDisable(true);
        statusLabel.setText(LauncherPreferences.getLanguage().equals("ru")
                ? "Подготовка..." : "Preparing...");
        progressIndicator.setVisible(true);
        progressIndicator.setManaged(true);

        Task<Boolean> launchTask = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                String javaExecutable = gameLauncher.getJavaExecutable();
                MinecraftVersion baseVersion = forgeProfileManager.prepare(javaExecutable, this::updateMessage);

                updateMessage("Preparing assets (first launch can take a few minutes)...");
                assetManager.downloadAssets(baseVersion.getId(), this::updateMessage);

                updateMessage("Launching Forge 1.20.1 with AutoModpack...");
                currentProcess = gameLauncher.launchForge(
                        session, baseVersion, ForgeProfileManager.FORGE_PROFILE_ID,
                        LauncherPreferences.getMemoryGb());

                if (currentProcess != null) {
                    final StringBuilder processLog = new StringBuilder();

                    Thread outputThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(currentProcess.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                synchronized (processLog) {
                                    processLog.append(line).append("\n");
                                }
                            }
                        } catch (IOException ex) {
                            System.err.println("Failed to read Minecraft output: " + ex.getMessage());
                        }
                    });
                    outputThread.setDaemon(true);
                    outputThread.start();

                    int exitCode = currentProcess.waitFor();
                    if (exitCode != 0) {
                        String tail;
                        synchronized (processLog) {
                            String all = processLog.toString();
                            int from = Math.max(0, all.length() - 1200);
                            tail = all.substring(from);
                        }
                        throw new IOException("Minecraft exited with code " + exitCode +
                                (tail.isBlank() ? "" : "\nLast output:\n" + tail));
                    }
                    return true;
                }
                return false;
            }
        };

        launchTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            Platform.runLater(() -> {
                statusLabel.setText(newMsg);
            });
        });

        launchTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                statusLabel.setText(LauncherPreferences.getLanguage().equals("ru")
                        ? "Игра закрыта." : "Minecraft closed.");
                launchButton.setDisable(false);
                progressIndicator.setVisible(false);
                progressIndicator.setManaged(false);
            });
        });

        launchTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                String prefix = LauncherPreferences.getLanguage().equals("ru")
                        ? "Ошибка: " : "Error: ";
                statusLabel.setText(prefix + launchTask.getException().getMessage());
                launchButton.setDisable(false);
                progressIndicator.setVisible(false);
                progressIndicator.setManaged(false);
            });
        });

        new Thread(launchTask).start();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/css/styles.css").toExternalForm());
        alert.getDialogPane().getStyleClass().add(
                LauncherPreferences.getTheme().equals("light") ? "light-theme" : "dark-theme");
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
