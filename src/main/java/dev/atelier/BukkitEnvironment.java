package dev.atelier;

import dev.atelier.model.Environment;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Проверки определений через реестры сервера. */
final class BukkitEnvironment implements Environment {
    @SuppressWarnings("deprecation")
    private static final List<String> MATERIALS = Arrays.stream(Material.values())
            .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir()).map(Material::name).toList();

    @Override
    public boolean isItemMaterial(String name) {
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() && !m.isAir();
    }

    @Override
    public Collection<String> itemMaterials() { return MATERIALS; }

    @Override
    public boolean isAttribute(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k != null && Registry.ATTRIBUTE.get(k) != null;
    }

    @Override
    public Collection<String> attributes() {
        return Registry.ATTRIBUTE.stream().map(a -> Registry.ATTRIBUTE.getKeyOrThrow(a).asString()).toList();
    }

    @Override
    public boolean isEnchantment(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k != null && RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(k) != null;
    }

    @Override
    public Collection<String> enchantments() {
        var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        return reg.stream().map(e -> reg.getKeyOrThrow(e).asString()).toList();
    }
}
