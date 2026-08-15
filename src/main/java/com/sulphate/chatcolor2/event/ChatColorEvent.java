package com.sulphate.chatcolor2.event;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

public class ChatColorEvent extends Event implements Cancellable {

    private final HandlerList handlerList;
    private final Player player;
    private final String message;
    private final String colour;
    private final PlayerEvent chatEvent;

    private boolean cancelled;

    public ChatColorEvent(Player player, String message, String colour, AsyncPlayerChatEvent chatEvent) {
        this(player, message, colour, (PlayerEvent) chatEvent);
    }

    public ChatColorEvent(Player player, String message, String colour, AsyncChatEvent chatEvent) {
        this(player, message, colour, (PlayerEvent) chatEvent);
    }

    public ChatColorEvent(Player player, String message, String colour, PlayerEvent chatEvent) {
        super(true);

        handlerList = new HandlerList();
        cancelled = false;

        this.player = player;
        this.message = message;
        this.colour = colour;
        this.chatEvent = chatEvent;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlerList;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    public String getColour() {
        return colour;
    }

    public PlayerEvent getChatEvent() {
        return chatEvent;
    }

}
