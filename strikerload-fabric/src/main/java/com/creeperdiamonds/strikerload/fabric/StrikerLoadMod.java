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

package com.creeperdiamonds.strikerload.fabric;

import com.creeperdiamonds.strikerload.core.CooldownTracker;
import com.creeperdiamonds.strikerload.core.Payload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class StrikerLoadMod implements ModInitializer {

    public static final String MOD_ID = "strikerload";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private final CooldownTracker cooldowns = new CooldownTracker();
    private final TickScheduler scheduler = new TickScheduler();
    private final PayloadDelivery delivery = new PayloadDelivery(this);

    private Path configPath;
    private FabricSettings settings = new FabricSettings();

    public FabricSettings settings() {
        return settings;
    }

    public TickScheduler scheduler() {
        return scheduler;
    }

    public PayloadDelivery delivery() {
        return delivery;
    }

    @Override
    public void onInitialize() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("strikerload.json");
        settings = FabricSettings.load(configPath);

        ServerTickEvents.END_SERVER_TICK.register(server -> scheduler.tick());

        // Air and block right-clicks arrive as two separate events here, unlike
        // Bukkit's single PlayerInteractEvent with an Action enum.
        UseItemCallback.EVENT.register((player, level, hand) ->
                onRightClick(player, level, hand));
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                onRightClick(player, level, hand));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                StrikeCommand.register(dispatcher, this));

        LOG.info("StrikerLoad enabled.");
        if (settings.safety.warnTntLimit) {
            LOG.warn("Large payloads spawn hundreds of TNT entities per tick.");
            LOG.warn("Expect tick lag on wide nukes; lower safety.maxTntPerStrike if it bites.");
        }
    }

    public void reload() {
        settings = FabricSettings.load(configPath);
        // Pending work refers to the old settings snapshot, so drop it rather
        // than let a half-configured barrage finish under new values.
        scheduler.clear();
        cooldowns.clearAll();
    }

    // ==================== trigger ====================

    private InteractionResult onRightClick(Player player, Level level, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer shooter)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        ItemStack held = shooter.getItemInHand(hand);
        Payload payload = PayloadItems.read(held);
        if (payload == null) return InteractionResult.PASS;

        if (!mayUse(shooter)) {
            send(shooter, "You are not cleared for that payload.", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
        }

        if (!settings.worlds.isEmpty()
                && !settings.worlds.contains(serverLevel.dimension().identifier().toString())) {
            send(shooter, "No satellite coverage in this world.", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
        }

        if (isOnCooldown(shooter)) return InteractionResult.SUCCESS;

        Vec3 target = resolveTarget(shooter, serverLevel);
        if (target == null) {
            send(shooter, "No valid target in range.", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
        }

        cooldowns.mark(shooter.getUUID(), System.currentTimeMillis());

        if (settings.consumeItem && !isCreative(shooter)) {
            held.shrink(1);
        }

        send(shooter, "Fire mission: " + payload.display() + " inbound.", ChatFormatting.GREEN);
        delivery().fire(payload, shooter, serverLevel, target,
                PayloadItems.readSize(held, payload, settings));

        return InteractionResult.SUCCESS;
    }

    /**
     * Vanilla has no permission nodes, only levels, so the per-payload
     * strikerload.use.* split from the Bukkit build cannot be reproduced
     * one-for-one. Gate is the same level vanilla uses for /summon and
     * /setblock, which is the closest honest equivalent.
     */
    private boolean mayUse(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private boolean isCreative(ServerPlayer player) {
        return player.gameMode() == GameType.CREATIVE;
    }

    private boolean isOnCooldown(ServerPlayer player) {
        long seconds = isCreative(player)
                ? settings.creativeCooldownSeconds
                : settings.cooldownSeconds;

        long left = cooldowns.remaining(player.getUUID(), seconds * 1000L, System.currentTimeMillis());
        if (left > 0) {
            send(player, "Cannon recharging: " + (left / 1000 + 1) + "s", ChatFormatting.GOLD);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.NOTE_BLOCK_BASS, SoundSource.MASTER, 1f, 0.6f);
            return true;
        }
        return false;
    }

    /** Where the player is looking, capped at maxRange. */
    private Vec3 resolveTarget(ServerPlayer player, ServerLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(settings.maxRange));

        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        if (hit.getType() == HitResult.Type.MISS) return null;
        return hit.getLocation();
    }

    private void send(ServerPlayer player, String message, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(message).withStyle(colour));
    }
}
