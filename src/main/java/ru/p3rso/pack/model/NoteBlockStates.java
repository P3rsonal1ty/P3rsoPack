package ru.p3rso.pack.model;

import java.util.List;

/**
 * Отображение «вариация ↔ состояние нотного блока». Используются только состояния powered=false:
 * 27 инструментов × 25 нот = 675, вариация 0 (harp, note 0) остаётся ванильному нотному блоку.
 * Порядок инструментов совпадает с ванильным NoteBlockInstrument и с org.bukkit.Instrument (проверено для 26.3).
 */
public final class NoteBlockStates {
    public static final List<String> INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone",
            "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet", "trumpet_exposed",
            "trumpet_oxidized", "trumpet_weathered", "zombie", "skeleton", "creeper", "dragon", "wither_skeleton",
            "piglin", "custom_head");
    public static final int NOTES = 25;
    public static final int MAX_VARIATION = INSTRUMENTS.size() * NOTES - 1;

    private NoteBlockStates() {}

    public static int instrument(int variation) { return variation / NOTES; }

    public static int note(int variation) { return variation % NOTES; }

    public static int variation(int instrument, int note) { return instrument * NOTES + note; }

    /** Ключ варианта в blockstates/note_block.json. */
    public static String variantKey(int instrument, int note, boolean powered) {
        return "instrument=" + INSTRUMENTS.get(instrument) + ",note=" + note + ",powered=" + powered;
    }
}
