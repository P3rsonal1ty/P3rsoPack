package ru.p3rso.pack.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Геометрия окна-сундука в пикселях интерфейса (как в текстуре фона): (0, 0) — левый верхний угол окна.
 * Ячейка слота — квадрат 18×18 (рамка слота), предмет рисуется на 1 px внутри.
 * <ul>
 *   <li>слоты окна: x = 7 + col·18, y = 17 + row·18</li>
 *   <li>инвентарь игрока (3 ряда): y = H − 83 + r·18, хотбар: y = H − 25, где H = 114 + rows·18 — высота окна</li>
 * </ul>
 */
public final class GuiGeometry {
    private GuiGeometry() {}

    public record Cell(int slot, int x, int y) {
        public int centerX() { return x + 9; }

        public int centerY() { return y + 9; }
    }

    public static int height(int rows) { return 114 + rows * 18; }

    /** Все ячейки: сначала окна, затем (если includePlayer) инвентаря игрока — с «виртуальными» номерами rows·9+0..35. */
    public static List<Cell> cells(int rows, boolean includePlayer) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < rows * 9; i++) out.add(new Cell(i, 7 + (i % 9) * 18, 17 + (i / 9) * 18));
        if (includePlayer) {
            int h = height(rows), base = rows * 9;
            for (int i = 0; i < 27; i++) out.add(new Cell(base + i, 7 + (i % 9) * 18, h - 83 + (i / 9) * 18));
            for (int i = 0; i < 9; i++) out.add(new Cell(base + 27 + i, 7 + i * 18, h - 25));
        }
        return out;
    }

    /** Ячейки, центр которых попадает в прямоугольник [x, x+w) × [y, y+h). */
    public static List<Integer> covered(int rows, boolean includePlayer, int x, int y, int w, int h) {
        List<Integer> out = new ArrayList<>();
        for (Cell c : cells(rows, includePlayer)) {
            if (c.centerX() >= x && c.centerX() < x + w && c.centerY() >= y && c.centerY() < y + h) out.add(c.slot());
        }
        return out;
    }

    /** Виртуальный слот → слот PlayerInventory (0–8 хотбар, 9–35 основной), или −1 для слотов окна. */
    public static int playerSlot(int rows, int virtual) {
        int i = virtual - rows * 9;
        if (i < 0 || i >= 36) return -1;
        return i < 27 ? 9 + i : i - 27;
    }

    public static int virtualSlot(int rows, int playerInvSlot) {
        if (playerInvSlot >= 9 && playerInvSlot < 36) return rows * 9 + playerInvSlot - 9;
        if (playerInvSlot >= 0 && playerInvSlot < 9) return rows * 9 + 27 + playerInvSlot;
        return -1;
    }
}
