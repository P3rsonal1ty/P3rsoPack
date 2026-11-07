package dev.atelier.model;

import dev.atelier.config.Source;

/**
 * Глиф шрифта — картинка, вставляемая в текст (заголовок GUI, action bar, чат).
 * {@code texture} — ключ текстуры (файл {@code assets/<ns>/textures/<path>.png}).
 * {@code advance} — на сколько пикселей сдвигается курсор после глифа (считается из PNG при генерации пака).
 */
public record GlyphDef(String id, Source source, String texture, int height, int ascent, char character, int advance) {
    public GlyphDef withAdvance(int adv) { return new GlyphDef(id, source, texture, height, ascent, character, adv); }

    public GlyphDef withChar(char c) { return new GlyphDef(id, source, texture, height, ascent, c, advance); }
}
