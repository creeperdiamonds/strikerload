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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Minimal tick-driven scheduler.
 *
 * Bukkit hands plugins a scheduler; Fabric does not, so the delays the Paper
 * module gets from BukkitScheduler (ring stagger, rod fuse, railgun windup,
 * impact watching) are driven from END_SERVER_TICK instead.
 *
 * Everything here runs on the server thread, so no synchronisation is needed -
 * the opposite of the Paper module, which has to assume Folia region threads.
 * Callbacks are collected into a scratch list before running so a task that
 * schedules another task cannot mutate the list mid-iteration.
 */
public final class TickScheduler {

    private record Delayed(long dueTick, Runnable action) {}

    private final List<Delayed> delayed = new ArrayList<>();
    private final List<BooleanSupplier> perTick = new ArrayList<>();
    private final List<Delayed> dueScratch = new ArrayList<>();
    private final List<BooleanSupplier> tickScratch = new ArrayList<>();

    private long now;

    /** Runs {@code action} after {@code delayTicks}; 0 or less means next tick. */
    public void runLater(long delayTicks, Runnable action) {
        delayed.add(new Delayed(now + Math.max(1L, delayTicks), action));
    }

    /**
     * Runs {@code action} every tick until it returns false.
     *
     * Used for the impact watchers: each charge polls its own state and
     * cancels itself on landing, so there is no shared mutable list of live
     * charges anywhere.
     */
    public void runEveryTick(BooleanSupplier action) {
        perTick.add(action);
    }

    public void clear() {
        delayed.clear();
        perTick.clear();
    }

    /** Called once per server tick. */
    public void tick() {
        now++;

        if (!delayed.isEmpty()) {
            dueScratch.clear();
            delayed.removeIf(d -> {
                if (d.dueTick() > now) return false;
                dueScratch.add(d);
                return true;
            });
            for (Delayed d : dueScratch) d.action().run();
        }

        if (!perTick.isEmpty()) {
            tickScratch.clear();
            tickScratch.addAll(perTick);
            perTick.clear();
            for (BooleanSupplier s : tickScratch) {
                if (s.getAsBoolean()) perTick.add(s);
            }
        }
    }
}
