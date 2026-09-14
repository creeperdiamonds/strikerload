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
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * Payload items, and reading a payload back off a held stack.
 *
 * Bukkit stores this in a PersistentDataContainer keyed by NamespacedKey.
 * The equivalent here is the CUSTOM_DATA component, whose tag is arbitrary NBT
 * that vanilla preserves and never interprets. Keys are prefixed so they
 * cannot collide with another mod writing to the same component.
 */
public final class PayloadItems {

    private static final String KEY_PAYLOAD = "strikerload_payload";
    private static final String KEY_SIZE = "strikerload_size";

    private PayloadItems() {}

    public static ItemStack create(Payload payload, int size, FabricSettings settings) {
        ItemStack stack = new ItemStack(resolveItem(settings.itemId(payload)));

        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal(payload.display() + " Shot").withStyle(ChatFormatting.RED));

        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to paint a target.").withStyle(ChatFormatting.GRAY),
                Component.literal("Yield: " + size).withStyle(ChatFormatting.DARK_GRAY))));

        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);

        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_PAYLOAD, payload.name());
        tag.putInt(KEY_SIZE, size);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        return stack;
    }

    /** The payload this stack represents, or null if it is not one of ours. */
    public static Payload read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        return Payload.parse(data.copyTag().getStringOr(KEY_PAYLOAD, ""));
    }

    /** The stored yield, falling back to the configured default. */
    public static int readSize(ItemStack stack, Payload payload, FabricSettings settings) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        int fallback = settings.defaultSize(payload);
        if (data == null || data.isEmpty()) return fallback;
        int stored = data.copyTag().getIntOr(KEY_SIZE, fallback);
        return stored > 0 ? stored : fallback;
    }

    /**
     * Unknown item ids fall back to netherite ingot rather than failing the
     * strike, matching the Paper module's Material.matchMaterial fallback.
     */
    private static Item resolveItem(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            StrikerLoadMod.LOG.warn("Unparseable item id '{}' in config; using netherite ingot.", id);
            return Items.NETHERITE_INGOT;
        }
        return BuiltInRegistries.ITEM.getOptional(parsed).orElseGet(() -> {
            StrikerLoadMod.LOG.warn("Unknown item id '{}' in config; using netherite ingot.", id);
            return Items.NETHERITE_INGOT;
        });
    }
}
