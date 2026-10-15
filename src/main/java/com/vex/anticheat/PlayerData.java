package com.vex.anticheat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerData {
    private final UUID uuid;

    public double lastX, lastY, lastZ;
    public float lastYaw, lastPitch;
    public double lastDeltaX, lastDeltaY, lastDeltaZ;
    public boolean onGround;
    public int airTicks = 0;
    public boolean initialized = false;

    private final Map<CheckType, Double> checkVLs = new EnumMap<>(CheckType.class);
    private double totalVL = 0;

    // combat
    public long lastAttackTime = 0;
    public final List<Long> attackIntervals = new ArrayList<>();
    public int cpsWindowCount = 0;
    public long cpsWindowStart = System.currentTimeMillis();

    // timer
    public long lastMoveTime = System.currentTimeMillis();
    public int movePackets = 0;

    // scaffold / place
    public long lastPlaceTime = 0;
    public int placeCount = 0;
    public long placeWindowStart = System.currentTimeMillis();

    // velocity
    public long lastVelocityTime = 0;
    public double velocityY = 0;
    public boolean velocityPending = false;

    // ender pearl / chorus son kullanim (isilanma muafiyeti)
    public long lastEnderTime = 0;

    // fly suphe sayaci (ust uste kac tick anormal) - tek tick sapmalarda flag yok
    public int flyBuffer = 0;
    // havada cakili durma sayaci (giris yuklemesi vs hover ayrimi icin)
    public int stillTicks = 0;
    // fizige uymayan surekli yukselis sayaci (jetpack tarzi)
    public int riseStreak = 0;
    // havada yukari ivme kazanma sayaci (7 tick'te bir sifirlayan fly'lar icin)
    public int airBoosts = 0;
    public long lastBoostTime = 0;
    // groundspoof suphe sayaci
    public int spoofBuffer = 0;

    // giris/respawn sonrasi muafiyet icin olusturma zamani
    public final long createdAt = System.currentTimeMillis();
    // muafiyet durumu degisince (creative->survival, aractan inme...) kisa muafiyet
    public boolean wasExempt = false;
    public long exemptChangeTime = 0;
    // BPS (saniyedeki blok) penceresi - timer/hizli speed tespiti
    public double bpsDist = 0;
    public long bpsStart = System.currentTimeMillis();
    // ust uste isinlanma sayaci (tek seferlik pearl/tp kabul, zincir = blink)
    public int teleportStreak = 0;
    // wind charge son kullanim (firlatma muafiyeti)
    public long lastWindTime = 0;
    // hasar-sonrasi knockback olcumu (velocity check)
    public double hurtX, hurtY, hurtZ;
    public long hurtTime = 0;
    public boolean hurtPending = false;
    public boolean hurtAttackerPlayer = false;
    public boolean hurtBlocking = false;
    public float hurtAmount = 0;
    // blok kirma zamanlari (fastbreak)
    public final java.util.ArrayDeque<Long> breakTimes = new java.util.ArrayDeque<>();
    // ayni tick saldiri sayaci (multiaura)
    public long lastAttackGameTime = -1;
    public int attackTickCount = 0;
    // omni-sprint suphe sayaci
    public int sprintBuffer = 0;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void updateLocation(double x, double y, double z, float yaw, float pitch, boolean ground) {
        if (!initialized) {
            lastX = x;
            lastY = y;
            lastZ = z;
            lastYaw = yaw;
            lastPitch = pitch;
            onGround = ground;
            initialized = true;
            return;
        }
        lastDeltaX = x - lastX;
        lastDeltaY = y - lastY;
        lastDeltaZ = z - lastZ;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        onGround = ground;
        if (ground) {
            airTicks = 0;
            flyBuffer = 0;
            stillTicks = 0;
            riseStreak = 0;
            airBoosts = 0;
            spoofBuffer = 0;
        } else {
            airTicks++;
        }
    }

    public void addVL(CheckType type, double amount) {
        checkVLs.put(type, checkVLs.getOrDefault(type, 0.0) + amount);
        totalVL += amount;
    }

    public double getCheckVL(CheckType type) {
        return checkVLs.getOrDefault(type, 0.0);
    }

    public double getTotalVL() {
        return totalVL;
    }

    public void resetVL() {
        totalVL = 0;
        checkVLs.clear();
    }

    // Temiz tick'lerde VL yavasca erir (saniyede ~0.6). Tekil blip'ler birikip
    // kick'e donusmez; surekli hile erimenin cok ustunde VL uretir.
    public void tickDecay() {
        if (totalVL <= 0) {
            return;
        }
        totalVL = Math.max(0, totalVL - 0.03);
        checkVLs.replaceAll((k, v) -> Math.max(0.0, v - 0.03));
    }

    public void addAttack(long now) {
        if (lastAttackTime != 0) {
            long interval = now - lastAttackTime;
            attackIntervals.add(interval);
            if (attackIntervals.size() > 15) {
                attackIntervals.remove(0);
            }
        }
        lastAttackTime = now;
    }
}
