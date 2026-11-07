package ru.p3rso.pack.model;

import java.util.Collection;
import java.util.List;

/**
 * Проверки, зависящие от сервера (материалы, атрибуты, зачарования). В плагине реализуется через
 * реестры Bukkit, в тестах — заглушкой.
 */
public interface Environment {
    boolean isItemMaterial(String name);

    Collection<String> itemMaterials();

    boolean isAttribute(String key);

    Collection<String> attributes();

    boolean isEnchantment(String key);

    Collection<String> enchantments();

    /** Пропускает всё — для тестов генератора пака. */
    static Environment permissive() {
        return new Environment() {
            public boolean isItemMaterial(String name) { return true; }
            public Collection<String> itemMaterials() { return List.of(); }
            public boolean isAttribute(String key) { return true; }
            public Collection<String> attributes() { return List.of(); }
            public boolean isEnchantment(String key) { return true; }
            public Collection<String> enchantments() { return List.of(); }
        };
    }
}
