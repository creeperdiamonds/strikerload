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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config, as JSON at config/strikerload.json.
 *
 * The Paper module gets YAML and defaults for free from Bukkit's
 * FileConfiguration. Fabric has neither, so the field initialisers below are
 * the defaults, and a missing or partial file simply leaves them in place.
 * Every value mirrors the Paper config.yml so a server owner running both
 * platforms only has to learn one set of knobs.
 */
public final class FabricSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean consumeItem = true;
    public long cooldownSeconds = 60;
    public long creativeCooldownSeconds = 500;
    public double maxRange = 200.0;

    /** Dimension ids, e.g. "minecraft:overworld". Empty means everywhere. */
    public List<String> worlds = new ArrayList<>();

    public double dropHeight = 0;
    public double rodDelaySeconds = 0.75;

    public Nuke nuke = new Nuke();
    public Stab stab = new Stab();
    public Railgun railgun = new Railgun();
    public WolfCfg wolf = new WolfCfg();
    public Darkness darkness = new Darkness();
    public Stasis stasis = new Stasis();
    public Safety safety = new Safety();

    public Map<String, Integer> defaults = defaultYields();
    public Map<String, String> items = defaultItems();

    public static final class Nuke {
        public double ringSpacing = 3.0;
        public double ringDensity = 1.4;
        public long ringDelayTicks = 0L;
        public double extraDownwardVelocity = 0.0;
    }

    public static final class Stab {
        public int minY = -63;
        public int maxY = 319;
        public int spacing = 1;
    }

    public static final class Railgun {
        public double spread = 0.6;
        public double speed = 6.0;
        public double damage = 200.0;
        public double delaySeconds = 4.0;
    }

    public static final class WolfCfg {
        public double scatter = 4.0;
    }

    public static final class Darkness {
        public int durationSeconds = 25;
    }

    public static final class Stasis {
        public double delaySeconds = 0.5;
    }

    public static final class Safety {
        public int maxTntPerStrike = 2000;
        public int maxWolvesPerStrike = 200;
        public int impactTimeoutTicks = 600;
        public boolean warnTntLimit = true;
    }

    private static Map<String, Integer> defaultYields() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Payload p : Payload.values()) m.put(p.id(), p.defaultSize());
        return m;
    }

    private static Map<String, String> defaultItems() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("nuke", "minecraft:netherite_block");
        m.put("stab", "minecraft:end_rod");
        m.put("railgun", "minecraft:spectral_arrow");
        m.put("wolf", "minecraft:bone");
        m.put("darkness", "minecraft:echo_shard");
        m.put("stasis", "minecraft:ender_pearl");
        return m;
    }

    // ---------------- lookups ----------------

    public int defaultSize(Payload payload) {
        Integer v = defaults == null ? null : defaults.get(payload.id());
        return v != null ? v : payload.defaultSize();
    }

    public String itemId(Payload payload) {
        String v = items == null ? null : items.get(payload.id());
        return v != null ? v : defaultItems().get(payload.id());
    }

    // ---------------- persistence ----------------

    /**
     * Loads the file, writing a fully-populated default file if absent.
     *
     * A malformed file is reported and ignored rather than crashing the
     * server: losing custom tuning is recoverable, a server that will not boot
     * during a fire mission is not.
     */
    public static FabricSettings load(Path path) {
        if (Files.notExists(path)) {
            FabricSettings fresh = new FabricSettings();
            fresh.save(path);
            return fresh;
        }
        try (Reader r = Files.newBufferedReader(path)) {
            FabricSettings loaded = GSON.fromJson(r, FabricSettings.class);
            if (loaded == null) throw new JsonSyntaxException("empty config");
            loaded.fillGaps();
            return loaded;
        } catch (IOException | JsonSyntaxException ex) {
            StrikerLoadMod.LOG.error("Could not read {} - using defaults. Fix the file and run /strikerload reload.",
                    path, ex);
            return new FabricSettings();
        }
    }

    /** Replaces any section a hand-edited file left out. */
    private void fillGaps() {
        if (worlds == null) worlds = new ArrayList<>();
        if (nuke == null) nuke = new Nuke();
        if (stab == null) stab = new Stab();
        if (railgun == null) railgun = new Railgun();
        if (wolf == null) wolf = new WolfCfg();
        if (darkness == null) darkness = new Darkness();
        if (stasis == null) stasis = new Stasis();
        if (safety == null) safety = new Safety();
        if (defaults == null) defaults = defaultYields();
        if (items == null) items = defaultItems();
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (IOException ex) {
            StrikerLoadMod.LOG.error("Could not write {}", path, ex);
        }
    }
}
