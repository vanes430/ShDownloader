package com.github.vanes430;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ===== Spigot/Bukkit side =====
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

// ===== Velocity side =====
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

// ===== YAML (shaded) =====
import org.yaml.snakeyaml.Yaml;

public class Shdownloader extends JavaPlugin {

    // ---- Defaults ----
    private static final String DEFAULT_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
    private static final int DEFAULT_MAX_Mbps = 20;
    private static final int MAX_REDIRECTS = 10;

    // Bukkit config keys
    private static final String CFG_DOWNLOAD_PATH = "downloadpath";
    private static final String CFG_CUSTOM_HEADER = "customheaderrequest";
    private static final String CFG_MAX_MBPS = "maxspeed_on_mbps";

    // ---- Bukkit lifecycle ----
    @Override
    public void onEnable() {
        // Ensure default config exists
        saveDefaultConfig();
        getLogger().info("[ShDownloader] Enabled. Use /shdownload from the console.");
        getCommand("shdownload").setExecutor(new BukkitConsoleCommandExecutor(this));
    }

    // ---- Bukkit Command ----
    private static class BukkitConsoleCommandExecutor implements CommandExecutor {
        private final Shdownloader plugin;
        BukkitConsoleCommandExecutor(Shdownloader plugin) {
            this.plugin = plugin;
        }
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof ConsoleCommandSender)) {
                sender.sendMessage("This command can only be executed from the console.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("Usage: /" + label + " <url> [--name <filename>]");
                return true;
            }
            String urlArg = args[0];
            String customName = null;
            for (int i = 1; i < args.length; i++) {
                if ("--name".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                    customName = args[i + 1];
                    i++;
                }
            }

            final String finalCustomName = customName;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String basePath = plugin.getConfig().getString(CFG_DOWNLOAD_PATH, "./");
                String ua = plugin.getConfig().getString(CFG_CUSTOM_HEADER, DEFAULT_UA);
                int mbps = plugin.getConfig().getInt(CFG_MAX_MBPS, DEFAULT_MAX_Mbps);
                if (!isValidUA(ua)) ua = DEFAULT_UA;
                if (mbps <= 0) mbps = DEFAULT_MAX_Mbps;

                try {
                    Path outDir = resolveBasePath(basePath);
                    Files.createDirectories(outDir);

                    DownloadResult result = downloadWithRedirect(urlArg, outDir, finalCustomName, ua, mbps, plugin.getLoggerAdapter());
                    sender.sendMessage(result.message);
                } catch (Exception ex) {
                    sender.sendMessage("Download failed: " + ex.getMessage());
                    plugin.getLogger().warning("Download failed: " + ex.getMessage());
                }
            });
            return true;
        }
    }

    // ---- Logger adapter to decouple from platform ----
    public LoggerAdapter getLoggerAdapter() {
        return new LoggerAdapter() {
            @Override public void info(String m) { getLogger().info(m); }
            @Override public void warn(String m) { getLogger().warning(m); }
        };
    }

    // ---- Velocity Entry ----
    @Plugin(id = "shdownloader", name = "ShDownloader", version = "1.0.0", authors = {"vanes430"})
    public static class VelocityEntrypoint {
        private final ProxyServer server;
        private final Logger logger;
        private final Path dataDir;

        @Inject
        public VelocityEntrypoint(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
            this.server = server;
            this.logger = logger;
            this.dataDir = dataDir;
        }

        @Subscribe
        public void onProxyInitialization(ProxyInitializeEvent event) {
            // Ensure default config exists (Velocity side)
            try {
                ensureDefaultConfig(dataDir);
            } catch (IOException e) {
                logger.warn("[ShDownloader] Could not write default config.yml: " + e.getMessage());
            }

            CommandMeta meta = server.getCommandManager().metaBuilder("shdownload").build();
            server.getCommandManager().register(meta, new SimpleCommand() {
                @Override
                public void execute(Invocation invocation) {
                    if (!(invocation.source() instanceof com.velocitypowered.api.proxy.ConsoleCommandSource)) {
                        invocation.source().sendMessage(Component.text("This command can only be executed from the console."));
                        return;
                    }
                    String[] args = invocation.arguments();
                    if (args.length < 1) {
                        invocation.source().sendMessage(Component.text("Usage: /shdownload <url> [--name <filename>]"));
                        return;
                    }
                    String urlArg = args[0];
                    String customName = null;
                    for (int i = 1; i < args.length; i++) {
                        if ("--name".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                            customName = args[i + 1];
                            i++;
                        }
                    }

                    final String urlArgFinal = urlArg;
                    final String customNameFinal = customName;

                    server.getScheduler().buildTask(VelocityEntrypoint.this, () -> {
                        Map<String, Object> cfg = loadVelocityYamlConfig(dataDir);
                        String basePath = asString(cfg.getOrDefault("downloadpath", "./"));
                        String ua = asString(cfg.getOrDefault("customheaderrequest", DEFAULT_UA));
                        int mbps = asInt(cfg.getOrDefault("maxspeed_on_mbps", DEFAULT_MAX_Mbps));
                        if (!isValidUA(ua)) ua = DEFAULT_UA;
                        if (mbps <= 0) mbps = DEFAULT_MAX_Mbps;

                        try {
                            Path outDir = resolveBasePath(basePath);
                            Files.createDirectories(outDir);
                            DownloadResult result = downloadWithRedirect(urlArgFinal, outDir, customNameFinal, ua, mbps, new LoggerAdapter() {
                                @Override public void info(String m) { logger.info(m); }
                                @Override public void warn(String m) { logger.warn(m); }
                            });
                            invocation.source().sendMessage(Component.text(result.message));
                        } catch (Exception ex) {
                            invocation.source().sendMessage(Component.text("Download failed: " + ex.getMessage()));
                            logger.warn("Download failed: " + ex.getMessage());
                        }
                    }).schedule();
                }
            });
            logger.info("[ShDownloader] Enabled. Use /shdownload from the console.");
        }
    }

    // ---- Shared: download logic ----
    private static class DownloadResult {
        final Path file;
        final String message;
        DownloadResult(Path file, String message) {
            this.file = file;
            this.message = message;
        }
    }

    private static DownloadResult downloadWithRedirect(String urlStr, Path outDir, String desiredName, String userAgent, int maxMbps, LoggerAdapter log) throws Exception {
        URL url = new URL(urlStr);
        int redirects = 0;
        HttpURLConnection conn = null;
        while (true) {
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.connect();

            int code = conn.getResponseCode();
            if (isRedirect(code)) {
                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location header");
                url = new URL(resolveRedirect(url, loc));
                redirects++;
                if (redirects > MAX_REDIRECTS) throw new IOException("Too many redirects");
                log.info("[ShDownloader] Redirecting to: " + url);
                conn.disconnect();
                continue;
            }
            if (code >= 400) {
                InputStream es = null;
                try { es = conn.getErrorStream(); } catch (Throwable ignored) {}
                String msg = (es != null) ? readFew(es) : "";
                throw new IOException("HTTP " + code + " " + conn.getResponseMessage() + (msg.isEmpty() ? "" : (" - " + msg)));
            }
            break;
        }

        String contentDisposition = conn.getHeaderField("Content-Disposition");
        String inferred = inferFileNameFromUrlOrHeader(url, contentDisposition);
        String finalName = (desiredName != null && !desiredName.trim().isEmpty()) ? sanitizeFileName(desiredName.trim()) : inferred;
        if (finalName == null || finalName.isEmpty()) {
            finalName = "downloaded_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        }

        Path outFile = outDir.resolve(finalName);
        outFile = uniquify(outFile);

        final long maxBytesPerSec = (long) maxMbps * 125_000L; // Mbps -> bytes/sec
        try (InputStream in = conn.getInputStream(); OutputStream out = new BufferedOutputStream(Files.newOutputStream(outFile))) {
            throttleCopy(in, out, maxBytesPerSec, log);
        } finally {
            conn.disconnect();
        }

        return new DownloadResult(outFile, "Downloaded to " + outFile.toAbsolutePath().toString());
    }

    // Copy with throttling by bytes/second
    private static void throttleCopy(InputStream in, OutputStream out, long maxBytesPerSec, LoggerAdapter log) throws IOException {
        byte[] buf = new byte[8192];
        long windowStart = System.nanoTime();
        long windowBytes = 0L;

        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
            windowBytes += read;

            long elapsedNanos = System.nanoTime() - windowStart;
            if (maxBytesPerSec > 0 && elapsedNanos > 0) {
                double elapsedSec = elapsedNanos / 1_000_000_000.0;
                double currentBps = windowBytes / Math.max(1e-6, elapsedSec);
                if (currentBps > maxBytesPerSec) {
                    // Sleep just enough to fall under cap
                    double targetSec = windowBytes / (double) maxBytesPerSec;
                    long sleepMillis = (long) Math.ceil((targetSec - elapsedSec) * 1000.0);
                    if (sleepMillis > 0) {
                        try { Thread.sleep(sleepMillis); } catch (InterruptedException ignored) {}
                    }
                }
                // Reset window every ~1s to keep numbers stable
                if (elapsedSec >= 1.0) {
                    windowStart = System.nanoTime();
                    windowBytes = 0L;
                }
            }
        }
        out.flush();
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM ||
               code == HttpURLConnection.HTTP_MOVED_TEMP ||
               code == HttpURLConnection.HTTP_SEE_OTHER ||
               code == 307 || code == 308;
    }

    private static String resolveRedirect(URL base, String location) throws MalformedURLException {
        if (location.startsWith("http://") || location.startsWith("https://")) return location;
        if (location.startsWith("//")) return base.getProtocol() + ":" + location;
        if (location.startsWith("/")) {
            return base.getProtocol() + "://" + base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "") + location;
        }
        // relative path
        String path = base.getPath();
        if (!path.endsWith("/")) {
            int idx = path.lastIndexOf('/');
            if (idx >= 0) path = path.substring(0, idx + 1);
            else path = "/";
        }
        return base.getProtocol() + "://" + base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "") + path + location;
    }

    private static String readFew(InputStream es) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 3) {
                sb.append(line).append(' ');
                lines++;
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static Path resolveBasePath(String base) {
        Path p = Paths.get(base).normalize();
        if (!p.isAbsolute()) {
            p = Paths.get(".").toAbsolutePath().normalize().resolve(p).normalize();
        }
        return p;
    }

    private static String inferFileNameFromUrlOrHeader(URL url, String contentDisposition) {
        // Try Content-Disposition filename
        if (contentDisposition != null) {
            String fn = parseContentDispositionFilename(contentDisposition);
            if (fn != null && !fn.trim().isEmpty()) return sanitizeFileName(fn);
        }
        // From URL path
        String path = url.getPath();
        if (path != null && path.length() > 1) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return sanitizeFileName(last);
        }
        // From host
        return sanitizeFileName(url.getHost());
    }

    private static String parseContentDispositionFilename(String cd) {
        // filename*=UTF-8''encoded or filename="quoted"
        Pattern p1 = Pattern.compile("filename\\*=([^']*)''([^;]+)");
        Matcher m1 = p1.matcher(cd);
        if (m1.find()) {
            try {
                String enc = m1.group(1);
                String val = m1.group(2);
                return java.net.URLDecoder.decode(val, enc != null && !enc.isEmpty() ? enc : "UTF-8");
            } catch (Exception ignored) {}
        }
        Pattern p2 = Pattern.compile("filename=\"?([^\";]+)\"?");
        Matcher m2 = p2.matcher(cd);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private static String sanitizeFileName(String name) {
        // remove path traversal and illegal characters
        name = name.replace("\\", "/");
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name.replaceAll("[\\r\\n\\t]", "").replaceAll("[<>:\"|?*]", "_");
    }

    private static Path uniquify(Path path) {
        if (!Files.exists(path)) return path;
        String fn = path.getFileName().toString();
        String base = fn;
        String ext = "";
        int dot = fn.lastIndexOf('.');
        if (dot > 0) { base = fn.substring(0, dot); ext = fn.substring(dot); }
        for (int i = 1; i < 10000; i++) {
            Path candidate = path.getParent().resolve(base + "(" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return path.getParent().resolve(base + "(" + System.currentTimeMillis() + ")" + ext);
    }

    private static boolean isValidUA(String ua) {
        if (ua == null) return false;
        String s = ua.trim();
        if (s.isEmpty()) return false;
        // A basic heuristic: must resemble a mainstream UA
        return s.contains("Mozilla/5.0") && (s.contains("Chrome") || s.contains("Firefox") || s.contains("Safari"));
    }

    private static Map<String, Object> loadVelocityYamlConfig(Path dataDir) {
        Path cfg = dataDir.resolve("config.yml");
        try (InputStream in = Files.newInputStream(cfg)) {
            Yaml yaml = new Yaml();
            Object obj = yaml.load(in);
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) obj;
                return map;
            }
        } catch (Exception ignored) {}
        Map<String, Object> def = new HashMap<>();
        def.put("downloadpath", "./");
        def.put("customheaderrequest", DEFAULT_UA);
        def.put("maxspeed_on_mbps", DEFAULT_MAX_Mbps);
        return def;
    }

    private static void ensureDefaultConfig(Path dataDir) throws IOException {
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        Path cfg = dataDir.resolve("config.yml");
        if (!Files.exists(cfg)) {
            try (InputStream in = Shdownloader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, cfg);
                } else {
                    // Fallback minimal config if the resource is missing
                    Files.write(cfg, Arrays.asList(
                        "downloadpath: \"./\"",
                        "customheaderrequest: \"" + DEFAULT_UA.replace("\"", "\\\"") + "\"",
                        "maxspeed_on_mbps: " + DEFAULT_MAX_Mbps
                    ), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static String asString(Object o) { return o == null ? "" : String.valueOf(o); }
    private static int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number)o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    // ---- Simple cross-platform logger adapter ----
    public interface LoggerAdapter {
        void info(String m);
        void warn(String m);
    }
}
