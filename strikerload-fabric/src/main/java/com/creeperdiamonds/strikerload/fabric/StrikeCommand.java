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

import com.creeperdiamonds.strikerload.core.Payload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Collection;

/**
 * /strikerload as a Brigadier tree.
 *
 * The Paper module parses a String[] by hand because that is what Bukkit
 * hands it. Brigadier does the parsing, so coordinates, players and yields
 * arrive already validated and tab-completion comes for free.
 *
 * Bukkit's per-payload permission nodes (strikerload.use.nuke and friends)
 * have no vanilla equivalent - see StrikerLoadMod#mayUse.
 */
public final class StrikeCommand {

    private static final SuggestionProvider<CommandSourceStack> PAYLOADS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    Arrays.stream(Payload.values()).map(Payload::id).toList(), builder);

    private StrikeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, StrikerLoadMod mod) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("strikerload")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))

                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            mod.reload();
                            ok(ctx, "Config reloaded.");
                            return 1;
                        }))

                .then(Commands.literal("give")
                        .then(Commands.argument("payload", StringArgumentType.word())
                                .suggests(PAYLOADS)
                                .executes(ctx -> give(ctx, mod, null, -1))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> give(ctx, mod,
                                                EntityArgument.getPlayers(ctx, "targets"), -1))
                                        .then(Commands.argument("size", IntegerArgumentType.integer(1))
                                                .executes(ctx -> give(ctx, mod,
                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                        IntegerArgumentType.getInteger(ctx, "size")))))))

                .then(Commands.literal("strike")
                        .then(Commands.argument("payload", StringArgumentType.word())
                                .suggests(PAYLOADS)
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> strike(ctx, mod, -1))
                                        .then(Commands.argument("size", IntegerArgumentType.integer(1))
                                                .executes(ctx -> strike(ctx, mod,
                                                        IntegerArgumentType.getInteger(ctx, "size")))))));

        dispatcher.register(root);
        // Matches the Bukkit plugin.yml aliases.
        dispatcher.register(Commands.literal("sl").requires(
                src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .redirect(dispatcher.getRoot().getChild("strikerload")));
    }

    private static int give(CommandContext<CommandSourceStack> ctx, StrikerLoadMod mod,
                            Collection<ServerPlayer> explicit, int size) {
        Payload payload = parsePayload(ctx);
        if (payload == null) return 0;

        Collection<ServerPlayer> targets = explicit;
        if (targets == null) {
            ServerPlayer self = ctx.getSource().getPlayer();
            if (self == null) {
                fail(ctx, "Console must name a player.");
                return 0;
            }
            targets = java.util.List.of(self);
        }

        int yield = size > 0 ? size : mod.settings().defaultSize(payload);
        for (ServerPlayer target : targets) {
            target.getInventory().placeItemBackInInventory(
                    PayloadItems.create(payload, yield, mod.settings()));
        }
        ok(ctx, payload.display() + " issued to " + targets.size() + " player(s).");
        return targets.size();
    }

    private static int strike(CommandContext<CommandSourceStack> ctx, StrikerLoadMod mod, int size) {
        Payload payload = parsePayload(ctx);
        if (payload == null) return 0;

        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        ServerLevel level = ctx.getSource().getLevel();
        ServerPlayer attributed = ctx.getSource().getPlayer();

        if (payload == Payload.STASIS && attributed == null) {
            fail(ctx, "Stasis needs a player to pull - run it as one.");
            return 0;
        }
        if (attributed == null) {
            warn(ctx, "Console strike is unattributed - protection mods may not be able to block it.");
        }

        int yield = size > 0 ? size : mod.settings().defaultSize(payload);
        mod.delivery().fire(payload, attributed, level, pos, yield);
        ok(ctx, "Strike dispatched.");
        return 1;
    }

    private static Payload parsePayload(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "payload");
        Payload payload = Payload.parse(raw);
        if (payload == null) fail(ctx, "Unknown payload: " + raw);
        return payload;
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(
                () -> Component.literal(msg).withStyle(ChatFormatting.GREEN), true);
    }

    private static void warn(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(
                () -> Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg).withStyle(ChatFormatting.RED));
    }
}
