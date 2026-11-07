package ru.p3rso.pack.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Собирает ошибки и предупреждения при загрузке, чтобы один битый файл не ронял всё. */
public final class Problems {
    public enum Level { ERROR, WARNING }

    public record Problem(Level level, String file, int line, String message) {
        public String describe() {
            return file + (line > 0 ? ":" + line : "") + ": " + message;
        }
    }

    private final List<Problem> list = Collections.synchronizedList(new ArrayList<>());

    public void error(String file, int line, String message) { list.add(new Problem(Level.ERROR, file, line, message)); }

    public void error(ConfigException e) { error(e.file(), e.line(), e.getMessage()); }

    public void warn(String file, int line, String message) { list.add(new Problem(Level.WARNING, file, line, message)); }

    public void error(Source src, String message) { error(src.file(), src.line(), message); }

    public void warn(Source src, String message) { warn(src.file(), src.line(), message); }

    public List<Problem> all() { synchronized (list) { return List.copyOf(list); } }

    public long errorCount() { return all().stream().filter(p -> p.level() == Level.ERROR).count(); }

    public long warningCount() { return all().stream().filter(p -> p.level() == Level.WARNING).count(); }

    public boolean hasErrors() { return errorCount() > 0; }
}
