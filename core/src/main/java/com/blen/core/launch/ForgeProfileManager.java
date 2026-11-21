package com.blen.core.launch;

import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class ForgeProfileManager {
    public static final String MINECRAFT_VERSION = "1.20.1";
    public static final String FORGE_VERSION = "47.4.29";
    public static final String FORGE_PROFILE_ID = MINECRAFT_VERSION + "-forge-" + FORGE_VERSION;

    private static final String FORGE_INSTALLER_URL =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                    MINECRAFT_VERSION + "-" + FORGE_VERSION + "/forge-" +
                    MINECRAFT_VERSION + "-" + FORGE_VERSION + "-installer.jar";
    private static final String FORGE_INSTALLER_SHA1 =
            "5061de68499474cc8eeeede3d794afb063dd3e6d";
    private static final String AUTOMODPACK_URL =
            "https://cdn.modrinth.com/data/k68glP2e/versions/KcBYU5P0/" +
                    "automodpack-mc1.20.1-forge-4.0.7.jar";
    private static final String AUTOMODPACK_SHA1 =
            "9c116d8aaf8e43a531b474e91b36f5a4ddee689e";
    private static final String AUTOMODPACK_FILE = "automodpack-mc1.20.1-forge-4.0.7.jar";

    private final Path installDir;
    private final VersionManager versionManager;

    public ForgeProfileManager(Path installDir, VersionManager versionManager) {
        this.installDir = installDir;
        this.versionManager = versionManager;
    }

    public MinecraftVersion prepare(String javaExecutable, ProgressCallback callback)
            throws IOException, InterruptedException {
        if (callback != null) {
            callback.onProgress("Preparing Minecraft " + MINECRAFT_VERSION + "...");
        }
        List<MinecraftVersion> versions = versionManager.getAvailableVersions();
        MinecraftVersion baseVersion = versions.stream()
                .filter(version -> MINECRAFT_VERSION.equals(version.getId()))
                .findFirst()
                .orElseThrow(() -> new IOException("Minecraft " + MINECRAFT_VERSION +
                        " is not available in the version manifest."));
        versionManager.downloadVersion(baseVersion, callback == null ? null : callback::onProgress);

        Path profileFile = versionManager.getVersionDir(FORGE_PROFILE_ID)
                .resolve(FORGE_PROFILE_ID + ".json");
        if (!isInstalledForgeProfile(profileFile)) {
            installForge(javaExecutable, profileFile, callback);
        }
        installAutoModpack(callback);
        return baseVersion;
    }

    private boolean isInstalledForgeProfile(Path profileFile) {
        if (!Files.isRegularFile(profileFile)) {
            return false;
        }
        try {
            JsonObject profile = JsonParser.parseString(Files.readString(profileFile)).getAsJsonObject();
            return profile.has("mainClass") && profile.has("inheritsFrom")
                    && MINECRAFT_VERSION.equals(profile.get("inheritsFrom").getAsString())
                    && profile.has("arguments");
        } catch (RuntimeException | IOException ex) {
            return false;
        }
    }

    private void installForge(String javaExecutable, Path profileFile, ProgressCallback callback)
            throws IOException, InterruptedException {
        Path cacheDir = installDir.resolve("launcher-cache");
        Files.createDirectories(cacheDir);
        Path installer = cacheDir.resolve("forge-installer-" + FORGE_VERSION + ".jar");
        if (!Files.isRegularFile(installer) || !FORGE_INSTALLER_SHA1.equals(sha1(installer))) {
            if (callback != null) {
                callback.onProgress("Downloading Forge " + FORGE_VERSION + "...");
            }
            downloadFile(FORGE_INSTALLER_URL, installer);
            if (!FORGE_INSTALLER_SHA1.equals(sha1(installer))) {
                Files.deleteIfExists(installer);
                throw new IOException("Downloaded Forge installer failed its SHA-1 integrity check.");
            }
        }

        if (callback != null) {
            callback.onProgress("Installing Forge " + FORGE_VERSION + "...");
        }
        Path launcherProfiles = installDir.resolve("launcher_profiles.json");
        if (!Files.exists(launcherProfiles)) {
            Files.writeString(launcherProfiles, "{\"profiles\":{},\"settings\":{},\"version\":3}");
        }
        Process process = new ProcessBuilder(javaExecutable, "-jar", installer.toString(),
                "--installClient", installDir.toString())
                .directory(installDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder installerOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    String outputLine = line.length() > 6000
                            ? line.substring(line.length() - 6000) : line;
                    int excess = installerOutput.length() + outputLine.length() + 1 - 6000;
                    if (excess > 0) {
                        installerOutput.delete(0, excess);
                    }
                    installerOutput.append(outputLine).append(System.lineSeparator());
                }
                if (callback != null && !line.isBlank()) {
                    callback.onProgress(line);
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = installerOutput.toString().strip();
            throw new IOException("Forge installer failed with exit code " + exitCode +
                    (output.isEmpty() ? "." : ":\n" + output));
        }
        if (!isInstalledForgeProfile(profileFile)) {
            throw new IOException("Forge installer completed without creating the expected launch profile.");
        }
    }

    private void installAutoModpack(ProgressCallback callback) throws IOException {
        Path modsDir = installDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path modFile = modsDir.resolve(AUTOMODPACK_FILE);
        if (Files.isRegularFile(modFile) && AUTOMODPACK_SHA1.equals(sha1(modFile))) {
            return;
        }

        if (callback != null) {
            callback.onProgress("Downloading AutoModpack 4.0.7...");
        }
        Path temporaryFile = modsDir.resolve(AUTOMODPACK_FILE + ".part");
        try {
            downloadFile(AUTOMODPACK_URL, temporaryFile);
            if (!AUTOMODPACK_SHA1.equals(sha1(temporaryFile))) {
                throw new IOException("Downloaded AutoModpack failed its SHA-1 integrity check.");
            }
            Files.move(temporaryFile, modFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void downloadFile(String url, Path destination) throws IOException {
        Path temporaryFile = destination.resolveSibling(destination.getFileName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("User-Agent", "ZombieLauncher/1.0");
        try {
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(temporaryFile)) {
                input.transferTo(output);
            }
            Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temporaryFile);
        }
    }

    private String sha1(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is not available.", ex);
        }
    }

    public interface ProgressCallback {
        void onProgress(String message);
    }
}
