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

import com.creeperdiamonds.strikerload.core.Offset;
import com.creeperdiamonds.strikerload.core.Payload;
import com.creeperdiamonds.strikerload.core.StrikeGeometry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Fabric delivery. Positioning maths lives in StrikeGeometry; this class only
 * turns positions into entities.
 *
 * Deliberately simpler than the Paper module, which has to survive Folia's
 * region threading. A Fabric server ticks one thread, so there is no
 * region scheduler, no per-entity scheduler and no async teleport here - just
 * direct spawns plus TickScheduler for anything that needs to happen later.
 */
public final class PayloadDelivery {

    private final StrikerLoadMod mod;

    public PayloadDelivery(StrikerLoadMod mod) {
        this.mod = mod;
    }

    /** shooter may be null for console-dispatched strikes. */
    public void fire(Payload payload, ServerPlayer shooter, ServerLevel level, Vec3 target, int size) {
        if (payload == Payload.STASIS && shooter == null) return;
        FabricSettings s = mod.settings();
        switch (payload) {
            case NUKE -> nuke(s, shooter, level, target, size);
            case STAB -> stab(s, shooter, level, target);
            case RAILGUN -> railgun(s, shooter, level, target, size);
            case WOLF -> wolf(s, shooter, level, target, size);
            case DARKNESS -> darkness(s, shooter, level, target, size);
            case STASIS -> stasis(s, shooter, level, target);
        }
    }

    // ---------------- NUKE ----------------

    private void nuke(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target, int rings) {
        List<List<Offset>> shape = StrikeGeometry.nukeRings(
                rings, s.nuke.ringSpacing, s.nuke.ringDensity, s.safety.maxTntPerStrike);

        if (shape.size() <= rings && shooter != null) {
            warn(shooter, "Payload truncated at " + s.safety.maxTntPerStrike
                    + " TNT (safety.maxTntPerStrike).");
        }

        double dropY = dropHeight(s, level);

        for (int r = 0; r < shape.size(); r++) {
            long delay = s.nuke.ringDelayTicks * r;
            for (Offset o : shape.get(r)) {
                double x = target.x() + o.dx();
                double z = target.z() + o.dz();
                if (delay <= 0) {
                    spawnTnt(s, level, x, dropY, z, shooter);
                } else {
                    mod.scheduler().runLater(delay, () -> spawnTnt(s, level, x, dropY, z, shooter));
                }
            }
        }

        sound(level, target, SoundEvents.TRIDENT_THUNDER, 4f, 0.6f);
    }

    // ---------------- STAB ----------------

    private void stab(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target) {
        int[] ys = StrikeGeometry.stabColumn(s.stab.minY, s.stab.maxY,
                level.getMinY(), level.getMaxY(), s.stab.spacing);

        PrimedTnt[] column = new PrimedTnt[ys.length];
        for (int i = 0; i < ys.length; i++) {
            PrimedTnt tnt = new PrimedTnt(level,
                    target.x() + 0.5, ys[i], target.z() + 0.5, shooter);
            tnt.setFuse(Integer.MAX_VALUE);
            tnt.setNoGravity(true);
            tnt.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(tnt);
            column[i] = tnt;
        }

        sound(level, target, SoundEvents.BEACON_ACTIVATE, 4f, 0.5f);

        long delayTicks = Math.max(1L, (long) (s.rodDelaySeconds * 20));
        // Top-down, so the column reads as a rod driving into the ground.
        mod.scheduler().runLater(delayTicks, () -> {
            for (int i = column.length - 1; i >= 0; i--) {
                if (column[i] != null && !column[i].isRemoved()) column[i].setFuse(0);
            }
        });
    }

    // ---------------- RAILGUN ----------------

    private void railgun(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target, int arrows) {
        double originY = dropHeight(s, level);
        long delay = Math.max(1L, (long) (s.railgun.delaySeconds * 20));

        mod.scheduler().runLater(delay, () -> {
            sound(level, target, SoundEvents.CROSSBOW_SHOOT, 4f, 0.5f);
            for (int i = 0; i < arrows; i++) {
                double ax = target.x() + (Math.random() - 0.5) * s.railgun.spread * 4;
                double az = target.z() + (Math.random() - 0.5) * s.railgun.spread * 4;

                Arrow arrow = new Arrow(level, ax, originY, az,
                        new ItemStack(Items.ARROW), null);
                arrow.shoot(0, -1, 0, (float) s.railgun.speed, (float) s.railgun.spread);
                arrow.setBaseDamage(s.railgun.damage);
                arrow.setCritArrow(true);
                arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                if (shooter != null) arrow.setOwner(shooter);
                level.addFreshEntity(arrow);
            }
        });
    }

    // ---------------- WOLF ----------------

