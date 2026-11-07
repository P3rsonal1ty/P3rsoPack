package ru.p3rso.pack.model;

import ru.p3rso.pack.config.Source;

import java.util.List;
import java.util.Map;

/** Окно (GUI) из guis/*.yml. */
public record GuiDef(
        String id,
        Source source,
        String title,
        int rows,
        String background,
        int backgroundOffset,
        int titleOffset,
        List<GuiItem> items,
        List<Action> openActions,
        String command,
        String permission,
        boolean hidePlayerInventory) {

    /** Слоты нижней части окна (инвентарь игрока): rows·9 + 0..26 — три ряда сверху вниз, rows·9 + 27..35 — хотбар. */
    public int topSize() { return rows * 9; }

    public int totalSlots() { return hidePlayerInventory ? rows * 9 + 36 : rows * 9; }

    /**
     * Элемент окна. {@code item} — id кастомного предмета или null; {@code material} — ванильный материал.
     * {@code actions}: ключ — тип клика (any, left, right, shift_left, shift_right, middle, drop), значение — действия.
     */
    public record GuiItem(String key, Source source, List<Integer> slots, String item, String material, int amount,
                          String name, List<String> lore, String itemModel, String tooltipStyle,
                          Map<String, List<Action>> actions) {}

    /** Действие по клику. */
    public record Action(Type type, String value, Source source) {
        public enum Type { COMMAND, CONSOLE, MESSAGE, ACTIONBAR, OPEN, CLOSE, SOUND, GIVE, REFRESH }
    }
}
