package h_aaa.astrbotrconbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class AstrBotRconBridge extends JavaPlugin {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock commandLock = new ReentrantLock(true);
    private ExecutorService acceptPool;
    private ExecutorService workerPool;
    private ServerSocket serverSocket;
    private CommandScheduler commandScheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        commandScheduler = new CommandScheduler(this);
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
        final int effectiveWait = resolveWaitMs(request.waitMs);
        final boolean captureEnabled = getConfig().getBoolean("log-capture.enabled", true);
        final int maxLines = Math.max(1, getConfig().getInt("log-capture.max-lines", 80));
        String configuredPath = getConfig().getString("log-capture.file-path", "logs/latest.log");
        final Path logFile = Paths.get(configuredPath == null || configuredPath.trim().isEmpty()
                ? "logs/latest.log" : configuredPath.trim());

        // Keep bridge commands and their capture windows from overlapping.
        if (!commandLock.tryLock(10, TimeUnit.SECONDS)) {
            return new ExecResult(false, "EXEC_TIMEOUT", "Waiting for another bridge command");
        }
        FutureTask<CommandExecution> task = new FutureTask<>(() -> {
            if (!running.get()) {
                throw new IllegalStateException("Bridge is disabled");
            }
            // Snapshot on the server thread immediately before dispatch, not while queued.
            LatestLogCapture capture = captureEnabled ? LatestLogCapture.begin(logFile) : null;
            ExecResult result;
            try {
                boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), request.command);
                result = new ExecResult(accepted, accepted ? "EXEC_OK" : "EXEC_REJECTED", "");
            } catch (Exception e) {
                result = new ExecResult(false,
                        getConfig().getString("messages.internal-error", "INTERNAL_ERROR"),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            return new CommandExecution(capture, result);
        });
        try {
            commandScheduler.execute(task);
            CommandExecution execution = task.get(10, TimeUnit.SECONDS);
            if (execution.capture == null) {
                return execution.result;
            }

            // Only the socket worker waits and reads the file; the server thread is free.
            Thread.sleep(effectiveWait);
            String output;
            try {
                output = execution.capture.readNewContent(maxLines);
            } catch (IOException e) {
                output = "Log read failed: " + e.getMessage();
            }
            if (!execution.result.output.isEmpty()) {
                output = output.isEmpty() ? execution.result.output
                        : output + '\n' + execution.result.output;
            }
            return new ExecResult(execution.result.success, execution.result.code, output);
        } catch (TimeoutException e) {
            return new ExecResult(false, "EXEC_TIMEOUT", "");
        } finally {
            // A command still waiting for a server tick must not execute after timeout.
            task.cancel(false);
            commandLock.unlock();
        }
    }

    private int resolveWaitMs(int requestWaitMs) {
        int defaultWait = Math.max(0, getConfig().getInt("log-capture.default-wait-ms", 1000));
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String base64Encode(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(Charset.forName("UTF-8")));
    }

    private static final class CommandExecution {
        private final LatestLogCapture capture;
        private final ExecResult result;

        private CommandExecution(LatestLogCapture capture, ExecResult result) {
            this.capture = capture;
            this.result = result;
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
