package ru.p3rso.pack.furniture;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/** «Пробное» событие ломания для плагинов защиты (WorldGuard и др.). P3rsoPack сам его игнорирует. */
public final class BreakCheckEvent extends BlockBreakEvent {
    public BreakCheckEvent(Block block, Player player) {
        super(block, player);
    }
}
