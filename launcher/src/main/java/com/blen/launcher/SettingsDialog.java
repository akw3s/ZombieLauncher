package com.blen.launcher;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

public final class SettingsDialog {
    private SettingsDialog() {
    }

    public static Optional<Selection> show(Window owner) {
        boolean russian = LauncherPreferences.getLanguage().equals("ru");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(russian ? "Настройки" : "Settings");
        dialog.initOwner(owner);

        ButtonType saveButton = new ButtonType(
                russian ? "Сохранить" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType(
                russian ? "Отмена" : "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(saveButton, cancelButton);

        ComboBox<Choice> themeChoice = new ComboBox<>();
        themeChoice.getItems().setAll(
                new Choice(russian ? "Тёмная" : "Dark", "dark"),
                new Choice(russian ? "Светлая" : "Light", "light"));
        selectValue(themeChoice, LauncherPreferences.getTheme());

        ComboBox<Choice> languageChoice = new ComboBox<>();
        languageChoice.getItems().setAll(
                new Choice("English", "en"),
                new Choice("Русский", "ru"));
        selectValue(languageChoice, LauncherPreferences.getLanguage());
        ComboBox<Choice> memoryChoice = new ComboBox<>();
        LauncherPreferences.MEMORY_OPTIONS_GB.forEach(memoryGb ->
                memoryChoice.getItems().add(new Choice(memoryGb + " GB", memoryGb.toString())));
        selectValue(memoryChoice, Integer.toString(LauncherPreferences.getMemoryGb()));
        themeChoice.setMaxWidth(Double.MAX_VALUE);
        languageChoice.setMaxWidth(Double.MAX_VALUE);
        memoryChoice.setMaxWidth(Double.MAX_VALUE);

        GridPane fields = new GridPane();
        fields.setHgap(20);
        fields.setVgap(18);
        fields.add(new Label(russian ? "Оформление" : "Appearance"), 0, 0);
        fields.add(themeChoice, 1, 0);
        fields.add(new Label(russian ? "Язык" : "Language"), 0, 1);
        fields.add(languageChoice, 1, 1);
        fields.add(new Label(russian ? "Память игры" : "Game memory"), 0, 2);
        fields.add(memoryChoice, 1, 2);
        GridPane.setHgrow(themeChoice, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(languageChoice, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(memoryChoice, javafx.scene.layout.Priority.ALWAYS);

        VBox content = new VBox(16, fields);
        content.setPadding(new Insets(20, 8, 8, 8));
        content.getStyleClass().add("settings-content");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("settings-dialog");
        dialog.getDialogPane().getStyleClass().add(
                LauncherPreferences.getTheme().equals("light") ? "light-theme" : "dark-theme");
        dialog.getDialogPane().getStylesheets().add(
                SettingsDialog.class.getResource("/css/styles.css").toExternalForm());
        dialog.setResultConverter(button -> button);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == saveButton) {
            Selection selection = new Selection(
                    themeChoice.getValue().value(), languageChoice.getValue().value(),
                    Integer.parseInt(memoryChoice.getValue().value()));
            LauncherPreferences.saveSettings(
                    selection.theme(), selection.language(), selection.memoryGb());
            return Optional.of(selection);
        }
        return Optional.empty();
    }

    private static void selectValue(ComboBox<Choice> combo, String value) {
        combo.getItems().stream()
                .filter(choice -> choice.value().equals(value))
                .findFirst()
                .ifPresent(combo.getSelectionModel()::select);
    }

    public record Selection(String theme, String language, int memoryGb) {
    }

    private record Choice(String label, String value) {
        @Override
        public String toString() {
            return label;
        }
    }
}
