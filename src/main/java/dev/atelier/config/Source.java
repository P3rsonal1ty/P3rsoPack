package dev.atelier.config;

/** Откуда взялось определение — для сообщений об ошибках. */
public record Source(String file, int line) {
    public static final Source UNKNOWN = new Source("?", 0);

    public ConfigException error(String message) { return new ConfigException(file, line, message); }

    @Override
    public String toString() { return file + ":" + line; }
}
