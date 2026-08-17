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

import java.util.Locale;

/** Payload types. Contains no platform types - safe on any loader. */
public enum Payload {
    NUKE,
    STAB,
    RAILGUN,
    WOLF,
    DARKNESS,
    STASIS;

    public static Payload parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String display() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    public int defaultSize() {
        return switch (this) {
            case NUKE -> 10;
            case STAB -> 1;
            case RAILGUN -> 45;
            case WOLF -> 150;
            case DARKNESS -> 50;
            case STASIS -> 1;
        };
    }
}
