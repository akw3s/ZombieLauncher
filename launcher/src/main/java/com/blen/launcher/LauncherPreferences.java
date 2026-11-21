package com.blen.launcher;

import javafx.scene.Parent;

import java.util.List;
import java.util.prefs.Preferences;

public final class LauncherPreferences {
    public static final int DEFAULT_MEMORY_GB = 8;
    public static final List<Integer> MEMORY_OPTIONS_GB = List.of(4, 6, 8, 10, 12, 16);

    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(LauncherPreferences.class);

    private LauncherPreferences() {
    }

    public static String getNickname() {
        return PREFERENCES.get("nickname", "");
    }

    public static void setNickname(String nickname) {
        PREFERENCES.put("nickname", nickname);
    }

    public static String getTheme() {
        return PREFERENCES.get("theme", "dark");
    }

    public static String getLanguage() {
        return PREFERENCES.get("language", "en");
    }

    public static int getMemoryGb() {
        int memoryGb = PREFERENCES.getInt("memoryGb", DEFAULT_MEMORY_GB);
        return MEMORY_OPTIONS_GB.contains(memoryGb) ? memoryGb : DEFAULT_MEMORY_GB;
    }

    public static void applyTheme(Parent root) {
        root.getStyleClass().removeAll("dark-theme", "light-theme");
        root.getStyleClass().add(getTheme().equals("light") ? "light-theme" : "dark-theme");
    }

    static void saveSettings(String theme, String language, int memoryGb) {
        if (!MEMORY_OPTIONS_GB.contains(memoryGb)) {
            throw new IllegalArgumentException("Unsupported game memory setting: " + memoryGb);
        }
        PREFERENCES.put("theme", theme);
        PREFERENCES.put("language", language);
        PREFERENCES.putInt("memoryGb", memoryGb);
    }
}
