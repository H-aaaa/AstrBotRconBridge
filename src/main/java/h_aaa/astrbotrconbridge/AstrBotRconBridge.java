package h_aaa.astrbotrconbridge;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class AstrBotRconBridge extends JavaPlugin {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService acceptPool;
    private ExecutorService workerPool;
    private ServerSocket serverSocket;
    private LogCaptureManager logCaptureManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        logCaptureManager = new LogCaptureManager(this);
        if (getConfig().getBoolean("log-capture.enabled", true)) {
            logCaptureManager.install();
        }

        running.set(true);
        acceptPool = Executors.newSingleThreadExecutor();
        workerPool = Executors.newCachedThreadPool();

        final String host = getConfig().getString("bridge.host", "127.0.0.1");
        final int port = getConfig().getInt("bridge.port", 25580);

        acceptPool.submit(new Runnable() {
            @Override
            public void run() {
                startServer(host, port);
            }
        });

        getLogger().info("AstrBot bridge enabled on " + host + ":" + port);
    }

    @Override
    public void onDisable() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (acceptPool != null) {
            acceptPool.shutdownNow();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
        if (logCaptureManager != null) {
            logCaptureManager.uninstall();
        }
        getLogger().info("AstrBot bridge disabled");
    }

    private void startServer(String host, int port) {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(host, port));
            while (running.get()) {
                final Socket socket = serverSocket.accept();
                workerPool.submit(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                });
            }
        } catch (IOException e) {
            if (running.get()) {
                getLogger().severe("Bridge server stopped unexpectedly: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            socket.setSoTimeout(getConfig().getInt("bridge.read-timeout-ms", 15000));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), Charset.forName("UTF-8")));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), Charset.forName("UTF-8")));

            InetAddress address = socket.getInetAddress();
            if (!isAllowedIp(address)) {
                writeResponse(writer, false, getConfig().getString("messages.forbidden-ip", "FORBIDDEN_IP"), "");
                return;
            }

            String token = reader.readLine();
            if (token == null || !token.equals(getConfig().getString("bridge.token", "change_me"))) {
                writeResponse(writer, false, getConfig().getString("messages.auth-failed", "AUTH_FAILED"), "");
                return;
            }

            String line = reader.readLine();
            if (line == null) {
                writeResponse(writer, false, getConfig().getString("messages.bad-request", "BAD_REQUEST"), "");
                return;
            }

            BridgeRequest request = BridgeRequest.parse(line);
            if (request == null) {
                writeResponse(writer, false, getConfig().getString("messages.bad-request", "BAD_REQUEST"), "");
                return;
            }

            if ("PING".equalsIgnoreCase(request.action)) {
                writeResponse(writer, true, "PONG", "ready");
                return;
            }
            if (!"EXEC".equalsIgnoreCase(request.action)) {
                writeResponse(writer, false, getConfig().getString("messages.bad-request", "BAD_REQUEST"), "");
                return;
            }

            ExecResult result = executeRequest(request);
            writeResponse(writer, result.success, result.code, result.output);
        } catch (Exception e) {
            getLogger().warning("Bridge client error: " + e.getMessage());
            try {
                if (writer != null) {
                    writeResponse(writer, false, getConfig().getString("messages.internal-error", "INTERNAL_ERROR"), e.getMessage() == null ? "" : e.getMessage());
                }
            } catch (IOException ignored) {
            }
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ExecResult executeRequest(final BridgeRequest request) throws Exception {
        final ConsoleCommandSender console = Bukkit.getConsoleSender();
        final BridgeCommandSender sender = new BridgeCommandSender(console);

        final int effectiveWait = resolveWaitMs(request.waitMs);
        final boolean captureEnabled = getConfig().getBoolean("log-capture.enabled", true);
        final boolean captureOnlyWhenEmpty = getConfig().getBoolean("log-capture.only-when-empty", false);
        final int maxLines = Math.max(1, getConfig().getInt("log-capture.max-lines", 80));
        final boolean urlFirst = getConfig().getBoolean("log-capture.url-first", true);
        final Pattern filter = LogCaptureManager.compilePattern(getConfig().getString("log-capture.regex-filter", ""));
        final boolean includeExecutorLine = getConfig().getBoolean("log-capture.include-console-executor-line", false);
        final String logFilePath = getConfig().getString("log-capture.file-path", "logs/latest.log");

        final Holder<Boolean> okHolder = new Holder<Boolean>(Boolean.FALSE);

        LogCaptureManager.CaptureSession session = null;
        long logStartOffset = -1L;
        if (captureEnabled && effectiveWait > 0) {
            session = logCaptureManager.openSession(request.command, filter, maxLines, urlFirst);
            logStartOffset = getLogFileLength(logFilePath);
        }

        try {
            Future<?> future = Bukkit.getScheduler().callSyncMethod(this, new java.util.concurrent.Callable<Object>() {
                @Override
                public Object call() {
                    boolean ok = Bukkit.dispatchCommand(sender, request.command);
                    okHolder.value = Boolean.valueOf(ok);
                    return null;
                }
            });
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return new ExecResult(false, "EXEC_TIMEOUT", "");
            }

            String directOutput = joinLines(sender.getLines());
            String captured = "";

            if (session != null && (!captureOnlyWhenEmpty || isBlank(directOutput))) {
                if (includeExecutorLine) {
                    getLogger().info("[BridgeExec] " + request.command);
                }
                Thread.sleep(effectiveWait);
                captured = joinLines(session.snapshot());
                if (isBlank(captured)) {
                    captured = readNewLogLines(logFilePath, logStartOffset, maxLines, request.command);
                }
            }

            String finalOutput = mergeOutput(directOutput, captured);
            boolean success = okHolder.value.booleanValue();
            return new ExecResult(success, success ? "EXEC_OK" : "EXEC_REJECTED", safe(finalOutput));
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private int resolveWaitMs(int requestWaitMs) {
        int defaultWait = Math.max(0, getConfig().getInt("log-capture.default-wait-ms", 300));
        int maxWait = Math.max(0, getConfig().getInt("log-capture.max-wait-ms", 15000));
        int effective = requestWaitMs >= 0 ? requestWaitMs : defaultWait;
        if (effective > maxWait) {
            effective = maxWait;
        }
        return Math.max(0, effective);
    }

    private boolean isAllowedIp(InetAddress address) {
        if (!getConfig().getBoolean("security.enable-ip-whitelist", false)) {
            return true;
        }
        List<String> whitelist = getConfig().getStringList("security.whitelist");
        if (whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        String hostAddress = address.getHostAddress();
        String hostName = address.getHostName();
        for (String allowed : whitelist) {
            if (allowed == null) {
                continue;
            }
            String trimmed = allowed.trim();
            if (trimmed.equalsIgnoreCase(hostAddress) || trimmed.equalsIgnoreCase(hostName)) {
                return true;
            }
        }
        return false;
    }

    private void writeResponse(BufferedWriter writer, boolean success, String code, String output) throws IOException {
        writer.write("status=" + (success ? "ok" : "error"));
        writer.newLine();
        writer.write("code=" + safe(code));
        writer.newLine();
        writer.write("output_b64=" + base64Encode(safe(output)));
        writer.newLine();
        writer.write("end=1");
        writer.newLine();
        writer.flush();
    }

    private String mergeOutput(String directOutput, String capturedOutput) {
        LinkedHashSet<String> lines = new LinkedHashSet<String>();
        addLines(lines, directOutput);
        addLines(lines, capturedOutput);

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = safe(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private void addLines(LinkedHashSet<String> out, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        String[] arr = raw.split("\\r?\\n");
        for (String s : arr) {
            String t = safe(s).trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = safe(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private long getLogFileLength(String path) {
        try {
            File file = resolveLogFile(path);
            if (file == null || !file.isFile()) {
                return -1L;
            }
            return file.length();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String readNewLogLines(String path, long offset, int maxLines, String command) {
        if (offset < 0) {
            return "";
        }
        File file = resolveLogFile(path);
        if (file == null || !file.isFile()) {
            return "";
        }
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long start = Math.max(0L, Math.min(offset, raf.length()));
            raf.seek(start);
            String line;
            while ((line = raf.readLine()) != null) {
                String decoded = decodeRafLine(line).trim();
                if (decoded.isEmpty() || !looksRelevantLogLine(decoded, command)) {
                    continue;
                }
                out.add(stripLogPrefix(decoded));
                if (out.size() >= Math.max(1, maxLines)) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
        return joinLines(new ArrayList<String>(out));
    }

    private String decodeRafLine(String line) {
        try {
            return new String(line.getBytes("ISO-8859-1"), "UTF-8");
        } catch (Exception ignored) {
            return line;
        }
    }

    private File resolveLogFile(String path) {
        String resolved = (path == null || path.trim().isEmpty()) ? "logs/latest.log" : path.trim();
        File file = new File(resolved);
        if (file.isAbsolute()) {
            return file;
        }
        File dataFolder = getDataFolder();
        File pluginsDir = dataFolder == null ? null : dataFolder.getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();
        if (serverRoot == null) {
            return file;
        }
        return new File(serverRoot, resolved);
    }

    private boolean looksRelevantLogLine(String line, String command) {
        String lower = safe(line).toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://")) {
            return true;
        }
        if (lower.contains("players online") || lower.contains("there are ")) {
            return true;
        }
        if (command != null) {
            String[] parts = command.toLowerCase(Locale.ROOT).split("\\s+");
            for (String p : parts) {
                if (p.length() >= 2 && lower.contains(p)) {
                    return true;
                }
            }
        }
        List<String> extra = getConfig().getStringList("log-capture.extra-keywords");
        if (extra != null) {
            for (String keyword : extra) {
                if (keyword != null) {
                    String k = keyword.trim().toLowerCase(Locale.ROOT);
                    if (k.length() >= 2 && lower.contains(k)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String stripLogPrefix(String line) {
        String noPrefix = safe(line).replaceFirst("^\\[[^\\]]+\\]\\s*", "");
        noPrefix = noPrefix.replaceFirst("^\\[[^\\]]+/INFO\\]:\\s*", "");
        noPrefix = noPrefix.replaceFirst("^INFO\\]:\\s*", "");
        return noPrefix.trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String base64Encode(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(Charset.forName("UTF-8")));
    }

    private static final class Holder<T> {
        private T value;
        private Holder(T value) {
            this.value = value;
        }
    }

    private static final class ExecResult {
        private final boolean success;
        private final String code;
        private final String output;

        private ExecResult(boolean success, String code, String output) {
            this.success = success;
            this.code = code;
            this.output = output;
        }
    }

    private static final class BridgeRequest {
        private final String action;
        private final String command;
        private final int waitMs;

        private BridgeRequest(String action, String command, int waitMs) {
            this.action = action;
            this.command = command;
            this.waitMs = waitMs;
        }

        private static BridgeRequest parse(String line) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 1) {
                return null;
            }
            String action = parts[0].trim();
            if (action.isEmpty()) {
                return null;
            }
            if ("PING".equalsIgnoreCase(action)) {
                return new BridgeRequest("PING", "", 0);
            }
            if (!"EXEC".equalsIgnoreCase(action) || parts.length < 3) {
                return null;
            }
            int waitMs;
            try {
                waitMs = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            String command = parts[2];
            if (command == null || command.trim().isEmpty()) {
                return null;
            }
            return new BridgeRequest("EXEC", command.trim(), waitMs);
        }
    }
}
