package com.oracle.wls.agentic.ai;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.helidon.integrations.langchain4j.Ai;
import io.helidon.service.registry.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service.Singleton
@Ai.Tool
public class LocalFileTool {

    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;

    @Tool("Download a remote file from an HTTP/HTTPS URL into /tmp and return the local downloaded path")
    public String downloadFile(@P("HTTP/HTTPS URL to download") String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "ERROR: only http/https URLs are supported.";
            }

            String fileName = inferFileName(uri);
            Path workDir = Files.createDirectories(Path.of("/tmp", "wls-rda-" + UUID.randomUUID()));
            Path target = workDir.resolve(fileName);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMinutes(3))
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                return "ERROR: download failed with status " + response.statusCode();
            }

            long totalBytes = 0;
            try (InputStream in = response.body()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_DOWNLOAD_BYTES) {
                        return "ERROR: file exceeds max allowed size (512MB).";
                    }
                    Files.write(target, java.util.Arrays.copyOf(buffer, read),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }

            return "OK\n"
                    + "downloadedPath=" + target + "\n"
                    + "workDir=" + workDir + "\n"
                    + "sizeBytes=" + totalBytes;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Unzip a downloaded archive into /tmp and return extracted directory and sample files")
    public String unzipArchiveToTmp(@P("Absolute path to a downloaded zip archive") String zipFilePath) {
        try {
            Path zipPath = Path.of(zipFilePath);
            if (!Files.exists(zipPath) || !Files.isRegularFile(zipPath)) {
                return "ERROR: zip file not found: " + zipPath;
            }

            Path extractDir = Files.createDirectories(Path.of("/tmp", "wls-rda-extracted-" + UUID.randomUUID()));
            int entries = 0;
            List<String> sampleFiles = new ArrayList<>();

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path outPath = extractDir.resolve(entry.getName()).normalize();
                    if (!outPath.startsWith(extractDir)) {
                        zis.closeEntry();
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(outPath);
                    } else {
                        Files.createDirectories(outPath.getParent());
                        Files.copy(zis, outPath);
                        if (sampleFiles.size() < 40) {
                            sampleFiles.add(extractDir.relativize(outPath).toString());
                        }
                    }
                    entries++;
                    zis.closeEntry();
                }
            }

            return "OK\n"
                    + "archivePath=" + zipPath + "\n"
                    + "extractedDir=" + extractDir + "\n"
                    + "entryCount=" + entries + "\n"
                    + "sampleFiles=" + String.join(",", sampleFiles);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("List files under an extracted directory for analysis")
    public String listFiles(@P("Absolute extracted directory path") String directoryPath,
                            @P("Maximum files to return") int maxFiles) {
        int limit = maxFiles <= 0 ? 200 : Math.min(maxFiles, 1000);
        try {
            Path root = Path.of(directoryPath);
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                return "ERROR: not a directory: " + root;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<String> files = walk
                        .filter(Files::isRegularFile)
                        .map(root::relativize)
                        .map(Path::toString)
                        .sorted(Comparator.naturalOrder())
                        .limit(limit)
                        .toList();
                return "OK\nfileCount=" + files.size() + "\nfiles=" + String.join("\n", files);
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool("Read a text file for diagnostics analysis")
    public String readTextFile(@P("Absolute file path") String filePath,
                               @P("Maximum characters to return") int maxChars) {
        int limit = maxChars <= 0 ? 12000 : Math.min(maxChars, 50000);
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "ERROR: file not found: " + path;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String trimmed = content.length() > limit ? content.substring(0, limit) : content;
            return "OK\n"
                    + "path=" + path + "\n"
                    + "sizeChars=" + content.length() + "\n"
                    + "capturedAt=" + Instant.now() + "\n"
                    + "content=\n" + trimmed;
        } catch (IOException e) {
            return "ERROR: could not read as UTF-8 text: " + e.getMessage();
        }
    }

    private static String inferFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "downloaded-file.bin";
        }
        int idx = path.lastIndexOf('/');
        String name = idx >= 0 ? path.substring(idx + 1) : path;
        return name.isBlank() ? "downloaded-file.bin" : name;
    }
}