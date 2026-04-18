package h_aaa.astrbotrconbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class LogCaptureManager {
    private final Plugin plugin;
    private final CopyOnWriteArrayList<CaptureSession> sessions = new CopyOnWriteArrayList<CaptureSession>();
    private Handler handler;
    private volatile boolean installed;

    public LogCaptureManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void install() {
        if (installed) {
            return;
        }
        final Filter existingFilter = Bukkit.getLogger().getFilter();
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || !isLoggable(record) || !passesLevel(record)) {
                    return;
                }
                String msg = record.getMessage();
                if (msg == null || msg.trim().isEmpty()) {
                    return;
                }
                for (CaptureSession session : sessions) {
                    session.onLog(record, msg);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        if (existingFilter != null) {
            handler.setFilter(existingFilter);
        }
        Bukkit.getLogger().addHandler(handler);
        installed = true;
        plugin.getLogger().info("LogCaptureManager installed");
    }

    public synchronized void uninstall() {
        if (!installed) {
            return;
        }
        try {
            Bukkit.getLogger().removeHandler(handler);
        } catch (Exception ignored) {
        }
        sessions.clear();
        installed = false;
    }

    private boolean passesLevel(LogRecord record) {
        String mode = plugin.getConfig().getString("log-capture.level-mode", "INFO_AND_ABOVE");
        if (mode == null) {
            mode = "INFO_AND_ABOVE";
        }
        mode = mode.trim().toUpperCase(Locale.ROOT);
        Level level = record.getLevel();
        if (level == null) {
            level = Level.INFO;
        }
        if ("INFO_ONLY".equals(mode)) {
            return Level.INFO.equals(level);
        }
        if ("WARNING_AND_ABOVE".equals(mode)) {
            return level.intValue() >= Level.WARNING.intValue();
        }
        return level.intValue() >= Level.INFO.intValue();
    }

    public CaptureSession openSession(String command, Pattern filter, int maxLines, boolean urlFirst) {
        CaptureSession session = new CaptureSession(plugin, command, filter, maxLines, urlFirst, this);
        sessions.add(session);
        return session;
    }

    void remove(CaptureSession session) {
        sessions.remove(session);
    }

    public static Pattern compilePattern(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(raw, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    public static final class CaptureSession {
        private final Plugin plugin;
        private final Pattern filter;
        private final int maxLines;
        private final boolean urlFirst;
        private final LogCaptureManager manager;
        private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<String>();
        private final List<String> keywords;
        private volatile boolean closed;

        CaptureSession(Plugin plugin, String command, Pattern filter, int maxLines, boolean urlFirst, LogCaptureManager manager) {
            this.plugin = plugin;
            this.filter = filter;
            this.maxLines = Math.max(1, maxLines);
            this.urlFirst = urlFirst;
            this.manager = manager;
            this.keywords = buildKeywords(command, plugin.getConfig().getStringList("log-capture.extra-keywords"));
        }

        void onLog(LogRecord record, String line) {
            if (closed || line == null) {
                return;
            }
            String normalized = line.replace('\r', ' ').trim();
            if (normalized.isEmpty() || !matches(record, normalized)) {
                return;
            }
            if (lines.size() >= maxLines) {
                return;
            }
            lines.add(normalized);
        }

        private boolean matches(LogRecord record, String line) {
            if (filter != null) {
                return filter.matcher(line).find();
            }

            String lowerLine = line.toLowerCase(Locale.ROOT);
            if (plugin.getConfig().getBoolean("log-capture.include-url-lines", true) && containsUrl(lowerLine)) {
                return true;
            }

            boolean relatedOnly = plugin.getConfig().getBoolean("log-capture.related-only", true);
            if (!relatedOnly) {
                return true;
            }

            for (String keyword : keywords) {
                if (keyword.length() >= 2 && lowerLine.contains(keyword)) {
                    return true;
                }
            }

            if (matchesCommonPatterns(lowerLine)) {
                return true;
            }

            String loggerName = record == null ? "" : safeLower(record.getLoggerName());
            return loggerName.contains("luckperms") || loggerName.contains("minecraft") || loggerName.contains("server");
        }

        private boolean matchesCommonPatterns(String lowerLine) {
            return lowerLine.contains("[lp]")
                    || lowerLine.contains("editor")
                    || lowerLine.contains("issued server command")
                    || lowerLine.contains("uuid")
                    || lowerLine.contains("invalid format")
                    || lowerLine.contains("unknown command")
                    || lowerLine.contains("incorrect argument")
                    || lowerLine.contains("usage:")
                    || lowerLine.contains("no player was found")
                    || lowerLine.contains("not found")
                    || lowerLine.contains("teleport")
                    || lowerLine.contains("entity")
                    || lowerLine.contains("luckperms")
                    || lowerLine.contains("successfully")
                    || lowerLine.contains("permission")
                    || lowerLine.contains("group")
                    || lowerLine.contains("user")
                    || lowerLine.contains("players online")
                    || lowerLine.contains("there are ");
        }

        public List<String> snapshot() {
            List<String> copy = new ArrayList<String>(lines);
            if (!urlFirst) {
                return copy;
            }
            List<String> urls = new ArrayList<String>();
            List<String> rest = new ArrayList<String>();
            for (String line : copy) {
                String low = line.toLowerCase(Locale.ROOT);
                if (containsUrl(low)) {
                    urls.add(line);
                } else {
                    rest.add(line);
                }
            }
            urls.addAll(rest);
            return urls;
        }

        public void close() {
            closed = true;
            manager.remove(this);
        }

        private static boolean containsUrl(String line) {
            if (line.contains("http://") || line.contains("https://")) {
                return true;
            }
            try {
                new URL(line);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        private static String safeLower(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        }

        private static List<String> buildKeywords(String command, List<String> extraKeywords) {
            List<String> result = new ArrayList<String>();
            if (command != null) {
                String[] parts = command.toLowerCase(Locale.ROOT).split("\\s+");
                for (int i = 0; i < parts.length && result.size() < 8; i++) {
                    String p = parts[i].trim();
                    if (p.length() >= 2 && !result.contains(p)) {
                        result.add(p);
                    }
                }
            }
            if (extraKeywords != null) {
                for (String keyword : extraKeywords) {
                    if (keyword == null) {
                        continue;
                    }
                    String k = keyword.trim().toLowerCase(Locale.ROOT);
                    if (k.length() >= 2 && !result.contains(k)) {
                        result.add(k);
                    }
                }
            }
            return Collections.unmodifiableList(result);
        }
    }
}
