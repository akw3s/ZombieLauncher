package com.blen.launcher.controller;

import com.blen.core.auth.AuthManager;
import com.blen.launcher.LauncherPreferences;
import com.blen.launcher.SettingsDialog;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Controller for the login screen. Handles cracked account authentication.
 */
public class LoginScreenController {
    @FXML
    private TextField usernameField;
    @FXML
    private Button loginButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private Label footnoteLabel;

    private AuthManager authManager;

    public void initialize() {
        Path storageDir = Paths.get(System.getProperty("user.home"), ".blen-launcher");
        authManager = new AuthManager(storageDir);
        usernameField.setText(LauncherPreferences.getNickname());
        updateLanguage();
        usernameField.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                LauncherPreferences.applyTheme(newScene.getRoot());
                usernameField.maxWidthProperty().bind(
                        Bindings.min(540, newScene.widthProperty().multiply(0.42)));
            }
        });
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();

        if (username.isEmpty()) {
            showAlert("Error", "Please enter a username.");
            return;
        }

        try {
            authManager.loginCracked(username);
            LauncherPreferences.setNickname(username);
            switchToMainScreen();
        } catch (Exception e) {
            showAlert(LauncherPreferences.getLanguage().equals("ru")
                            ? "Ошибка входа" : "Login Failed",
                    (LauncherPreferences.getLanguage().equals("ru")
                            ? "Не удалось выполнить вход: " : "Failed to login: ") + e.getMessage());
        }
    }

    @FXML
    private void handleSettings() {
        Stage stage = (Stage) settingsButton.getScene().getWindow();
        SettingsDialog.show(stage).ifPresent(selection -> {
            LauncherPreferences.applyTheme(settingsButton.getScene().getRoot());
            updateLanguage();
        });
    }

    private void updateLanguage() {
        boolean russian = LauncherPreferences.getLanguage().equals("ru");
        titleLabel.setText("ZombieLauncher");
        subtitleLabel.setText(russian
                ? "Ваш мир с модами Forge 1.20.1 начинается здесь."
                : "Your Forge 1.20.1 modded world starts here.");
        usernameField.setPromptText(russian ? "Имя игрока Minecraft" : "Minecraft username");
        loginButton.setText(russian ? "Продолжить" : "Continue");
        settingsButton.setText(russian ? "Настройки" : "Settings");
        footnoteLabel.setText(russian
                ? "Оффлайн-профиль • Для сетевой игры сервер должен разрешать оффлайн-аккаунты"
                : "Offline profile • Multiplayer requires a server that allows offline accounts");
    }

    private void switchToMainScreen() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/fxml/MainScreen.fxml"));
            javafx.scene.Scene scene = new javafx.scene.Scene(loader.load());
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            LauncherPreferences.applyTheme(scene.getRoot());

            if (usernameField.getScene() != null && usernameField.getScene().getWindow() instanceof Stage stage) {
                stage.setTitle("ZombieLauncher");
                stage.setScene(scene);
            } else {
                throw new IllegalStateException("Login window is not ready yet");
            }
        } catch (Exception e) {
            showAlert("Error", "Failed to load main screen: " + e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        boolean russian = LauncherPreferences.getLanguage().equals("ru");
        alert.setTitle(russian && title.equals("Error") ? "Ошибка" : title);
        alert.setHeaderText(null);
        alert.setContentText(russian && message.equals("Please enter a username.")
                ? "Введите имя игрока." : message);
        alert.showAndWait();
    }
}
