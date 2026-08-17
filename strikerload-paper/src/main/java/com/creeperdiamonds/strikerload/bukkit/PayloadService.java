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

import com.creeperdiamonds.strikerload.core.Offset;
import com.creeperdiamonds.strikerload.core.Payload;
import com.creeperdiamonds.strikerload.core.StrikeGeometry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bukkit/Paper/Folia delivery. Positioning maths lives in StrikeGeometry; this
 * class only turns positions into entities.
 *
 * FOLIA NOTES - the rules this class is written to obey:
 *
 *  1. There is no main thread. Every world or entity touch must happen on the
 *     thread that owns that location or entity.
 *  2. Regions tick in parallel and do not share data. Code in one region must
 *     never read or write another region's entities.
 *  3. RegionScheduler is for locations. Entity.getScheduler() is for entities,
 *     because it follows the entity across region boundaries; the region
 *     scheduler does not.
 *  4. Teleports must go through teleportAsync.
 *
 * These same calls are internally handled on ordinary Paper, so one jar serves
 * both platforms and there is no runtime platform detection anywhere.
 */
public final class PayloadService {

    private final StrikerLoadPlugin plugin;

    public PayloadService(StrikerLoadPlugin plugin) {
        this.plugin = plugin;
    }

    /** shooter may be null for console-dispatched strikes. */
    public void fire(Payload payload, Player shooter, Location target, int size) {
        if (payload == Payload.STASIS && shooter == null) return;
        Settings s = plugin.settings();
        switch (payload) {
            case NUKE -> nuke(s, shooter, target, size);
            case STAB -> stab(s, shooter, target);
            case RAILGUN -> railgun(s, shooter, target, size);
            case WOLF -> wolf(s, shooter, target, size);
            case DARKNESS -> darkness(s, shooter, target, size);
            case STASIS -> stasis(s, shooter, target);
        }
    }

    // ---------------- NUKE ----------------

    private void nuke(Settings s, Player shooter, Location target, int rings) {
        World world = target.getWorld();
        List<List<Offset>> shape = StrikeGeometry.nukeRings(
                rings, s.nukeRingSpacing, s.nukeRingDensity, s.maxTntPerStrike);

        if (shape.size() <= rings && shooter != null) {
            shooter.sendMessage(Component.text(
                    "Payload truncated at " + s.maxTntPerStrike + " TNT (safety.max-tnt-per-strike).",
                    NamedTextColor.GOLD));
        }

        double dropY = dropHeight(s, world);

        for (int r = 0; r < shape.size(); r++) {
            List<Offset> ring = shape.get(r);
            long delay = s.nukeRingDelayTicks * r;

            for (Offset o : ring) {
                Location loc = new Location(world,
                        target.getX() + o.dx(), dropY, target.getZ() + o.dz());

                // A wide nuke can span several regions, so each charge is
                // scheduled onto the region that owns its own drop position
                // rather than the region that owns the centre.
                if (delay <= 0) {
                    Bukkit.getRegionScheduler().run(plugin, loc, task -> spawnTnt(s, world, loc, shooter));
                } else {
                    Bukkit.getRegionScheduler().runDelayed(plugin, loc,
                            task -> spawnTnt(s, world, loc, shooter), delay);
                }
            }
        }

        Bukkit.getRegionScheduler().run(plugin, target, task ->
                world.playSound(target, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.MASTER, 4f, 0.6f));
    }

    // ---------------- STAB ----------------

    private void stab(Settings s, Player shooter, Location target) {
        World world = target.getWorld();

        // The column varies only in Y, so every charge is owned by the region
        // that owns the target column - one region task is correct here.
        Bukkit.getRegionScheduler().run(plugin, target, task -> {
            int[] ys = StrikeGeometry.stabColumn(s.stabMinY, s.stabMaxY,
                    world.getMinHeight(), world.getMaxHeight(), s.stabSpacing);

            TNTPrimed[] column = new TNTPrimed[ys.length];
            for (int i = 0; i < ys.length; i++) {
                Location loc = new Location(world, target.getX() + 0.5, ys[i], target.getZ() + 0.5);
                column[i] = world.spawn(loc, TNTPrimed.class, t -> {
                    t.setFuseTicks(Integer.MAX_VALUE);
                    t.setGravity(false);
                    if (shooter != null) t.setSource(shooter);
                    t.setVelocity(new Vector(0, 0, 0));
                });
            }

            world.playSound(target, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 4f, 0.5f);

            long delayTicks = Math.max(1L, (long) (s.rodDelaySeconds * 20));
            Bukkit.getRegionScheduler().runDelayed(plugin, target, t2 -> {
                for (int i = column.length - 1; i >= 0; i--) {
                    if (column[i] != null && column[i].isValid()) column[i].setFuseTicks(0);
                }
            }, delayTicks);
        });
    }

    // ---------------- RAILGUN ----------------

