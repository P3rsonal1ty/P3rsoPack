package ru.p3rso.pack.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Вызывается после каждой (пере)загрузки P3rsoPack — можно пересоздать свои закэшированные предметы. */
public final class P3rsoPackReloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public P3rsoPackReloadEvent(boolean async) { super(async); }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
