/*
 * StrikerLoad - orbital strike cannon for Minecraft servers.
 * Copyright (C) 2026  creeperdiamonds
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.creeperdiamonds.strikerload.bukkit;

import com.creeperdiamonds.strikerload.core.CooldownTracker;
import com.creeperdiamonds.strikerload.core.Payload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class StrikerLoadPlugin extends JavaPlugin implements Listener, TabCompleter {

    private NamespacedKey payloadKey;
    private NamespacedKey sizeKey;
    private PayloadService payloads;
    private final CooldownTracker cooldowns = new CooldownTracker();

    /** Volatile so region threads always observe the latest reload. */
    private volatile Settings settings;

    public Settings settings() {
        return settings;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        payloadKey = new NamespacedKey(this, "payload");
        sizeKey = new NamespacedKey(this, "payload_size");
        payloads = new PayloadService(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("strikerload")).setTabCompleter(this);

        getLogger().info("StrikerLoad enabled on " + Bukkit.getVersion());
        if (settings.warnTntLimit) {
            getLogger().warning("Large payloads spawn hundreds of TNT entities per tick.");
            getLogger().warning("Raise 'max-tnt-per-tick' in spigot.yml or rings will silently fizzle.");
        }
    }

    @Override
    public void onDisable() {
        cooldowns.clearAll();
    }

    // ==================== items ====================

    public ItemStack createPayloadItem(Payload payload, int size) {
        Material mat = Material.matchMaterial(
                settings.itemMaterial(payload.id(), "NETHERITE_INGOT"));
        if (mat == null) mat = Material.NETHERITE_INGOT;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(payload.display() + " Shot")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to paint a target.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Yield: " + size, NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(payloadKey, PersistentDataType.STRING, payload.name());
        pdc.set(sizeKey, PersistentDataType.INTEGER, size);
        item.setItemMeta(meta);
        return item;
    }

    private Payload readPayload(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return Payload.parse(item.getItemMeta().getPersistentDataContainer()
                .get(payloadKey, PersistentDataType.STRING));
    }

    private int readSize(ItemStack item, Payload payload) {
        Integer stored = item.getItemMeta().getPersistentDataContainer()
                .get(sizeKey, PersistentDataType.INTEGER);
        return stored != null ? stored : defaultSize(payload);
    }

    public int defaultSize(Payload payload) {
        return settings.defaultSize(payload.id(), payload.defaultSize());
    }

    // ==================== trigger ====================

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = event.getItem();
        Payload payload = readPayload(held);
        if (payload == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!player.hasPermission("strikerload.use." + payload.id())
                && !player.hasPermission("strikerload.use.*")) {
            player.sendMessage(Component.text("You are not cleared for that payload.", NamedTextColor.RED));
            return;
        }

        List<String> worlds = settings.worlds;
        if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName())) {
            player.sendMessage(Component.text("No satellite coverage in this world.", NamedTextColor.RED));
            return;
        }

        if (isOnCooldown(player)) return;

        Location target = resolveTarget(player);
        if (target == null) {
            player.sendMessage(Component.text("No valid target in range.", NamedTextColor.RED));
            return;
        }

        cooldowns.mark(player.getUniqueId(), System.currentTimeMillis());

        if (settings.consumeItem && player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }

        player.sendMessage(Component.text("Fire mission: " + payload.display() + " inbound.",
                NamedTextColor.GREEN));
        payloads.fire(payload, player, target, readSize(held, payload));
    }

    private boolean isOnCooldown(Player player) {
        if (player.hasPermission("strikerload.bypass.cooldown")) return false;

        long seconds = player.getGameMode() == GameMode.CREATIVE
                ? settings.creativeCooldownSeconds
                : settings.cooldownSeconds;

        long left = cooldowns.remaining(player.getUniqueId(), seconds * 1000L, System.currentTimeMillis());
        if (left > 0) {
            player.sendMessage(Component.text("Cannon recharging: " + (left / 1000 + 1) + "s",
                    NamedTextColor.GOLD));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return true;
        }
        return false;
    }

    private Location resolveTarget(Player player) {
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                settings.maxRange,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitPosition() == null) return null;
        return hit.getHitPosition().toLocation(player.getWorld());
    }

    // ==================== commands ====================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text(
                    "/strikerload give <payload> [player] [size] | strike <payload> <x> <y> <z> [world] [size] | reload",
                    NamedTextColor.GRAY));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                settings = new Settings(getConfig());
                sender.sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
            }
            case "give" -> handleGive(sender, args);
            case "strike" -> handleStrike(sender, args);
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /strikerload give <payload> [player] [size]",
                    NamedTextColor.RED));
            return;
        }
        Payload payload = Payload.parse(args[1]);
        if (payload == null) {
            sender.sendMessage(Component.text("Unknown payload: " + args[1], NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Console must name a player.", NamedTextColor.RED));
            return;
        }

        int size = args.length >= 4 ? parseOr(args[3], defaultSize(payload)) : defaultSize(payload);
        target.getInventory().addItem(createPayloadItem(payload, size));
        sender.sendMessage(Component.text(payload.display() + " issued to " + target.getName(),
                NamedTextColor.GREEN));
    }

    private void handleStrike(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text(
                    "Usage: /strikerload strike <payload> <x> <y> <z> [world] [size]", NamedTextColor.RED));
            return;
        }
        Payload payload = Payload.parse(args[1]);
        if (payload == null) {
            sender.sendMessage(Component.text("Unknown payload.", NamedTextColor.RED));
            return;
        }

        World world;
        if (args.length >= 6) world = Bukkit.getWorld(args[5]);
        else if (sender instanceof Player p) world = p.getWorld();
        else world = Bukkit.getWorlds().get(0);

        if (world == null) {
            sender.sendMessage(Component.text("Unknown world.", NamedTextColor.RED));
            return;
        }

        Location loc;
        try {
            loc = new Location(world, Double.parseDouble(args[2]),
                    Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Coordinates must be numbers.", NamedTextColor.RED));
            return;
        }

        int size = args.length >= 7 ? parseOr(args[6], defaultSize(payload)) : defaultSize(payload);
        Player attributed = sender instanceof Player p ? p : null;
        if (attributed == null) {
            sender.sendMessage(Component.text(
                    "Console strike is unattributed - region plugins may not be able to block it.",
                    NamedTextColor.GOLD));
        }
        payloads.fire(payload, attributed, loc, size);
        sender.sendMessage(Component.text("Strike dispatched.", NamedTextColor.GREEN));
    }

    private int parseOr(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("give", "strike", "reload");
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return Arrays.stream(Payload.values()).map(Payload::id).toList();
        }
        return List.of();
    }
}
