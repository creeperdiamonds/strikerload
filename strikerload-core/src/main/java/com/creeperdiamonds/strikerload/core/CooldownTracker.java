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

package com.creeperdiamonds.strikerload.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player recharge timing. Platform-independent.
 *
 * Backed by a concurrent map on purpose: under Folia this is read and written
 * from multiple region threads in parallel, and a plain HashMap would corrupt
 * under concurrent resize. This is cheap insurance even on single-threaded
 * Paper, where it costs essentially nothing.
 */
public final class CooldownTracker {

    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    /** Remaining cooldown in milliseconds, or 0 if ready. */
    public long remaining(UUID player, long cooldownMillis, long now) {
        long previous = last.getOrDefault(player, 0L);
        long elapsed = now - previous;
        return elapsed >= cooldownMillis ? 0L : cooldownMillis - elapsed;
    }

    public void mark(UUID player, long now) {
        last.put(player, now);
    }

    public void clear(UUID player) {
        last.remove(player);
    }

    public void clearAll() {
        last.clear();
    }
}
