package com.vex.anticheat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class VexAntiCheat implements ModInitializer {
    public static final String MOD_ID = "vex-anticheat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static VexAntiCheat INSTANCE;

    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<>();
    private final CheckConfig config = new CheckConfig();
    private long lastTickTime = 0;
    public long lastLagMs = 0;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[VexAntiCheat] 1.21.11 Fabric aktif!");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            dataMap.put(player.getUUID(), new PlayerData(player.getUUID()));
            // ilk konumu kaydet
            PlayerData data = dataMap.get(player.getUUID());
            data.updateLocation(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), player.onGround());
        });

        // hasar alan oyuncuya kisa sure knockback muafiyeti (hizi server ittigi icin)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer sp) {
                PlayerData d = dataMap.get(sp.getUUID());
                if (d != null) {
                    d.velocityPending = true;
                    d.lastVelocityTime = System.currentTimeMillis();
                }
            }
            return true;
        });

        // velocity takibi: hasar SONRASI uygulanan gercek KB vektorunu yakala.
        // Diken (thorns) geri tepmesi KB uygulamaz, izlenmez. Suda/agda sonumlenir, izlenmez.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, dealt, blocked) -> {
            if (!(entity instanceof ServerPlayer sp)) {
                return;
            }
            if (!(source.getEntity() instanceof ServerPlayer)) {
                return;
            }
            if (baseDamage < 1.5 || blocked) {
                return;
            }
            if (source.getMsgId().equals("thorns")) {
                return;
            }
            if (sp.isInWater() || sp.isInLava()) {
                return;
            }
            if (MovementChecker.isInCobweb(sp) || MovementChecker.isInPowderSnow(sp)) {
                return;
            }
            PlayerData d = dataMap.get(sp.getUUID());
            if (d == null) {
                return;
            }
            net.minecraft.world.phys.Vec3 kb = sp.getDeltaMovement();
            d.kbVX = kb.x;
            d.kbVY = kb.y;
            d.kbVZ = kb.z;
            d.hurtSpeed = Math.sqrt(d.lastDeltaX * d.lastDeltaX + d.lastDeltaZ * d.lastDeltaZ);
            d.hurtX = sp.getX();
            d.hurtY = sp.getY();
            d.hurtZ = sp.getZ();
            d.hurtTime = System.currentTimeMillis();
            d.hurtPending = true;
            d.hurtAttackerPlayer = true;
            d.hurtBlocking = false;
            d.hurtAmount = baseDamage;
        });

        // blok kirma hizi olcumu (fastbreak). 20+/2sn = flag + kirma iptal.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp && !isExempt(sp)) {
                PlayerData d = dataMap.get(sp.getUUID());
                if (d != null) {
                    long t = System.currentTimeMillis();
                    d.breakTimes.addLast(t);
                    while (!d.breakTimes.isEmpty() && t - d.breakTimes.peekFirst() > 2000) {
                        d.breakTimes.pollFirst();
                    }
                    if (d.breakTimes.size() > 20) {
                        flag(sp, d, CheckType.FASTBREAK, 1.5, "20+/2sn");
                        d.breakTimes.clear();
                        return false;
                    }
                }
            }
            return true;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            dataMap.remove(handler.getPlayer().getUUID());
        });

        // respawn'da veriyi sifirla (konum degisir, giris muafiyeti yeniden baslar)
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            dataMap.put(newPlayer.getUUID(), new PlayerData(newPlayer.getUUID()));
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        CombatChecker.register();
        VexCommands.register();
    }

    private void onTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        // lag tick: 150ms+ suren tick'te pozisyonlar sicrar, kontrol sagliksiz olur.
        // Konumu kabul et, suphe sayaclarini dondur, VL erit.
        boolean lagging = lastTickTime != 0 && now - lastTickTime > 150;
        lastTickTime = now;
        if (lagging) {
            lastLagMs = now;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // olu oyuncu kontrol edilmez (olum ekrani / respawn beklerken kipurdamaz)
            if (player.isDeadOrDying()) {
                continue;
            }
            PlayerData data = dataMap.get(player.getUUID());
            if (data == null) {
                data = new PlayerData(player.getUUID());
                dataMap.put(player.getUUID(), data);
            }
            data.tickDecay();
            // ---- VELOCITY olcumu (hasardan 200ms sonra) ----
            // Server'in uyguladigi gercek KB vektoru ile o ana kadarki hareket
            // karsilastirilir. KB yonunde beklenenin %25'inden az gidildiyse = yenmedi.
            // Vurus aninda hizli kosan degerlendirilmez, duvar/kalkan muaf.
            if (data.hurtPending && now - data.hurtTime >= 200) {
                data.hurtPending = false;
                if (!isExempt(player) && data.hurtAttackerPlayer && !data.hurtBlocking
                        && !player.horizontalCollision && !player.verticalCollision
                        && !MovementChecker.isInCobweb(player)
                        && !MovementChecker.isInPowderSnow(player)) {
                    double kbH = Math.sqrt(data.kbVX * data.kbVX + data.kbVZ * data.kbVZ);
                    double ddx = player.getX() - data.hurtX;
                    double ddz = player.getZ() - data.hurtZ;
                    double dispXZ = Math.sqrt(ddx * ddx + ddz * ddz);
                    double dispY = player.getY() - data.hurtY;
                    // dikey firlatma da KB alindigini gosterir (mace smash, patlama)
                    if (Math.abs(dispY) <= 1.0
                            && data.hurtAmount >= 1.5 && kbH >= 0.3 && data.hurtSpeed <= 0.3) {
                        double proj = (ddx * data.kbVX + ddz * data.kbVZ) / kbH;
                        if (proj < 0.25 * kbH) {
                            flag(player, data, CheckType.VELOCITY, 3.0, "nokb");
                            net.minecraft.world.phys.Vec3 cur = player.getDeltaMovement();
                            player.setDeltaMovement(new net.minecraft.world.phys.Vec3(
                                    cur.x + data.kbVX * 0.8, cur.y + 0.25, cur.z + data.kbVZ * 0.8));
                        }
                    }
                    if (dispXZ > 4.5) {
                        flag(player, data, CheckType.VELOCITY, 2.0, "boost="
                                + String.format("%.1f", dispXZ));
                    }
                }
            }
            if (lagging) {
                data.updateLocation(player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot(), player.onGround());
                data.flyBuffer = 0;
                data.stillTicks = 0;
                data.riseStreak = 0;
                data.airBoosts = 0;
                data.spoofBuffer = 0;
                data.sprintBuffer = 0;
                continue;
            }
            MovementChecker.check(player, data, this);
        }
    }

    public PlayerData getData(UUID uuid) {
        return dataMap.get(uuid);
    }

    public Map<UUID, PlayerData> getDataMap() {
        return dataMap;
    }

    public CheckConfig getConfig() {
        return config;
    }

    public boolean isExempt(ServerPlayer player) {
        // OP dahil herkes survival'da kontrol edilir.
        // Sadece creative/spectator/ucus/elytra/arac muaf.
        // VexBot sahte oyunculari test kuklasidir, muaf (insan disi adimlarla hareket ederler).
        if (isBot(player)) {
            return true;
        }
        if (player.isCreative() || player.isSpectator()) {
            return true;
        }
        if (player.getAbilities().flying) {
            return true;
        }
        if (player.isFallFlying()) {
            return true;
        }
        if (player.isPassenger()) {
            return true;
        }
        return false;
    }

    public static boolean isBot(ServerPlayer player) {
        String cn = player.getClass().getName();
        return cn.contains("BotPlayer") || cn.contains("FakePlayer") || cn.startsWith("vexbot.");
    }

    public void flag(ServerPlayer player, PlayerData data, CheckType type, double amount, String detail) {
        if (!config.isEnabled(type)) {
            return;
        }
        if (isExempt(player)) {
            return;
        }
        data.addVL(type, amount);
        double vl = data.getCheckVL(type);

        String msg = "§8[§cVexAC§8] §e" + player.getScoreboardName()
                + " §7flag §c" + type.getName() + " §7VL:" + String.format("%.1f", vl) + " " + detail;
        broadcastToOps(player.level().getServer(), msg);
        LOGGER.warn("[VexAC] {} VL={} {}", player.getScoreboardName(), vl, type.name() + " " + detail);

        // setback: hileli hareket geri alinir
        if (type == CheckType.SPEED || type == CheckType.FLY || type == CheckType.TIMER) {
            double lx = data.lastX;
            double ly = data.lastY;
            double lz = data.lastZ;
            if (lx != 0 || ly != 0 || lz != 0) {
                player.connection.teleport(lx, ly, lz, data.lastYaw, data.lastPitch);
            }
        } else if (type == CheckType.GROUNDSPOOF && data.hasGroundPos) {
            player.connection.teleport(data.lastGroundX, data.lastGroundY, data.lastGroundZ,
                    data.lastGroundYaw, data.lastGroundPitch);
        }

        // kick karari check bazinda: her check kendi esigine ulasinca atilir (varsayilan 50).
        if (type == CheckType.FLY) {
            if (vl >= 50.0) {
                data.resetVL();
                player.connection.disconnect(Component.literal("§cVexAntiCheat: Unfair Advantage"));
            }
        } else if (vl >= config.getVlThreshold(type)) {
            data.resetVL();
            player.connection.disconnect(Component.literal("§cVexAntiCheat: Unfair Advantage"));
        }
    }

    private void broadcastToOps(MinecraftServer server, String msg) {
        if (server == null) {
            return;
        }
        Component comp = Component.literal(msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Commands.LEVEL_ADMINS.check(p.createCommandSourceStack().permissions())) {
                p.sendSystemMessage(comp);
            }
        }
    }
}
