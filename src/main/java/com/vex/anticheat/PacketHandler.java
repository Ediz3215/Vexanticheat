package com.vex.anticheat;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Paket seviyesi kontroller (mixin ile ServerGamePacketListenerImpl'dan beslenir).
 * Tick seviyesinin goremedigi seyler: paket hizi (timer), saldiri ani rotasyonu,
 * paketteki onGround iddiasi, bozuk paketler.
 */
public class PacketHandler {

    // Hitbox toleransi: intercept testinde hedef kutusu bu kadar sisme ile
    // denenir (grazing vuruslar mesru sayilir). Degeri kullanici belirler.
    static final double HITBOX_MARGIN = 0.3;

    public static void onMove(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        // Vanilla paketi once Netty thread'de gorur, sonra main thread'de TEKRAR
        // calistirir. Iki kez saymamak icin sadece main thread'de isle.
        if (!player.level().getServer().isSameThread()) {
            return;
        }
        VexAntiCheat ac = VexAntiCheat.INSTANCE;
        if (ac == null) {
            return;
        }
        PlayerData data = ac.getData(player.getUUID());
        if (data == null || ac.isExempt(player)) {
            return;
        }

        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        float yaw = packet.getYRot(player.getYRot());
        float pitch = packet.getXRot(player.getXRot());

        // ---- BADPACKETS: bozuk sayi / imkansiz pitch ----
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(pitch) > 90.0f) {
            ac.flag(player, data, CheckType.BADPACKETS, 2.0, "nan/pitch");
            return;
        }

        if (packet.hasRotation()) {
            data.pktPrevYaw = data.pktYaw;
            data.pktPrevPitch = data.pktPitch;
            data.pktYaw = yaw;
            data.pktPitch = pitch;
            data.pktHasRot = true;
        }
        if (packet.hasPosition()) {
            data.pktX = x;
            data.pktY = y;
            data.pktZ = z;
            data.pktHasPos = true;
        }

        // ---- TIMER: paket hizi (mesru ~20/s, hizli timer 30+/s) ----
        // Lag ve isinlanma sonrasi paket yiginlari sayilmaz.
        // Tek seferlik patlama affedilir (arka arkaya 2 pencere gerekli).
        long now = System.currentTimeMillis();
        boolean tpGrace = now - data.lastEnderTime < 5000;
        if (tpGrace || now - ac.lastLagMs < 1000) {
            data.pktTimes.clear();
        } else {
            data.pktTimes.addLast(now);
            while (!data.pktTimes.isEmpty() && now - data.pktTimes.peekFirst() > 2000) {
                data.pktTimes.pollFirst();
            }
            if (data.pktTimes.size() > 60) {
                if (now - data.lastPrateTime < 3000) {
                    ac.flag(player, data, CheckType.TIMER, 2.0, "prate=" + data.pktTimes.size());
                }
                data.lastPrateTime = now;
                data.pktTimes.clear();
            }
        }

        // ---- GROUNDSPOOF (paket iddiasi vs gercek destek) ----
        if (packet.isOnGround() && !player.isInWater() && !player.isInLava() && !player.onClimbable()
                && now - data.createdAt > 6000 && now - data.lastEnderTime > 5000
                && now - data.lastWindTime > 2500) {
            double gap = y - MovementChecker.supportTop(player);
            if (gap > 0.5) {
                data.pktSpoof++;
                if (data.pktSpoof >= 6) {
                    ac.flag(player, data, CheckType.GROUNDSPOOF, 1.5,
                            "pgap=" + String.format("%.2f", gap));
                }
            } else {
                data.pktSpoof = 0;
            }
        } else {
            data.pktSpoof = 0;
        }
    }

    // @return true = saldiri iptal edilsin (mixin ci.cancel cagirir)
    public static boolean onInteract(ServerPlayer player, ServerboundInteractPacket packet) {
        // Ayni dedup: sadece main thread.
        if (!player.level().getServer().isSameThread()) {
            return false;
        }
        VexAntiCheat ac = VexAntiCheat.INSTANCE;
        if (ac == null) {
            return false;
        }
        PlayerData data = ac.getData(player.getUUID());
        if (data == null || ac.isExempt(player)) {
            return false;
        }
        final boolean[] cancel = { false };
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 pos) {
            }

            @Override
            public void onAttack() {
                if (handleAttack(ac, player, data, packet)) {
                    cancel[0] = true;
                }
            }
        });
        return cancel[0];
    }

    // @return true = iptal
    private static boolean handleAttack(VexAntiCheat ac, ServerPlayer player, PlayerData data,
            ServerboundInteractPacket packet) {
        Entity target;
        try {
            target = packet.getTarget(player.level());
        } catch (Exception e) {
            return false;
        }
        if (target == null || target == player) {
            if (target == player) {
                ac.flag(player, data, CheckType.BADPACKETS, 2.0, "self");
            }
            return false;
        }

        // Olu hedefe vurus Grim gibi atlanir (oldugu tick'te vurmak mesru olabilir)
        if (!target.isAlive()) {
            return false;
        }

        long gameTime = player.level().getGameTime();
        long now = System.currentTimeMillis();

        // mizrakla saldiri = lunge dash olabilir (hiz toleransi icin isaretle)
        try {
            if (player.getMainHandItem().is(net.minecraft.tags.ItemTags.SPEARS)) {
                data.lastSpearTime = now;
            }
        } catch (Exception ignored) {
        }

        // ---- multiaura: ayni tick'te 3+ saldiri (fazlalik iptal) ----
        if (gameTime == data.lastAttackGameTime) {
            data.attackTickCount++;
            if (data.attackTickCount >= 3) {
                ac.flag(player, data, CheckType.KILLAURA, 1.5, "multi");
                return true;
            }
        } else {
            data.lastAttackGameTime = gameTime;
            data.attackTickCount = 1;
        }

        // ---- CPS (paket zamanli, hassas). 14+ flag, 25+ iptal ----
        data.pktAttackTimes.addLast(now);
        while (!data.pktAttackTimes.isEmpty() && now - data.pktAttackTimes.peekFirst() > 1000) {
            data.pktAttackTimes.pollFirst();
        }
        if (data.pktAttackTimes.size() > 25) {
            ac.flag(player, data, CheckType.KILLAURA, 1.5, "cps=" + data.pktAttackTimes.size());
            data.pktAttackTimes.clear();
            return true;
        }
        if (data.pktAttackTimes.size() > 14) {
            ac.flag(player, data, CheckType.KILLAURA, 1.5, "cps=" + data.pktAttackTimes.size());
            data.pktAttackTimes.clear();
        }

        // ---- HITBOX vs REACH (Grim mantigi: ray-trace, paket-ani konumu) ----
        // Grim gibi saldiri anindaki PAKET konumu kullanilir (server pozu degil),
        // cunku hareket halindeki oyuncuda server pozu geriden gelir.
        // Vurus cizgisi kutuya degmiyorsa = hitbox; degip 4.0+ ise = reach.
        // Yakin mesafe (<1.5) dibinde karisiklik olabilir, yargilanmaz.
        // Iptal sadece 3.0+ mesafede (yakin temasta masum vurusu yemez).
        if (data.pktHasRot && target instanceof net.minecraft.world.entity.LivingEntity) {
            Vec3 eye;
            if (data.pktHasPos) {
                eye = new Vec3(data.pktX, data.pktY + player.getEyeHeight(), data.pktZ);
            } else {
                eye = player.getEyePosition();
            }
            AABB box = target.getBoundingBox();
            // intercept testinde toleransli (sismis) kutu kullanilir
            AABB fatBox = box.inflate(HITBOX_MARGIN);
            double rawDist = distanceToBox(eye, box);
            boolean intercept = fatBox.contains(eye)
                    || rayHitsBox(eye, lookVec(data.pktYaw, data.pktPitch), fatBox, 6.0) != null
                    || rayHitsBox(eye, lookVec(data.pktPrevYaw, data.pktPrevPitch), fatBox, 6.0) != null;
            if (!intercept && rawDist > 3.0 && rawDist <= 6.0) {
                // tani detayi: hedefe bakis acisi (kucuk aci + isabetsizlik = senkron sorunu,
                // buyuk aci = gercekten bakmadan vurma)
                double ang = angleToBox(eye, lookVec(data.pktYaw, data.pktPitch), box);
                ac.flag(player, data, CheckType.HITBOX, 2.0, "nointercept="
                        + String.format("%.2f", rawDist) + " ang=" + String.format("%.0f", ang));
                return true;
            } else if (rawDist > 4.0) {
                ac.flag(player, data, CheckType.REACH, 2.0,
                        "pdist=" + String.format("%.2f", rawDist));
                return true;
            }
        }
        return false;
    }

    // Gozden kutuya en yakin noktaya bakis acisi (derece). Kucuk = nisanli.
    static double angleToBox(Vec3 eye, Vec3 look, AABB box) {
        double cx = clamp(eye.x, box.minX, box.maxX);
        double cy = clamp(eye.y, box.minY, box.maxY);
        double cz = clamp(eye.z, box.minZ, box.maxZ);
        double dx = cx - eye.x;
        double dy = cy - eye.y;
        double dz = cz - eye.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return 0.0;
        }
        double dot = (dx * look.x + dy * look.y + dz * look.z) / len;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    static Vec3 lookVec(float yaw, float pitch) {        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
    }

    // Isin kutuya degiyor mu (slab yontemi)? Vurusa kadar degilse null doner.
    static Vec3 rayHitsBox(Vec3 eye, Vec3 dir, AABB box, double maxDist) {
        double tmin = 0.0;
        double tmax = maxDist;
        double[] o = { eye.x, eye.y, eye.z };
        double[] d = { dir.x, dir.y, dir.z };
        double[] mn = { box.minX, box.minY, box.minZ };
        double[] mx = { box.maxX, box.maxY, box.maxZ };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < mn[i] || o[i] > mx[i]) {
                    return null;
                }
            } else {
                double t1 = (mn[i] - o[i]) / d[i];
                double t2 = (mx[i] - o[i]) / d[i];
                double near = Math.min(t1, t2);
                double far = Math.max(t1, t2);
                tmin = Math.max(tmin, near);
                tmax = Math.min(tmax, far);
                if (tmin > tmax) {
                    return null;
                }
            }
        }
        return new Vec3(eye.x + dir.x * tmin, eye.y + dir.y * tmin, eye.z + dir.z * tmin);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static double distanceToBox(Vec3 p, AABB box) {
        double dx = Math.max(box.minX - p.x, Math.max(0, p.x - box.maxX));
        double dy = Math.max(box.minY - p.y, Math.max(0, p.y - box.maxY));
        double dz = Math.max(box.minZ - p.z, Math.max(0, p.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
