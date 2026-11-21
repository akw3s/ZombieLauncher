package com.blen.core.launch;

import com.blen.core.auth.Session;
import com.blen.core.version.MinecraftVersion;
import com.blen.core.version.VersionManager;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Builds the classpath and launches Minecraft as a separate JVM process.
 * Handles JVM arguments, native libraries, and game arguments.
 */
public class GameLauncher {
    private static final String MC_MAIN_CLASS = "net.minecraft.client.main.Main";
    private static final int REQUIRED_JAVA_VERSION = 17;
    private static final String JDK_DOWNLOAD_URL = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse";

    private final Path installDir;
    private final VersionManager versionManager;

    public GameLauncher(Path installDir, VersionManager versionManager) {
        this.installDir = installDir;
        this.versionManager = versionManager;
    }

    /**
     * Launch Minecraft with the given session and version.
     */
    public Process launch(Session session, MinecraftVersion version, String... extraArgs)
            throws IOException {

        String javaExecutable = resolveJavaExecutable();
        Path nativesDir = prepareNatives(version);
        List<String> classpath = buildClasspath(version);
        List<String> jvmArgs = buildJvmArgs(session, version, nativesDir);
        List<String> gameArgs = buildGameArgs(session, version, version.getId());

        // Full command: java [jvm args] -cp [classpath] MC_MAIN_CLASS [game args]
        List<String> command = new ArrayList<>();

        // Java executable
        command.add(javaExecutable);

        // JVM arguments
        command.addAll(jvmArgs);

        // Classpath
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));

        // Main class
        command.add(MC_MAIN_CLASS);

        // Game arguments
        command.addAll(Arrays.asList(extraArgs));
        command.addAll(gameArgs);

        System.out.println("Launching: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);

        return pb.start();
    }

    public String getJavaExecutable() throws IOException {
        return resolveJavaExecutable();
    }

    public Process launchForge(Session session, MinecraftVersion baseVersion, String forgeProfileId)
            throws IOException {
        return launchForge(session, baseVersion, forgeProfileId, 8);
    }

    public Process launchForge(Session session, MinecraftVersion baseVersion, String forgeProfileId,
                               int maxMemoryGb) throws IOException {
        if (maxMemoryGb < 2 || maxMemoryGb > 64) {
            throw new IllegalArgumentException("Game memory must be between 2 and 64 GB.");
        }
        String javaExecutable = resolveJavaExecutable();
        Path forgeVersionDir = versionManager.getVersionDir(forgeProfileId);
        Path forgeManifestPath = forgeVersionDir.resolve(forgeProfileId + ".json");
        if (!Files.isRegularFile(forgeManifestPath)) {
            throw new IOException("Forge profile is not installed: " + forgeProfileId);
        }

        JsonObject forgeManifest = new com.google.gson.Gson().fromJson(
                Files.readString(forgeManifestPath), JsonObject.class);
        Path nativesDir = prepareNatives(baseVersion);
        List<String> classpath = buildForgeClasspath(baseVersion, forgeManifest);
        List<String> jvmArgs = buildForgeJvmArgs(
                forgeManifest, forgeProfileId, nativesDir, classpath, maxMemoryGb);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add(forgeManifest.get("mainClass").getAsString());
        command.addAll(readArgumentList(forgeManifest, "game"));
        command.addAll(buildGameArgs(session, baseVersion, forgeProfileId));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(installDir.toFile());
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    private List<String> buildForgeClasspath(MinecraftVersion baseVersion, JsonObject forgeManifest)
            throws IOException {
        List<String> classpath = new ArrayList<>();
        Path baseVersionDir = versionManager.getVersionDir(baseVersion.getId());
        JsonObject baseManifest = new com.google.gson.Gson().fromJson(
                Files.readString(baseVersionDir.resolve(baseVersion.getId() + ".json")), JsonObject.class);
        Path clientJar = baseVersionDir.resolve(baseVersion.getId() + ".jar");
        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("Minecraft client jar is missing: " + clientJar);
        }
        addManifestLibraries(baseManifest, classpath);
        addManifestLibraries(forgeManifest, classpath);
        return classpath;
    }

    private void addManifestLibraries(JsonObject manifest, List<String> classpath) throws IOException {
        if (!manifest.has("libraries")) {
            return;
        }
        for (com.google.gson.JsonElement libraryElement : manifest.getAsJsonArray("libraries")) {
            JsonObject library = libraryElement.getAsJsonObject();
            if (!library.has("name")) {
                continue;
            }
            String artifactPath = null;
            if (library.has("downloads")) {
                JsonObject downloads = library.getAsJsonObject("downloads");
                if (downloads.has("artifact") &&
                        downloads.getAsJsonObject("artifact").has("path")) {
                    artifactPath = downloads.getAsJsonObject("artifact").get("path").getAsString();
                }
            }
            if (artifactPath == null) {
                artifactPath = mavenPath(library.get("name").getAsString());
            }
            Path libraryPath = installDir.resolve("libraries").resolve(artifactPath);
            if (!Files.isRegularFile(libraryPath)) {
                throw new IOException("Required Forge library is missing: " + libraryPath);
            }
            String resolvedPath = libraryPath.toAbsolutePath().toString();
            if (!classpath.contains(resolvedPath)) {
                classpath.add(resolvedPath);
            }
        }
    }

    private String mavenPath(String coordinate) throws IOException {
        String[] parts = coordinate.split("@", 2);
        String extension = parts.length == 2 ? parts[1] : "jar";
        String[] components = parts[0].split(":");
        if (components.length < 3) {
            throw new IOException("Invalid Forge library coordinate: " + coordinate);
        }
        StringBuilder path = new StringBuilder(components[0].replace('.', '/'))
                .append('/').append(components[1])
                .append('/').append(components[2])
                .append('/').append(components[1]).append('-').append(components[2]);
        for (int i = 3; i < components.length; i++) {
            path.append('-').append(components[i]);
        }
        return path.append('.').append(extension).toString();
    }

    private List<String> buildForgeJvmArgs(JsonObject forgeManifest, String forgeProfileId,
                                           Path nativesDir, List<String> classpath, int maxMemoryGb)
            throws IOException {
        List<String> args = new ArrayList<>();
        args.add("-Xmx" + maxMemoryGb + "G");
        args.add("-Xms1G");
        args.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        args.add("-Djna.tmpdir=" + nativesDir.toAbsolutePath());
        args.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true");

        Map<String, String> values = new HashMap<>();
        values.put("library_directory", installDir.resolve("libraries").toAbsolutePath().toString());
        values.put("classpath_separator", File.pathSeparator);
        values.put("classpath", String.join(File.pathSeparator, classpath));
        values.put("version_name", forgeProfileId);
        values.put("natives_directory", nativesDir.toAbsolutePath().toString());
        for (String argument : readArgumentList(forgeManifest, "jvm")) {
            args.add(replacePlaceholders(argument, values));
        }
        return args;
    }

    private List<String> readArgumentList(JsonObject manifest, String key) throws IOException {
        List<String> args = new ArrayList<>();
        if (!manifest.has("arguments") || !manifest.getAsJsonObject("arguments").has(key)) {
            return args;
        }
        for (com.google.gson.JsonElement element :
                manifest.getAsJsonObject("arguments").getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                args.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject argument = element.getAsJsonObject();
                if (!argument.has("rules") && argument.has("value")) {
                    com.google.gson.JsonElement value = argument.get("value");
                    if (value.isJsonArray()) {
                        for (com.google.gson.JsonElement item : value.getAsJsonArray()) {
                            args.add(item.getAsString());
                        }
                    } else {
                        args.add(value.getAsString());
                    }
                }
            } else {
                throw new IOException("Unsupported " + key + " argument in Forge profile.");
            }
        }
        return args;
    }

    private String replacePlaceholders(String argument, Map<String, String> values) {
        String result = argument;
        for (Map.Entry<String, String> value : values.entrySet()) {
            result = result.replace("${" + value.getKey() + "}", value.getValue());
        }
        return result;
    }

    /**
     * Build the classpath from version libraries.
     */
    private List<String> buildClasspath(MinecraftVersion version) throws IOException {
        List<String> classpath = new ArrayList<>();

        // Add the game jar
        Path versionDir = versionManager.getVersionDir(version.getId());
        Path gameJar = versionDir.resolve(version.getId() + ".jar");
        if (Files.exists(gameJar)) {
            classpath.add(gameJar.toString());
        }

        // Add all libraries from the version manifest
        String manifestJson = Files.readString(
            versionDir.resolve(version.getId() + ".json"));
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        if (manifest.has("libraries")) {
            for (com.google.gson.JsonElement libEl : manifest.getAsJsonArray("libraries")) {
                JsonObject lib = libEl.getAsJsonObject();
                if (lib.has("downloads")) {
                    JsonObject downloads = lib.getAsJsonObject("downloads");
                    if (downloads.has("artifact")) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        if (artifact.has("path")) {
                            Path libPath = installDir.resolve("libraries").resolve(artifact.get("path").getAsString());
                            if (Files.exists(libPath)) {
                                classpath.add(libPath.toString());
                            }
                        }
                    }
                }
            }
        }

        return classpath;
    }

    /**
     * Build JVM arguments for the Minecraft process.
     */
    private List<String> buildJvmArgs(Session session, MinecraftVersion version, Path nativesDir) {
        List<String> args = new ArrayList<>();

        // Memory settings (maximum 8GB)
        args.add("-Xmx8G");
        args.add("-Xms2G");

        // Point LWJGL/JNA native loading to extracted natives.
        args.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
        args.add("-Djna.tmpdir=" + nativesDir.toAbsolutePath());
        args.add("-Dorg.lwjgl.librarypath=" + nativesDir.toAbsolutePath());

        // Java 17+ module exports needed for modern MC
        args.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        args.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        args.add("--add-opens=java.desktop/com.sun.javafx.tk.quantum=ALL-UNNAMED");

        // Logging disabled for cleaner output
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true");

        return args;
    }

    /**
     * Find a Java executable suitable for launching Minecraft.
     */
    private String resolveJavaExecutable() throws IOException {
        String candidate = findJavaExecutable();
        if (candidate != null) {
            int majorVersion = getJavaMajorVersion(candidate);
            if (majorVersion == REQUIRED_JAVA_VERSION || majorVersion == 21) {
                return candidate;
            }
        }

        Path bundledRuntime = installDir.getParent().resolve("runtime-java17");
        if (!hasValidBundledRuntime(bundledRuntime)) {
            downloadBundledRuntime(bundledRuntime);
        }

        String bundledJava = findJavaInRuntime(bundledRuntime);
        if (bundledJava != null && getJavaMajorVersion(bundledJava) == REQUIRED_JAVA_VERSION) {
            return bundledJava;
        }

        throw new IOException("A Java 17 runtime is required to launch this Forge profile. " +
                "Set ZOMBIE_JAVA_HOME or JAVA_HOME to Java 17, or retry while online so it can be downloaded.");
    }

    private boolean isJavaVersionAtLeast(String javaExecutable, int requiredVersion) throws IOException {
        return getJavaMajorVersion(javaExecutable) >= requiredVersion;
    }

    private int getJavaMajorVersion(String javaExecutable) throws IOException {
        String parsed = parseJavaVersion(getJavaVersionLine(javaExecutable));
        if (parsed == null) {
            return -1;
        }
        try {
            return Integer.parseInt(parsed);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String getJavaVersionLine(String javaExecutable) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-version");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.readLine();
        }
    }

    private boolean hasValidBundledRuntime(Path runtimeDir) {
        if (!Files.exists(runtimeDir)) {
            return false;
        }
        try {
            String java = findJavaInRuntime(runtimeDir);
            return java != null && getJavaMajorVersion(java) == REQUIRED_JAVA_VERSION;
        } catch (IOException ex) {
            return false;
        }
    }

    private String findJavaInRuntime(Path runtimeDir) throws IOException {
        String exe = isWindows() ? "java.exe" : "java";
        Path candidate = runtimeDir.resolve("bin").resolve(exe);
        if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
            return candidate.toString();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runtimeDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    Path nested = child.resolve("bin").resolve(exe);
                    if (Files.exists(nested) && Files.isRegularFile(nested)) {
                        return nested.toString();
                    }
                }
            }
        }
        return null;
    }

    private void downloadBundledRuntime(Path runtimeDir) throws IOException {
        if (!Files.exists(runtimeDir)) {
            Files.createDirectories(runtimeDir);
        }

        Path zipPath = runtimeDir.resolveSibling("jdk17.zip");
        try (InputStream in = new URL(JDK_DOWNLOAD_URL).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        unzip(zipPath, runtimeDir);
        Files.deleteIfExists(zipPath);
    }

    private void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zipStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zipStream.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                zipStream.closeEntry();
            }
        }
    }

    private String findJavaExecutable() {
        List<String> candidates = new ArrayList<>();
        String exe = isWindows() ? "java.exe" : "java";
        String zombieJavaHome = System.getenv("ZOMBIE_JAVA_HOME");
        if (zombieJavaHome != null && !zombieJavaHome.isBlank()) {
            candidates.add(Paths.get(zombieJavaHome, "bin", exe).toString());
        }
        String legacyJavaHome = System.getenv("BLEN_JAVA_HOME");
        if (legacyJavaHome != null && !legacyJavaHome.isBlank()) {
            candidates.add(Paths.get(legacyJavaHome, "bin", exe).toString());
        }
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.isBlank()) {
            candidates.add(Paths.get(envJavaHome, "bin", exe).toString());
        }
        Path runtimeDir = installDir.getParent().resolve("runtime-java17");
        try {
            String runtimeJava = findJavaInRuntime(runtimeDir);
            if (runtimeJava != null) {
                candidates.add(runtimeJava);
            }
        } catch (IOException ignored) {
        }
        String systemJavaHome = System.getProperty("java.home");
        if (systemJavaHome != null && !systemJavaHome.isBlank()) {
            candidates.add(Paths.get(systemJavaHome, "bin", exe).toString());
        }
        candidates.add(exe);

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path path = Paths.get(candidate);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String parseJavaVersion(String versionLine) {
        if (versionLine == null) {
            return null;
        }
        // Example: openjdk version "17.0.13" 2024-10-15
        int firstQuote = versionLine.indexOf('"');
        int secondQuote = firstQuote >= 0 ? versionLine.indexOf('"', firstQuote + 1) : -1;
        if (firstQuote >= 0 && secondQuote > firstQuote) {
            String v = versionLine.substring(firstQuote + 1, secondQuote);
            if (v.startsWith("1.")) {
                String[] parts = v.split("\\.");
                if (parts.length >= 2) {
                    return parts[1];
                }
            } else {
                String[] parts = v.split("\\.");
                if (parts.length > 0) {
                    return parts[0];
                }
            }
        }
        return null;
    }

    /**
     * Build game arguments (username, version, etc.).
     */
    private List<String> buildGameArgs(Session session, MinecraftVersion version, String launchVersion) {
        List<String> args = new ArrayList<>();

        // Required by net.minecraft.client.main.Main
        args.add("--username");
        args.add(session.getUsername());

        args.add("--version");
        args.add(launchVersion);

        args.add("--versionType");
        args.add("blen");

        args.add("--uuid");
        args.add(session.getUuid());

        args.add("--accessToken");
        args.add("0");

        args.add("--userType");
        args.add("legacy");

        args.add("--gameDir");
        args.add(installDir.toAbsolutePath().toString());

        // Optional: add --assetIndex (use manifest asset index id, e.g. "26")
        String assetIndexId = resolveAssetIndexId(version);
        if (assetIndexId != null) {
            args.add("--assetIndex");
            args.add(assetIndexId);
        }

        args.add("--assetsDir");
        args.add(installDir.resolve("assets").toAbsolutePath().toString());

        return args;
    }

    private String resolveAssetIndexId(MinecraftVersion version) {
        try {
            Path versionDir = versionManager.getVersionDir(version.getId());
            Path manifestPath = versionDir.resolve(version.getId() + ".json");
            if (!Files.exists(manifestPath)) {
                return null;
            }
            JsonObject manifest = new com.google.gson.Gson().fromJson(Files.readString(manifestPath), JsonObject.class);
            if (!manifest.has("assetIndex")) {
                return null;
            }
            JsonObject assetIndex = manifest.getAsJsonObject("assetIndex");
            return assetIndex.has("id") ? assetIndex.get("id").getAsString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Path prepareNatives(MinecraftVersion version) throws IOException {
        Path versionDir = versionManager.getVersionDir(version.getId());
        String manifestJson = Files.readString(versionDir.resolve(version.getId() + ".json"));
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject manifest = gson.fromJson(manifestJson, JsonObject.class);

        Path nativesDir = installDir.resolve("natives").resolve(version.getId());
        if (Files.exists(nativesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(nativesDir)) {
                if (stream.iterator().hasNext()) {
                    return nativesDir;
                }
            }
        } else {
            Files.createDirectories(nativesDir);
        }

        if (!manifest.has("libraries")) {
            return nativesDir;
        }

        for (com.google.gson.JsonElement libEl : manifest.getAsJsonArray("libraries")) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!lib.has("downloads")) {
                continue;
            }
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (!downloads.has("classifiers")) {
                continue;
            }
            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
            String key = classifiers.has("natives-windows") ? "natives-windows"
                    : (classifiers.has("natives-windows-64") ? "natives-windows-64" : null);
            if (key == null) {
                continue;
            }
            JsonObject nativeObj = classifiers.getAsJsonObject(key);
            if (!nativeObj.has("path")) {
                continue;
            }
            Path nativeJar = installDir.resolve("libraries").resolve(nativeObj.get("path").getAsString());
            if (Files.exists(nativeJar)) {
                extractNatives(nativeJar, nativesDir);
            }
        }
        return nativesDir;
    }

    private void extractNatives(Path nativeJar, Path nativesDir) throws IOException {
        try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                Path out = nativesDir.resolve(Paths.get(name).getFileName().toString());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