    private void railgun(Settings s, Player shooter, Location target, int arrows) {
        World world = target.getWorld();
        Location origin = new Location(world, target.getX(), dropHeight(s, world), target.getZ());
        Vector down = new Vector(0, -1, 0);
        long delay = Math.max(1L, (long) (s.railgunDelaySeconds * 20));

        Bukkit.getRegionScheduler().runDelayed(plugin, origin, task -> {
            world.playSound(target, Sound.ITEM_CROSSBOW_SHOOT, SoundCategory.MASTER, 4f, 0.5f);
            for (int i = 0; i < arrows; i++) {
                Arrow arrow = world.spawnArrow(origin.clone().add(
                                (Math.random() - 0.5) * s.railgunSpread * 4, 0,
                                (Math.random() - 0.5) * s.railgunSpread * 4),
                        down, (float) s.railgunSpeed, (float) s.railgunSpread);
                if (shooter != null) arrow.setShooter(shooter);
                arrow.setDamage(s.railgunDamage);
                arrow.setCritical(true);
                arrow.setPierceLevel(5);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            }
        }, delay);
    }

    // ---------------- WOLF ----------------

    private void wolf(Settings s, Player shooter, Location target, int amount) {
        World world = target.getWorld();
        int count = Math.min(amount, s.maxWolvesPerStrike);

        Bukkit.getRegionScheduler().run(plugin, target, task -> {
            for (int i = 0; i < count; i++) {
                Location loc = target.clone().add(
                        (Math.random() - 0.5) * s.wolfScatter * 2,
                        2 + Math.random() * 3,
                        (Math.random() - 0.5) * s.wolfScatter * 2);

                world.spawn(loc, Wolf.class, w -> {
                    w.setAngry(true);
                    w.setPersistent(false);
                    // Target selection runs on the wolf's own scheduler and only
                    // considers entities near the wolf, so it never reaches into
                    // another region to read player state.
                    w.getScheduler().run(plugin, t2 -> {
                        Player victim = nearbyVictim(w, shooter);
                        if (victim != null) w.setTarget(victim);
                    }, null);
                });
            }
            world.playSound(target, Sound.ENTITY_WOLF_GROWL, SoundCategory.MASTER, 4f, 0.8f);
        });
    }

    /** Region-local target search: only entities already loaded near the wolf. */
    private Player nearbyVictim(Wolf wolf, Player exclude) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : wolf.getNearbyEntities(24, 12, 24)) {
            if (!(e instanceof Player p)) continue;
            if (exclude != null && p.equals(exclude)) continue;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(wolf.getLocation());
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best;
    }

    // ---------------- DARKNESS ----------------

    private void darkness(Settings s, Player shooter, Location target, int range) {
        // getPlayers() is safe to enumerate, but each player must be touched on
        // its own scheduler - a player in another region cannot be modified
        // from here. The distance check moves inside that task too, since the
        // player's position is only stable on its owning thread.
        for (Player p : target.getWorld().getPlayers()) {
            if (shooter != null && p.equals(shooter)) continue;
            p.getScheduler().run(plugin, task -> {
                if (!p.getWorld().equals(target.getWorld())) return;
                if (p.getLocation().distance(target) > range) return;
                p.addPotionEffect(new PotionEffect(
                        PotionEffectType.DARKNESS, s.darknessSeconds * 20, 0, false, false));
                p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.MASTER, 1.5f, 0.6f);
            }, null);
        }
    }

    // ---------------- STASIS ----------------

    private void stasis(Settings s, Player shooter, Location target) {
        Location dest = target.clone();
        long delay = Math.max(1L, (long) (s.stasisDelaySeconds * 20));

        shooter.getScheduler().runDelayed(plugin, task -> {
            if (!shooter.isOnline()) return;
            dest.setYaw(shooter.getLocation().getYaw());
            dest.setPitch(shooter.getLocation().getPitch());
            // Folia requires async teleport; on Paper this resolves immediately.
            shooter.teleportAsync(dest).thenAccept(ok -> {
                if (!ok) return;
                Bukkit.getRegionScheduler().run(plugin, dest, t2 -> dest.getWorld().playSound(
                        dest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 2f, 1f));
            });
        }, null, delay);
    }

    // ---------------- helpers ----------------

    private double dropHeight(Settings s, World world) {
        return s.dropHeight > 0
                ? Math.min(s.dropHeight, world.getMaxHeight() - 1)
                : world.getMaxHeight() - 1;
    }

    /**
     * Spawns one charge and gives it its own impact watcher.
     *
     * Each charge watches itself via its entity scheduler rather than a single
     * central task holding a shared list. That is not just Folia-safety
     * bookkeeping: a shared list would be mutated from several region threads
     * at once, and the charges in a wide nuke genuinely do land in different
     * regions. Self-watching charges have no shared state at all.
     */
    private void spawnTnt(Settings s, World world, Location loc, Player shooter) {
        TNTPrimed tnt = world.spawn(loc, TNTPrimed.class, t -> {
            t.setFuseTicks(Integer.MAX_VALUE);
            if (shooter != null) t.setSource(shooter);
            if (s.nukeExtraVelocity > 0) t.setVelocity(new Vector(0, -s.nukeExtraVelocity, 0));
        });

        AtomicInteger elapsed = new AtomicInteger();
        tnt.getScheduler().runAtFixedRate(plugin, task -> {
            if (!tnt.isValid()) { task.cancel(); return; }
            boolean landed = tnt.isOnGround()
                    || tnt.getLocation().getY() <= tnt.getWorld().getMinHeight() + 1;
            if (landed || elapsed.incrementAndGet() >= s.impactTimeoutTicks) {
                tnt.setFuseTicks(0);
                task.cancel();
            }
        }, null, 1L, 1L);
    }
}
