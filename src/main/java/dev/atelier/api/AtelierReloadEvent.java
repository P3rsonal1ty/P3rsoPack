package dev.atelier.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается после каждой (пере)загрузки Atelier — можно пересоздать свои закэшированные предметы. */
public final class AtelierReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public AtelierReloadEvent(boolean async) { super(async); }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
