package com.vex.anticheat;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class CombatChecker {

    public static void register() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide()) {
                return InteractionResult.PASS;
            }
            VexAntiCheat ac = VexAntiCheat.INSTANCE;
            if (ac == null) {
                return InteractionResult.PASS;
            }
            PlayerData data = ac.getData(sp.getUUID());
            if (data == null) {
                return InteractionResult.PASS;
            }
            if (ac.isExempt(sp)) {
                return InteractionResult.PASS;
            }

            long now = System.currentTimeMillis();

            // ---- REACH (Paper ReachCheck port) ----
            if (ac.getConfig().isEnabled(CheckType.REACH)) {
                Vec3 eye = sp.getEyePosition();
                AABB box = entity.getBoundingBox().inflate(0.1);
                double dist = distanceToBox(eye, box);
                double limit = sp.isCreative() ? 5.1 : 3.01;
                if (dist > limit) {
                    ac.flag(sp, data, CheckType.REACH, 2.0, "dist=" + String.format("%.2f", dist));
                    return InteractionResult.FAIL;
                }
            }

            // ---- KILLAURA / TRIGGERBOT (CPS + duzensiz tik) ----
            if (ac.getConfig().isEnabled(CheckType.KILLAURA)) {
                data.addAttack(now);

                // ayni tick'te 3+ saldiri = multiaura (insan tek tiklar)
                long gameTime = sp.level().getGameTime();
                if (gameTime == data.lastAttackGameTime) {
                    data.attackTickCount++;
                    if (data.attackTickCount >= 3) {
                        ac.flag(sp, data, CheckType.KILLAURA, 1.5, "multi");
                    }
                } else {
                    data.lastAttackGameTime = gameTime;
                    data.attackTickCount = 1;
                }

                // saniyede 12+ tik = aura sampiyonu
                long window = now - data.cpsWindowStart;
                data.cpsWindowCount++;
                if (window >= 1000) {
                    int cps = data.cpsWindowCount;
                    data.cpsWindowCount = 0;
                    data.cpsWindowStart = now;
                    if (cps > 12) {
                        ac.flag(sp, data, CheckType.KILLAURA, 1.5, "cps=" + cps);
                    }
                }

                // cok duzenli araliklar (bot tiklama)
                if (data.attackIntervals.size() >= 10) {
                    double avg = 0;
                    for (long l : data.attackIntervals) {
                        avg += l;
                    }
                    avg /= data.attackIntervals.size();
                    double variance = 0;
                    for (long l : data.attackIntervals) {
                        variance += Math.abs(l - avg);
                    }
                    variance /= data.attackIntervals.size();
                    if (avg < 120 && variance < 15) {
                        ac.flag(sp, data, CheckType.TRIGGERBOT, 1.5, "avg=" + (int) avg);
                    }
                }
            }

            // ---- CRITICALS (yerdeyken kritik vurma basit kontrol) ----
            if (ac.getConfig().isEnabled(CheckType.CRITICALS)) {
                boolean onGround = sp.onGround();
                boolean inWater = sp.isInWater();
                double fallDist = sp.fallDistance;
                // yerde, dusmeden kritik vuruyorsa
                if (onGround && !inWater && fallDist <= 0.01) {
                    // her vurus kritik olamaz, VL biriktir
                    // burada hasar hook'u yok, sadece supheli pattern icin kucuk VL
                    // gercek kritik tespiti icin LivingHurt event gerekir
                }
            }

            return InteractionResult.PASS;
        });
    }

    private static double distanceToBox(Vec3 p, AABB box) {
        double dx = Math.max(box.minX - p.x, Math.max(0, p.x - box.maxX));
        double dy = Math.max(box.minY - p.y, Math.max(0, p.y - box.maxY));
        double dz = Math.max(box.minZ - p.z, Math.max(0, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Scaffold icin blok yerlestirme hizi takibi (UseBlockCallback ile cagrilir)
    public static void onPlace(ServerPlayer player) {
        VexAntiCheat ac = VexAntiCheat.INSTANCE;
        if (ac == null) {
            return;
        }
        PlayerData data = ac.getData(player.getUUID());
        if (data == null || ac.isExempt(player)) {
            return;
        }
        if (!ac.getConfig().isEnabled(CheckType.SCAFFOLD)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - data.placeWindowStart > 1000) {
            int count = data.placeCount;
            data.placeCount = 0;
            data.placeWindowStart = now;
            // 1 saniyede 10+ blok = scaffold/hizli yerlestirme
            if (count > 10) {
                ac.flag(player, data, CheckType.SCAFFOLD, 1.5, "bps=" + count);
            }
            // bakis asagidayken havada blok koyma da supheli, movement ile birlesir
        }
        data.placeCount++;
        data.lastPlaceTime = now;

        // asagiya bakmadan hizli kopru (acisi kontrolu)
        float pitch = player.getXRot();
        if (pitch < 30 && data.airTicks > 3 && data.placeCount > 4) {
            ac.flag(player, data, CheckType.SCAFFOLD, 1.0, "pitch=" + (int) pitch);
        }
    }
}
