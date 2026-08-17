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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure payload maths. No Bukkit, no Fabric, no Minecraft types at all.
 *
 * This is the part that must not be rewritten per platform, and the part that
 * can actually be unit tested. Platform modules ask this class where things go
 * and then spawn entities at those positions.
 */
public final class StrikeGeometry {

    private StrikeGeometry() {}

    /**
     * Concentric rings for a nuke, outermost work applied per ring so callers
     * can stagger them for a rolling barrage.
     *
     * @param rings    number of rings beyond the centre point
     * @param spacing  blocks between rings
     * @param density  one charge per this many blocks of circumference
     * @param maxTotal hard cap on charges; rings are dropped once exceeded
     * @return one list of offsets per ring, index 0 being the centre
     */
    public static List<List<Offset>> nukeRings(int rings, double spacing,
                                               double density, int maxTotal) {
        if (rings < 0) rings = 0;
        if (spacing <= 0) spacing = 1.0;
        if (density <= 0) density = 1.0;

        List<List<Offset>> out = new ArrayList<>();
        int budget = maxTotal;

        for (int ring = 0; ring <= rings; ring++) {
            double radius = ring * spacing;
            int count = ring == 0 ? 1 : Math.max(4, (int) (2 * Math.PI * radius / density));
            if (budget - count < 0) break;
            budget -= count;

            List<Offset> offsets = new ArrayList<>(count);
            if (ring == 0) {
                offsets.add(new Offset(0, 0));
            } else {
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    offsets.add(new Offset(radius * Math.cos(angle), radius * Math.sin(angle)));
                }
            }
            out.add(Collections.unmodifiableList(offsets));
        }
        return Collections.unmodifiableList(out);
    }

    /** Total charges a nuke of this shape would spawn. */
    public static int nukeCharges(int rings, double spacing, double density, int maxTotal) {
        int total = 0;
        for (List<Offset> ring : nukeRings(rings, spacing, density, maxTotal)) total += ring.size();
        return total;
    }

    /**
     * Y levels for a stab column, clamped to the world's build range.
     * World height is not fixed across versions - 1.8.8 is 0..255, modern is
     * -64..319 - so the caller passes the real bounds rather than assuming.
     */
    public static int[] stabColumn(int desiredMinY, int desiredMaxY,
                                   int worldMinY, int worldMaxY, int spacing) {
        if (spacing < 1) spacing = 1;
        int bottom = Math.max(desiredMinY, worldMinY + 1);
        int top = Math.min(desiredMaxY, worldMaxY - 1);
        if (top < bottom) return new int[0];

        int count = (top - bottom) / spacing + 1;
        int[] ys = new int[count];
        for (int i = 0; i < count; i++) ys[i] = bottom + i * spacing;
        return ys;
    }

    /** Linear falloff from 1.0 at the centre to 0.0 at the edge. */
    public static double falloff(double distance, double radius) {
        if (radius <= 0) return 0;
        double f = 1.0 - (distance / radius);
        return Math.max(0.0, Math.min(1.0, f));
    }
}