    private void wolf(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target, int amount) {
        int count = Math.min(amount, s.safety.maxWolvesPerStrike);

        for (int i = 0; i < count; i++) {
            double x = target.x() + (Math.random() - 0.5) * s.wolf.scatter * 2;
            double y = target.y() + 2 + Math.random() * 3;
            double z = target.z() + (Math.random() - 0.5) * s.wolf.scatter * 2;

            Wolf w = new Wolf(wolfType(), level);
            w.snapTo(x, y, z, (float) (Math.random() * 360.0), 0f);
            level.addFreshEntity(w);

            ServerPlayer victim = nearbyVictim(level, w.position(), shooter);
            if (victim != null) w.setTarget(victim);
        }

        sound(level, target, SoundEvents.WOLF_SHAKE, 4f, 0.8f);
    }

    /**
     * The wolf entity type, looked up by id rather than by constant.
     *
     * 1.21.11 and 26.1 both expose EntityType.WOLF, but 26.2 removed the
     * constant when wolves became variant-driven. The registry id is stable
     * across all three, so this is what keeps one source tree compiling
     * everywhere instead of forking the file per target.
     */
    private static EntityType<Wolf> wolfType() {
        @SuppressWarnings("unchecked")
        EntityType<Wolf> type = (EntityType<Wolf>) BuiltInRegistries.ENTITY_TYPE
                .getValue(Identifier.withDefaultNamespace("wolf"));
        return type;
    }

    /** Nearest survival-mode player to the drop, excluding the shooter. */
    private ServerPlayer nearbyVictim(ServerLevel level, Vec3 from, ServerPlayer exclude) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (exclude != null && p == exclude) continue;
            if (p.gameMode() == GameType.CREATIVE
                    || p.gameMode() == GameType.SPECTATOR) continue;
            double d = p.position().distanceToSqr(from);
            if (d < bestDist && d <= 24 * 24) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    // ---------------- DARKNESS ----------------

    private void darkness(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target, int range) {
        for (ServerPlayer p : level.players()) {
            if (shooter != null && p == shooter) continue;
            if (p.position().distanceTo(target) > range) continue;
            p.addEffect(new MobEffectInstance(
                    MobEffects.DARKNESS, s.darkness.durationSeconds * 20, 0, false, false));
            sound(level, p.position(), SoundEvents.WARDEN_HEARTBEAT, 1.5f, 0.6f);
        }
    }

    // ---------------- STASIS ----------------

    private void stasis(FabricSettings s, ServerPlayer shooter, ServerLevel level, Vec3 target) {
        long delay = Math.max(1L, (long) (s.stasis.delaySeconds * 20));

        mod.scheduler().runLater(delay, () -> {
            if (shooter.isRemoved()) return;
            shooter.teleportTo(level, target.x(), target.y(), target.z(),
                    Set.of(), shooter.getYRot(), shooter.getXRot(), true);
            sound(level, target, SoundEvents.ENDERMAN_TELEPORT, 2f, 1f);
        });
    }

    // ---------------- helpers ----------------

    private double dropHeight(FabricSettings s, ServerLevel level) {
        double ceiling = level.getMaxY() - 1;
        return s.dropHeight > 0 ? Math.min(s.dropHeight, ceiling) : ceiling;
    }

    /**
     * Spawns one charge and gives it its own impact watcher.
     *
     * The watcher cancels itself, so a wide nuke needs no central registry of
     * live charges. impactTimeoutTicks force-detonates anything still falling,
     * which stops a charge dropped into a void chunk from lingering forever.
     */
    private void spawnTnt(FabricSettings s, ServerLevel level,
                          double x, double y, double z, ServerPlayer shooter) {
        PrimedTnt tnt = new PrimedTnt(level, x, y, z, shooter);
        tnt.setFuse(Integer.MAX_VALUE);
        if (s.nuke.extraDownwardVelocity > 0) {
            tnt.setDeltaMovement(new Vec3(0, -s.nuke.extraDownwardVelocity, 0));
        }
        level.addFreshEntity(tnt);

        int[] elapsed = {0};
        mod.scheduler().runEveryTick(() -> {
            if (tnt.isRemoved()) return false;
            boolean landed = tnt.onGround() || tnt.getY() <= level.getMinY() + 1;
            if (landed || ++elapsed[0] >= s.safety.impactTimeoutTicks) {
                tnt.setFuse(0);
                return false;
            }
            return true;
        });
    }

    private void sound(ServerLevel level, Vec3 at, SoundEvent event, float volume, float pitch) {
        level.playSound(null, at.x(), at.y(), at.z(), event, SoundSource.MASTER, volume, pitch);
    }

    private void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> event, float volume, float pitch) {
        level.playSound(null, at.x(), at.y(), at.z(), event, SoundSource.MASTER, volume, pitch);
    }

    private void warn(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD));
    }
}
