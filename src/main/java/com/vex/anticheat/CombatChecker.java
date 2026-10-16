package com.vex.anticheat;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;

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

            // REACH/CPS/multi artik paket katmaninda (PacketHandler, saldiri ani hassas).
            // Burada sadece davranissal triggerbot analizi kalir.
            // ---- TRIGGERBOT (duzensiz tik) ----
            if (ac.getConfig().isEnabled(CheckType.KILLAURA)) {
                data.addAttack(now);

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

    // Scaffold icin blok yerlestirme hizi takibi (UseBlockCallback ile cagrilir).
    // 10+/sn = flag, 16+/sn = yerlestirme iptal.
    public static InteractionResult onPlace(ServerPlayer player) {
        VexAntiCheat ac = VexAntiCheat.INSTANCE;
        if (ac == null) {
            return InteractionResult.PASS;
        }
        PlayerData data = ac.getData(player.getUUID());
        if (data == null || ac.isExempt(player)) {
            return InteractionResult.PASS;
        }
        if (!ac.getConfig().isEnabled(CheckType.SCAFFOLD)) {
            return InteractionResult.PASS;
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
        if (data.placeCount > 16) {
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }
}
