package h_aaa.astrbotrconbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reads only the log content appended after a console command starts. */
final class LatestLogCapture {
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARACTERS = 65536;
    private static final Pattern ANSI_FORMATTING = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)\u00A7[0-9A-FK-ORX]");

    private final Path logFile;
    private final BasicFileAttributes initial;

    private LatestLogCapture(Path logFile, BasicFileAttributes initial) {
        this.logFile = logFile;
        this.initial = initial;
    }

    static LatestLogCapture begin(Path logFile) throws IOException {
        Path path = logFile.toAbsolutePath().normalize();
        try {
            return new LatestLogCapture(path, Files.readAttributes(path, BasicFileAttributes.class));
        } catch (NoSuchFileException ignored) {
            // The server may create latest.log while the command is running.
            return new LatestLogCapture(path, null);
        }
    }

    String readNewContent(int maxLines) throws IOException {
        BasicFileAttributes current = Files.readAttributes(logFile, BasicFileAttributes.class);
        if (!current.isRegularFile()) {
            throw new IOException("Not a regular log file: " + logFile);
        }

        long position = 0L;
        if (initial != null && current.size() >= initial.size()
                && Objects.equals(initial.fileKey(), current.fileKey())) {
            position = initial.size();
        }
        int length = (int) Math.min(current.size() - position, MAX_CAPTURE_BYTES);
        if (length == 0) {
            return "";
        }

        // Bound both the input and the output, even if a plugin logs a very long line.
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
            channel.position(position);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read only the snapshot length; later writes belong to a later window.
            }
        }
        buffer.flip();
        String content = StandardCharsets.UTF_8.decode(buffer).toString();
        content = ANSI_FORMATTING.matcher(content).replaceAll("");
        content = MINECRAFT_FORMATTING.matcher(content).replaceAll("");

        StringBuilder output = new StringBuilder();
        int lines = 0;
        for (String line : content.split("\\r\\n|[\\r\\n]")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (lines >= Math.max(1, maxLines) || output.length() >= MAX_OUTPUT_CHARACTERS) {
                break;
            }
            if (output.length() > 0) {
                output.append('\n');
            }
            int remaining = MAX_OUTPUT_CHARACTERS - output.length();
            output.append(line, 0, Math.min(line.length(), remaining));
            lines++;
        }
        return output.toString().trim();
    }
}
