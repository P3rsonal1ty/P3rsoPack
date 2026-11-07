package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Source;

import java.util.List;

/** Мебель: item_display с моделью + хитбокс (interaction или barrier-блоки) + сидения. */
public record FurnitureDef(
        String id,
        Source source,
        ModelSpec model,
        String displayTransform,
        float scale,
        float[] translation,
        Hitbox hitbox,
        List<Seat> seats,
        int rotationStep,
        boolean dropItem,
        String placeSound,
        String breakSound,
        Integer brightness) {

    /** Хитбокс: INTERACTION — сущность interaction шириной width и высотой height; BARRIER — твёрдые блоки-барьеры. */
    public record Hitbox(Type type, float width, float height, List<int[]> barriers) {
        public enum Type { INTERACTION, BARRIER, NONE }
    }

    /** Сидение: смещение от центра мебели (с учётом поворота). */
    public record Seat(float x, float y, float z) {}
}
