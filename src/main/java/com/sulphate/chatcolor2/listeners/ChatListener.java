package com.sulphate.chatcolor2.listeners;

import com.sulphate.chatcolor2.data.PlayerData;
import com.sulphate.chatcolor2.event.ChatColorEvent;
import com.sulphate.chatcolor2.commands.Setting;
import com.sulphate.chatcolor2.data.PlayerDataStore;
import com.sulphate.chatcolor2.managers.ConfigsManager;
import com.sulphate.chatcolor2.managers.GroupColoursManager;
import com.sulphate.chatcolor2.utils.Config;
import com.sulphate.chatcolor2.utils.GeneralUtils;
import com.sulphate.chatcolor2.utils.Reloadable;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ChatListener implements Listener, Reloadable {

    private static final Pattern SYMBOLS_REGEX = Pattern.compile("^[!^\"£$%*()\\[\\]{}'#@~;:,./<>?\\\\|\\-_=+]+[^!^\"£$%&*()\\[\\]{}'#@~;:,./<>?\\\\|\\-_=+]+");

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final ConfigsManager configsManager;
    private final GeneralUtils generalUtils;
    private final GroupColoursManager groupColoursManager;
    private final PlayerDataStore dataStore;

    private YamlConfiguration mainConfig;
    private final Set<Player> pausedPlayers;

    public ChatListener(ConfigsManager configsManager, GeneralUtils generalUtils, GroupColoursManager groupColoursManager, PlayerDataStore dataStore) {
        this.configsManager = configsManager;
        this.generalUtils = generalUtils;
        this.groupColoursManager = groupColoursManager;
        this.dataStore = dataStore;

        pausedPlayers = new HashSet<>();

        reload();
    }

    public void reload() {
        mainConfig = configsManager.getConfig(Config.MAIN_CONFIG);
    }

    public boolean togglePause(Player player) {
        if (pausedPlayers.contains(player)) {
            pausedPlayers.remove(player);
            return false;
        }
        else {
            pausedPlayers.add(player);
            return true;
        }
    }

    // Legacy chat path (used as a fallback on non-Paper servers).
    public void onEvent(AsyncPlayerChatEvent event) {
        handleChat(event.getPlayer(), event.getMessage(), event.isCancelled(), event::setMessage, event);
    }

    // Modern chat path (Paper) - works with components, ensuring colours work on 1.26.1.2+.
    public void onModernEvent(AsyncChatEvent event) {
        String message = LEGACY_SERIALIZER.serialize(event.message());

        debugLog("raw: " + showCodes(message));

        handleChat(event.getPlayer(), message, event.isCancelled(), coloured -> {
            debugLog("colourised: " + showCodes(coloured));

            // LPC (LuckPerms Chat Formatter) rebuilds messages from their plain text, so component colours
            // set here are discarded. When LPC is present and the player can use colours in it, inject the
            // colour as literal legacy codes instead: they survive plain-text serialization and are rendered
            // by LPC's own legacy colour parser. Hex (§x) colours cannot be represented there, so they keep
            // using component colours.
            if (shouldInjectLegacyCodes(event.getPlayer(), coloured)) {
                event.message(Component.text(coloured.replace('§', '&')));
                debugLog("final: legacy codes injected for LPC");
            }
            else {
                event.message(LEGACY_SERIALIZER.deserialize(coloured));
                debugLog("final: component colours set");
            }
        }, event);
    }

    private static boolean shouldInjectLegacyCodes(Player player, String coloured) {
        if (coloured == null || coloured.indexOf('§') == -1) {
            return false;
        }

        // LPC's legacy parser cannot render hex (§x) colours.
        if (coloured.contains("§x")) {
            return false;
        }

        org.bukkit.plugin.Plugin lpc = Bukkit.getPluginManager().getPlugin("LPC");

        if (lpc == null || !lpc.isEnabled()) {
            return false;
        }

        return player.hasPermission("lpc.chatcolor");
    }

    private void debugLog(String message) {
        if (mainConfig.getBoolean("settings.debug", false)) {
            Bukkit.getConsoleSender().sendMessage("[ChatColor2 debug] " + message);
        }
    }

    private static String showCodes(String message) {
        return message.replace('§', '&');
    }

    private void handleChat(Player player, String message, boolean cancelled, Consumer<String> messageSetter, PlayerEvent event) {
        UUID uuid = player.getUniqueId();

        if (cancelled || checkHasSymbolPrefix(message) || pausedPlayers.contains(player)) {
            return;
        }

        boolean defaultColourEnabled = mainConfig.getBoolean(Setting.DEFAULT_COLOR_ENABLED.getConfigPath());

        // If they chat before the data store has had a chance to load/fail their data, use the default colour, or if
        // not enabled, do nothing at all.
        if (dataStore.getColour(uuid) == null) {
            if (defaultColourEnabled) {
                String defaultColor = mainConfig.getString("default.color");
                colourAndModify(player, message, defaultColor, messageSetter, event);
            }

            return;
        }

        // Check default colour.
        if (defaultColourEnabled) {
            generalUtils.checkDefault(uuid);
        }

        // Check if the player should have their colour reset.
        if (mainConfig.getBoolean(Setting.REMOVE_INACCESSIBLE_COLORS.getConfigPath())) {
            String colour = dataStore.getColour(uuid);
            String colourName = dataStore.getPlayerData(uuid).getColourName();

            if (!hasDefaultOrGroupColour(player, colour)) {
                removeInaccessibleColour(player, colourName);
            }
        }

        message = checkColourCodes(message, player);

        // Check if they have a group colour, and if it should be enforced.
        String groupColour = groupColoursManager.getGroupColourForPlayer(player);
        String colour = dataStore.getColour(uuid);

        if (groupColour != null) {
            // If it should be forced, set it so.
            if (mainConfig.getBoolean(Setting.FORCE_GROUP_COLORS.getConfigPath())) {
                colour = groupColour;
            }
        }

        colourAndModify(player, message, colour, messageSetter, event);
    }

    private boolean checkHasSymbolPrefix(String message) {
        boolean checkSymbols = mainConfig.getBoolean(Setting.IGNORE_SYMBOL_PREFIXES.getConfigPath());

        if (checkSymbols) {
            return SYMBOLS_REGEX.matcher(message).matches();
        }
        else {
            return false;
        }
    }

    private void colourAndModify(Player player, String message, String colour, Consumer<String> messageSetter, PlayerEvent event) {
        if (GeneralUtils.isDifferentWhenColourised(message)) {
            boolean override = mainConfig.getBoolean(Setting.COLOR_OVERRIDE.getConfigPath());

            if (override) {
                while (GeneralUtils.isDifferentWhenColourised(message)) {
                    // Strip the colour from the message.
                    message = org.bukkit.ChatColor.stripColor(GeneralUtils.colourise(message));
                }

                messageSetter.accept(message);
            }
            else {
                messageSetter.accept(GeneralUtils.colourise(message));
            }
        }
        else {
            boolean eventSucceeded;

            try {
                eventSucceeded = fireEvent(player, message, colour, event);
            }
            catch (Exception ex) {
                eventSucceeded = false;
            }

            if (eventSucceeded) {
                messageSetter.accept(generalUtils.colouriseMessage(colour, message, false));
            }
        }
    }

    private String checkColourCodes(String message, Player player) {
        // If their message contains &, check they have permissions for it, or strip the colour.
        if (!player.hasPermission("chatcolor.use-color-codes")) {
            // A player reported using '&&a' for example, would bypass this. So, loop until it's not different.
            while (GeneralUtils.isDifferentWhenColourised(message)) {
                // Strip the colour from the message.
                message = org.bukkit.ChatColor.stripColor(GeneralUtils.colourise(message));
            }
        }

        if (!player.hasPermission("chatcolor.use-hex-codes") && GeneralUtils.containsHexColour(message, true)) {
            while (GeneralUtils.isDifferentWhenColourised(message)) {
                message = org.bukkit.ChatColor.stripColor(GeneralUtils.colourise(message));
            }
        }

        return message;
    }

    private boolean hasDefaultOrGroupColour(Player player, String colour) {
        // Check if they are using the default colour (no permission needed).
        if (mainConfig.getBoolean(Setting.DEFAULT_COLOR_ENABLED.getConfigPath())) {
            String defaultColour = mainConfig.getString("default.color");

            if (colour.equals(defaultColour)) {
                return true;
            }
        }

        // Check if they are using a group colour (no permission needed).
        String groupColour = groupColoursManager.getGroupColourForPlayer(player);

        if (groupColour != null) {
            if (colour.equals(groupColour)) {
                return true;
            }
        }

        return false;
    }

    private void removeInaccessibleColour(Player player, String colourName) {
        PlayerData data = dataStore.getPlayerData(player.getUniqueId());
        UUID uuid = player.getUniqueId();

        String permission = "";
        boolean shouldRemove = false;

        if (colourName.startsWith("%")) {
            permission = "chatcolor.custom." + colourName.substring(1);
        }
        else if (colourName.startsWith("#")) {
            if (player.hasPermission("chatcolor.use-hex-codes")) {
                shouldRemove = true;
            }
            else {
                permission = "chatcolor.color." + colourName.substring(1);
            }
        }
        else if (colourName.startsWith("u") || colourName.startsWith("g")) {
            permission = "chatcolor.special";
        }
        else {
            permission = "chatcolor.color." + colourName;
        }

        Set<Character> mods = data.getModifiers();

        for (char mod : mods) {
            if (!player.hasPermission("chatcolor.modifier." + mod)) {
                mods.remove(mod);
            }
        }

        if (shouldRemove || !player.hasPermission(permission)) {
            if (mainConfig.getBoolean(Setting.DEFAULT_COLOR_ENABLED.getConfigPath())) {
                dataStore.setColour(uuid, mainConfig.getString("default.color"));
            }
            else {
                dataStore.setColour(uuid, "");
            }
        }

        for (char mod : mods) {
            data.addModifier(mod);
        }
    }

    private boolean fireEvent(Player player, String message, String colour, PlayerEvent chatEvent) {
        ChatColorEvent chatColorEvent = new ChatColorEvent(player, message, colour, chatEvent);
        Bukkit.getPluginManager().callEvent(chatColorEvent);

        return !chatColorEvent.isCancelled();
    }

}
