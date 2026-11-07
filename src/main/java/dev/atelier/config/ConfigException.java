package dev.atelier.config;

/** Ошибка конфигурации с привязкой к файлу и строке. */
public final class ConfigException extends RuntimeException {
    private final String file;
    private final int line;

    public ConfigException(String file, int line, String message) {
        super(message);
        this.file = file;
        this.line = line;
    }

    public String file() { return file; }

    public int line() { return line; }

    /** Формат «items/ruby.yml:12: сообщение». */
    public String describe() {
        return file + (line > 0 ? ":" + line : "") + ": " + getMessage();
    }
}
