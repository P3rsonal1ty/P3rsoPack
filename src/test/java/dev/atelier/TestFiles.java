package dev.atelier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

final class TestFiles {
    static final Path DEFAULTS = Path.of("src/main/resources/defaults");

    static void copyDefaults(Path to) throws IOException {
        try (Stream<Path> s = Files.walk(DEFAULTS)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(DEFAULTS.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private TestFiles() {}
}
