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

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of config values, rebuilt on load and on /strikerload
 * reload.
 *
 * Under Folia, payload code runs on many region threads at once. Reading
 * FileConfiguration directly from those threads while a reload is replacing it
 * is a data race, so config is read once on the main/global thread and handed
 * around as this immutable object instead.
 */
public final class Settings {

    public final boolean consumeItem;
    public final long cooldownSeconds;
    public final long creativeCooldownSeconds;
    public final double maxRange;
    public final List<String> worlds;
    public final double dropHeight;
    public final double rodDelaySeconds;

    public final double nukeRingSpacing;
    public final double nukeRingDensity;
    public final long nukeRingDelayTicks;
    public final double nukeExtraVelocity;

    public final int stabMinY;
    public final int stabMaxY;
    public final int stabSpacing;

    public final double railgunSpread;
    public final double railgunSpeed;
    public final double railgunDamage;
    public final double railgunDelaySeconds;

    public final double wolfScatter;
    public final int darknessSeconds;
    public final double stasisDelaySeconds;

    public final int maxTntPerStrike;
    public final int maxWolvesPerStrike;
    public final int impactTimeoutTicks;
    public final boolean warnTntLimit;

    private final FileConfiguration raw;

    public Settings(FileConfiguration c) {
        this.raw = c;
        consumeItem = c.getBoolean("consume-item", true);
        cooldownSeconds = c.getLong("cooldown-seconds", 60);
        creativeCooldownSeconds = c.getLong("creative-cooldown-seconds", 500);
        maxRange = c.getDouble("max-range", 200.0);
        worlds = List.copyOf(c.getStringList("worlds"));
        dropHeight = c.getDouble("drop-height", 0);
        rodDelaySeconds = c.getDouble("rod-delay-seconds", 0.75);

        nukeRingSpacing = c.getDouble("nuke.ring-spacing", 3.0);
        nukeRingDensity = c.getDouble("nuke.ring-density", 1.4);
        nukeRingDelayTicks = c.getLong("nuke.ring-delay-ticks", 0L);
        nukeExtraVelocity = c.getDouble("nuke.extra-downward-velocity", 0.0);

        stabMinY = c.getInt("stab.min-y", -63);
        stabMaxY = c.getInt("stab.max-y", 319);
        stabSpacing = c.getInt("stab.spacing", 1);

        railgunSpread = c.getDouble("railgun.spread", 0.6);
        railgunSpeed = c.getDouble("railgun.speed", 6.0);
        railgunDamage = c.getDouble("railgun.damage", 200.0);
        railgunDelaySeconds = c.getDouble("railgun.delay-seconds", 4.0);

        wolfScatter = c.getDouble("wolf.scatter", 4.0);
        darknessSeconds = c.getInt("darkness.duration-seconds", 25);
        stasisDelaySeconds = c.getDouble("stasis.delay-seconds", 0.5);

        maxTntPerStrike = c.getInt("safety.max-tnt-per-strike", 2000);
        maxWolvesPerStrike = c.getInt("safety.max-wolves-per-strike", 200);
        impactTimeoutTicks = c.getInt("safety.impact-timeout-ticks", 600);
        warnTntLimit = c.getBoolean("safety.warn-tnt-limit", true);
    }

    /** Per-payload item material and default yield stay lookup-based. */
    public String itemMaterial(String payloadId, String fallback) {
        return raw.getString("items." + payloadId + ".material", fallback);
    }

    public int defaultSize(String payloadId, int fallback) {
        return raw.getInt("defaults." + payloadId, fallback);
    }

    public static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
