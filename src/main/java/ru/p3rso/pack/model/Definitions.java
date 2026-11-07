package ru.p3rso.pack.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Неизменяемый снимок всех загруженных определений. Подменяется целиком при /reload. */
public record Definitions(
        Map<String, ItemDef> items,
        Map<String, GlyphDef> glyphs,
        Map<String, GuiDef> guis,
        Map<String, BlockDef> blocks,
        Map<String, FurnitureDef> furniture) {

    public static final Definitions EMPTY = new Definitions(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public Definitions {
        items = Collections.unmodifiableMap(new TreeMap<>(items));
        glyphs = Collections.unmodifiableMap(new TreeMap<>(glyphs));
        guis = Collections.unmodifiableMap(new TreeMap<>(guis));
        blocks = Collections.unmodifiableMap(new TreeMap<>(blocks));
        furniture = Collections.unmodifiableMap(new TreeMap<>(furniture));
    }
}
