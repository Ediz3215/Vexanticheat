package com.vex.anticheat;

import net.minecraft.server.level.ServerPlayer;

public class MovementChecker {

    public static void check(ServerPlayer player, PlayerData data, VexAntiCheat ac) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        boolean ground = player.onGround();

        if (!data.initialized) {
            data.updateLocation(x, y, z, yaw, pitch, ground);
            return;
        }

        double dx = x - data.lastX;
        double dy = y - data.lastY;
        double dz = z - data.lastZ;
        double deltaXZ = Math.sqrt(dx * dx + dz * dz);

        // teleport / respawn gibi isinlanmalari veri olarak kabul et, flag atma.
        // Ama UST USTE isinlanma = blink/speed teleporter hilesi -> TIMER flag.
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 100.0) {
            data.teleportStreak++;
            data.bpsDist = 0;
            data.bpsStart = System.currentTimeMillis();
            data.updateLocation(x, y, z, yaw, pitch, ground);
            if (data.teleportStreak >= 2) {
                ac.flag(player, data, CheckType.TIMER, 2.0, "blink");
            }
            return;
        }
        data.teleportStreak = 0;

        if (ac.isExempt(player)) {
            data.wasExempt = true;
            data.updateLocation(x, y, z, yaw, pitch, ground);
            return;
        }

        long now = System.currentTimeMillis();
        // tick suresi normalizasyonu: yavas tick'te biriken cok ticklik hareket
        // tek tick sanilip flag yememesi icin olcumler tick sayisina bolunur.
        double et = data.lastCheckMs == 0 ? 1.0 : (now - data.lastCheckMs) / 50.0;
        et = Math.max(1.0, Math.min(et, 5.0));
        data.lastCheckMs = now;
        double nDeltaXZ = deltaXZ / et;
        double nDy = dy / et;
        double nLastXZ = Math.sqrt(data.lastDeltaX * data.lastDeltaX
                + data.lastDeltaZ * data.lastDeltaZ) / data.lastEt;
        double nLastDeltaY = data.lastDeltaY / data.lastEt;
        data.lastEt = et;
        // giris/respawn sonrasi 6sn muafiyet (yavas client yuklemesinde packetsiz tick'ler olur)
        if (now - data.createdAt < 6000) {
            data.updateLocation(x, y, z, yaw, pitch, ground);
            return;
        }
        // muafiyetten cikinca 1.5sn muafiyet (creative->survival havada, aractan inme...)
        if (data.wasExempt) {
            data.wasExempt = false;
            data.exemptChangeTime = now;
        }
        if (now - data.exemptChangeTime < 1500 && data.exemptChangeTime != 0) {
            data.updateLocation(x, y, z, yaw, pitch, ground);
            return;
        }
        // yatakta yatan oyuncuyu kontrol etme
        if (player.isSleeping()) {
            data.updateLocation(x, y, z, yaw, pitch, ground);
            return;
        }
        // hasar sonrasi kisa sure knockback muafiyeti (600ms)
        if (data.velocityPending && now - data.lastVelocityTime > 600) {
            data.velocityPending = false;
        }
        // hasar sonrasi kisa sure knockback muafiyeti (600ms)
        if (data.velocityPending && now - data.lastVelocityTime > 600) {
            data.velocityPending = false;
        }
        boolean kbGrace = data.velocityPending;
        // sert dusus kaydi (mace smash / slime / wind burst sonrasi tolerans icin)
        if (dy < -1.2) {
            data.lastBigFallMs = now;
        }
        // dusus sonrasi 3sn hava toleransi (wind burst, slime sekmesi).
        // Sadece havada gecerli, yere degince biter.
        boolean fallGrace = !ground && now - data.lastBigFallMs < 3000;
        // ender pearl / chorus sonrasi isinlanma muafiyeti (5sn, uzun atislar icin)
        boolean tpGrace = now - data.lastEnderTime < 5000;
        // wind charge sonrasi firlatma muafiyeti (2.5sn)
        boolean windGrace = now - data.lastWindTime < 2500;
        // mizrak lunge sonrasi dash muafiyeti (2sn). Sadece hizli dash'te gecerli
        // (yavas hover bunun arkasina saklanamasin). Ham zaman ayri tutulur
        // (dikey riptide icin).
        boolean spearTime = now - data.lastSpearTime < 2000;
        boolean spearGrace = spearTime && deltaXZ > 0.5;

        // ---- VELOCITY olcumu onTick'te yapilir (zamaninda olcum icin) ----

        // ---- BPS (2 saniyelik pencerede blok/sn) ----
        // Tick-basi limitlerin altinda kalan timer/hizli speed'i yakalar.
        // Mesru azami kosu-ziplama ~7 m/s, hiz efektiyle ~9-10; esik 12.
        // Su/lav ve isinlanma sonrasi pencereye islenmez.
        boolean bpsInLiquid = player.isInWater() || player.isInLava();
        if (!kbGrace && !tpGrace && !fallGrace && !spearGrace && !bpsInLiquid) {
            data.bpsDist += deltaXZ;
        }
        double bpsElapsed = (now - data.bpsStart) / 1000.0;
        if (bpsElapsed >= 2.0) {
            if (data.bpsDist > 3.0) {
                double bps = data.bpsDist / bpsElapsed;
                if (bps > 12.0) {
                    ac.flag(player, data, CheckType.TIMER, 2.0,
                            "bps=" + String.format("%.1f", bps));
                }
            }
            data.bpsDist = 0;
            data.bpsStart = now;
        }

        // ---- TIMER (tick basina hareket suresi) ----
        // cok hizli paket akisi yerine tick arasi mesafe asiriligini basitce izle.
        // Su ici cok hizli olabilir, onu atla. (Yagmurda riptide sinirlama: bkz changelog)
        if (ac.getConfig().isEnabled(CheckType.TIMER)) {
            if (nDeltaXZ > 1.5 && distSq < 100.0 && !kbGrace && !tpGrace && !fallGrace && !spearGrace
                    && !player.isInWater()) {
                // 1 tickte 1.5 bloktan fazla yatay hareket = timer/speed
                ac.flag(player, data, CheckType.TIMER, 2.0, "dxz=" + String.format("%.2f", deltaXZ));
            }
        }

        // ---- SPEED ----
        // Mesru azami hizlarin biraz ustunde mutlak tavan + momentum kontrolu.
        // Ikisi de asilirsa flag. Tek eksen degil, XZ buyuklugu kullanilir.
        // Sprint desync toleransi: hiz yuru limitini asiyorsa sprint sayilir
        // (client sprint'e basmis ama server bir tick geriden ogrenmis olabilir).
        if (ac.getConfig().isEnabled(CheckType.SPEED)) {
            boolean sprinting = player.isSprinting() || nDeltaXZ > 0.27;
            double friction = data.onGround ? 0.91 : 0.89;
            double momentum = nLastXZ * friction + 0.12;
            double cap;
            if (sprinting) {
                cap = ground ? 0.42 : 0.75;
            } else {
                cap = ground ? 0.26 : 0.55;
            }
            // hiz efekti toleransi
            boolean hasSpeedEffect = player.hasEffect(net.minecraft.world.effect.MobEffects.SPEED);
            if (hasSpeedEffect) {
                cap += 0.25;
            }

            if (!kbGrace && !tpGrace && !fallGrace && !spearGrace && nDeltaXZ > cap && nDeltaXZ > momentum && nDeltaXZ < 1.5) {
                // yuzme/elytra disinda
                if (!player.isInWater() && !player.isInLava()) {
                    ac.flag(player, data, CheckType.SPEED, 1.2, "dxz=" + String.format("%.3f", deltaXZ)
                            + " sp=" + (player.isSprinting() ? 1 : 0) + " g=" + (ground ? 1 : 0));
                }
            }
        }

        // ---- HIGHJUMP + STEP (yerden kalkis anormal yuksekligi) ----
        // Normal ziplama 0.42, jump boost ile ~0.6. Ani +0.65 ustu = hile/step.
        // Slime ustunde ve wind charge ile mesru firlama olabilir -> muaf.
        boolean takeoff = data.onGround && !ground;
        if (takeoff && !kbGrace && !tpGrace && !windGrace && !fallGrace) {
            double jumpLimit = 0.62;
            var jumpEffect = player.hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST)
                    ? player.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST) : null;
            if (jumpEffect != null) {
                jumpLimit += 0.12 * (jumpEffect.getAmplifier() + 1);
            }
            if (!isSlimeBelow(player)) {
                if (nDy > jumpLimit && nDy <= 1.2) {
                    ac.flag(player, data, CheckType.HIGHJUMP, 1.5,
                            "dy=" + String.format("%.2f", dy));
                } else if (nDy > 1.2 && nDy <= 3.0) {
                    ac.flag(player, data, CheckType.STEP, 1.5,
                            "dy=" + String.format("%.2f", dy));
                }
            }
        }

        // ---- INVENTORYMOVE (kap acikken hareket) ----
        // Sandik/firin gibi kap acikken client hareketi kilitler; yuruyen = hile.
        if (player.containerMenu != player.inventoryMenu && nDeltaXZ > 0.3 && !kbGrace) {
            ac.flag(player, data, CheckType.INVENTORYMOVE, 1.5, "inv");
        }

        // ---- NOWEB: ag icinde hizli hareket (LB 0.23 strafe yapar) ----
        // Mesru ag ici ~0.1'i gecemez; giris ani buffer ile tolere edilir.
        if (isInCobweb(player) && !kbGrace) {
            if (nDeltaXZ > 0.18 || nDy > 0.3) {
                data.webBuffer++;
                if (data.webBuffer >= 3) {
                    ac.flag(player, data, CheckType.NOSLOW, 1.5, "web");
                }
            } else {
                data.webBuffer = 0;
            }
        } else {
            data.webBuffer = 0;
        }

        // ---- SPRINT: geriye dogru sprint (omni-sprint) + imkansiz sprint halleri ----
        // Vanilla'da sprint sadece ileriye bakarken mumkun; geriye sprint = hile.
        // Grim tarzi durum kontrolleri: yerken/su icerken, gizlenirken veya
        // aclik 6 ve altiyken sprint imkansizdir. Korlukten SONRA sprint baslatmak
        // da imkansizdir (kor olmadan once baslayan mesru). Knockback grace korur.
        boolean blind = player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        if (blind && data.blindStartMs == 0) {
            data.blindStartMs = now;
        } else if (!blind) {
            data.blindStartMs = 0;
        }
        if (player.isSprinting() && nDeltaXZ > 0.2 && !kbGrace && !tpGrace && !windGrace) {
            if (data.sprintStartMs == 0) {
                data.sprintStartMs = now;
            }
            double yawRad = Math.toRadians(yaw);
            double fx = -Math.sin(yawRad);
            double fz = Math.cos(yawRad);
            double dot = (dx * fx + dz * fz) / deltaXZ;
            boolean badState = player.isUsingItem() || player.isCrouching()
                    || player.getFoodData().getFoodLevel() <= 6;
            boolean blindSprint = blind && data.blindStartMs != 0
                    && data.sprintStartMs >= data.blindStartMs;
            if (dot < -0.3 || badState || blindSprint) {
                data.sprintBuffer++;
                if (data.sprintBuffer >= 4) {
                    ac.flag(player, data, CheckType.SPRINT, 1.0,
                            dot < -0.3 ? "omni" : (blindSprint ? "blind" : "state"));
                }
            } else {
                data.sprintBuffer = 0;
            }
        } else {
            data.sprintBuffer = 0;
            data.sprintStartMs = 0;
        }

        // ---- FLY (gravity prediction) ----
        // Hareketli havada: 4 ust uste anormal tick = flag (ziplama tepesi max 1-2 tick).
        // Cakili havada (hic kipirdamama): yukleme/lag toleransi icin 40 tick = flag.
        // Yere degince buffer'lar sifirlanir.
        if (ac.getConfig().isEnabled(CheckType.FLY)) {
            if (data.airTicks > 2 && !ground && !data.onGround && !kbGrace && !tpGrace && !fallGrace && !windGrace) {
                boolean levitating = player.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                        || player.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
                boolean stuck = isInCobweb(player) || isInPowderSnow(player) || player.getTicksFrozen() > 0;
                boolean inLiquid = player.isInWater() || player.isInLava() || player.onClimbable();
                boolean inBubble = isInBubbleColumn(player);
                if (!levitating && !stuck && !inLiquid && !inBubble) {
                    // tamamen/kismen cakili: yukleme donmasi toleransi (40 tick), dusuk VL.
                    // Mikro-titresim de cakili sayilir (1e-4 alti).
                    boolean frozen = distSq < 0.0001;
                    if (frozen) {
                        data.stillTicks++;
                        if (data.stillTicks >= 40) {
                            ac.flag(player, data, CheckType.FLY, 0.5, "frozen");
                        }
                    } else {
                        data.stillTicks = 0;
                        double expectedY = (nLastDeltaY - 0.08) * 0.98;
                        double diffY = Math.abs(nDy - expectedY);
                        // mizrak lunge Y-kilitli dash yapar, tahmin tutmaz: hizli dash muaf
                        if (diffY > 0.05 * et && Math.abs(nDy) < 0.1 && !spearGrace) {
                            data.flyBuffer++;
                            if (data.flyBuffer >= 4) {
                                ac.flag(player, data, CheckType.FLY, 1.5, "dy=" + String.format("%.3f", dy)
                                        + " on=" + blockName(player));
                            }
                        } else {
                            data.flyBuffer = Math.max(0, data.flyBuffer - 1);
                        }
                    }
                    // surekli yukselis (jetpack/sentinel tarzi): fizige UYMAYAN tirmanis.
                    // Ziplama/slime/patlama fizige uyar (tahmin tutar) -> flag yok.
                    // Mesru hicbir hareket dy>0.15'i surekli tutamaz (ziplama hemen sonumlenir).
                    // Jetpack/sentinel yercekimini yener (tahmin sapar) -> 5 tick ust uste = flag.
                    double expectedClimb = (nLastDeltaY - 0.08) * 0.98;
                    if (nDy > 0.15 && Math.abs(nDy - expectedClimb) > 0.05 * et) {
                        data.riseStreak++;
                        if (data.riseStreak >= 5) {
                            ac.flag(player, data, CheckType.FLY, 1.5, "rise="
                                    + String.format("%.2f", dy));
                        }
                    } else {
                        data.riseStreak = 0;
                    }
                    // havada yukari ivme kazanma (air-jump): yercekimi her tick dusurur,
                    // o yuzden havada dy'nin birden buyumesi dis destektir (Sentinel gibi
                    // 7 tick'te bir hiz sifirlayan fly'lar). Yerden kalkis ve slime sekmesi
                    // ilk 5 tick muaf (airTicks), patlama/pearl grace ile korunur.
                    // Dikey riptide/mizrak firlatmasi muaf (dy>1.0). 5sn icinde 3 boost = flag.
                    if (data.airTicks > 5 && !(spearTime && dy > 1.0)) {
                        double boost = nDy - nLastDeltaY;
                        if (boost > 0.12) {
                            if (now - data.lastBoostTime < 5000) {
                                data.airBoosts++;
                            } else {
                                data.airBoosts = 1;
                            }
                            data.lastBoostTime = now;
                            if (data.airBoosts >= 3) {
                                ac.flag(player, data, CheckType.FLY, 1.5, "airjump");
                            }
                        }
                    }
                } else {
                    data.flyBuffer = 0;
                    data.stillTicks = 0;
                    data.riseStreak = 0;
                }
            }
        }

        // ---- GROUNDSPOOF (client "yerdeyim" diyor ama altinda destek yok) ----
        // Hileler yere carpmamak / dusme hasari yememek icin onGround uydurur.
        // Server bagimsiz gercek kontrolu: ayaklarin altinda carpisma var mi?
        if (ac.getConfig().isEnabled(CheckType.GROUNDSPOOF)) {
            if (ground && !kbGrace && !tpGrace
                    && !player.isInWater() && !player.isInLava() && !player.onClimbable()) {
                double gap = y - supportTop(player);
                if (gap > 0.5) {
                    data.spoofBuffer++;
                    if (data.spoofBuffer >= 3) {
                        ac.flag(player, data, CheckType.GROUNDSPOOF, 1.5,
                                "gap=" + String.format("%.2f", gap));
                    }
                } else {
                    data.spoofBuffer = 0;
                }
            } else {
                data.spoofBuffer = 0;
            }
        }

        // ---- LIQUIDWALK / Jesus (sivi USTUNDE yurume) ----
        // Su icinde yuzen degil; su disinda ama hemen alti sivi olan ve
        // "yerdeyim" diyen = su ustunde yuruyor. Yuzenler/batanlar muaf.
        if (ac.getConfig().isEnabled(CheckType.LIQUIDWALK)) {
            if (!player.isInWater() && !player.isInLava() && ground
                    && nDeltaXZ > 0.3 && !kbGrace && !tpGrace && !windGrace
                    && isLiquidBelow(player)) {
                ac.flag(player, data, CheckType.LIQUIDWALK, 2.0, "dxz=" + String.format("%.2f", deltaXZ));
            }
        }

        // ---- NOSLOW: yemek yerken hizli hareket ----
        // Yerken yurume ~0.13-0.15'i gecemez. Spoof'layanlar isUsingItem'i
        // gizler; o yuzden yemek baslangicindan 1.6sn pencere de izlenir.
        if (ac.getConfig().isEnabled(CheckType.NOSLOW) && ground
                && !kbGrace && !tpGrace && !windGrace) {
            boolean usingItem = player.isUsingItem();
            if (usingItem && nDeltaXZ > 0.18) {
                ac.flag(player, data, CheckType.NOSLOW, 1.5, "eat");
            } else if (!usingItem && now - data.lastEatStart < 1600 && nDeltaXZ > 0.22) {
                ac.flag(player, data, CheckType.NOSLOW, 1.5, "eatspoof");
            }
        }

        data.updateLocation(x, y, z, yaw, pitch, ground);
    }

    private static boolean isInBubbleColumn(ServerPlayer player) {
        try {
            var level = player.level();
            var feet = player.blockPosition();
            if (level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) {
                return true;
            }
            if (level.getBlockState(feet.above()).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) {
                return true;
            }
            return level.getBlockState(feet.above(2)).is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN);
        } catch (Exception e) {
            return false;
        }
    }

    private static String blockName(ServerPlayer player) {
        try {
            return player.level().getBlockState(player.blockPosition()).getBlock().getDescriptionId();
        } catch (Exception e) {
            return "?";
        }
    }

    private static boolean isLiquidBelow(ServerPlayer player) {
        try {
            var level = player.level();
            var feet = player.blockPosition();
            if (isLiquid(level.getBlockState(feet)) || isLiquid(level.getBlockState(feet.below()))) {
                return true;
            }
            // ayaklar hava blogundaysa bir alt katmana da bak (0.5 alti)
            var box = player.getBoundingBox();
            int by = (int) Math.floor(box.minY - 0.5);
            var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
            int minBX = (int) Math.floor(box.minX - 0.3);
            int maxBX = (int) Math.floor(box.maxX + 0.3);
            int minBZ = (int) Math.floor(box.minZ - 0.3);
            int maxBZ = (int) Math.floor(box.maxZ + 0.3);
            for (int bx = minBX; bx <= maxBX; bx++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    cursor.set(bx, by, bz);
                    if (isLiquid(level.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLiquid(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.WATER)
                || state.is(net.minecraft.world.level.block.Blocks.LAVA);
    }

    private static boolean isSlimeBelow(ServerPlayer player) {        try {
            var feet = player.blockPosition();
            return player.level().getBlockState(feet.below())
                    .is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isInPowderSnow(ServerPlayer player) {
        try {
            var level = player.level();
            var feet = player.blockPosition();
            if (level.getBlockState(feet).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) {
                return true;
            }
            return level.getBlockState(feet.above()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        } catch (Exception e) {
            return false;
        }
    }

    // Ayaklarin altindaki en yuksek carpisma ust yuzeyi (gercek zemin).
    // Destek yoksa -SONSUZ doner. Paket katmani da kullanir.
    static double supportTop(ServerPlayer player) {
        try {
            var level = player.level();
            var box = player.getBoundingBox();
            int minBX = (int) Math.floor(box.minX - 0.3);
            int maxBX = (int) Math.floor(box.maxX + 0.3);
            int minBZ = (int) Math.floor(box.minZ - 0.3);
            int maxBZ = (int) Math.floor(box.maxZ + 0.3);
            int minBY = (int) Math.floor(box.minY - 0.6);
            int maxBY = (int) Math.floor(box.minY);
            var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
            double top = Double.NEGATIVE_INFINITY;
            for (int bx = minBX; bx <= maxBX; bx++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    for (int by = minBY; by <= maxBY; by++) {
                        cursor.set(bx, by, bz);
                        var shape = level.getBlockState(cursor).getCollisionShape(level, cursor);
                        if (!shape.isEmpty()) {
                            double t = by + shape.max(net.minecraft.core.Direction.Axis.Y);
                            if (t > top) {
                                top = t;
                            }
                        }
                    }
                }
            }
            return top;
        } catch (Exception e) {
            return 0.0;
        }
    }

    static boolean isInCobweb(ServerPlayer player) {
        try {
            var state = player.level().getBlockState(player.blockPosition());
            return state.is(net.minecraft.world.level.block.Blocks.COBWEB);
        } catch (Exception e) {
            return false;
        }
    }
}
