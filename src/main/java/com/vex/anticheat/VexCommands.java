package com.vex.anticheat;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;

public class VexCommands {

    public static void register() {
        // ender pearl / chorus takibi (isinlanma muafiyeti icin)
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                var stack = player.getItemInHand(hand);
                PlayerData d = VexAntiCheat.INSTANCE.getData(sp.getUUID());
                if (stack.is(Items.ENDER_PEARL) || stack.is(Items.CHORUS_FRUIT)) {
                    if (d != null) {
                        d.lastEnderTime = System.currentTimeMillis();
                    }
                }
                if (stack.is(Items.WIND_CHARGE)) {
                    if (d != null) {
                        d.lastWindTime = System.currentTimeMillis();
                    }
                }
                try {
                    var anim = stack.getUseAnimation();
                    boolean slowingUse = anim == net.minecraft.world.item.ItemUseAnimation.EAT
                            || anim == net.minecraft.world.item.ItemUseAnimation.DRINK
                            || anim == net.minecraft.world.item.ItemUseAnimation.BOW
                            || anim == net.minecraft.world.item.ItemUseAnimation.BLOCK
                            || anim == net.minecraft.world.item.ItemUseAnimation.CROSSBOW
                            || anim == net.minecraft.world.item.ItemUseAnimation.SPEAR;
                    if (slowingUse && d != null) {
                        d.lastEatStart = System.currentTimeMillis();
                    }
                    // mizrak + uc gozlu mizrak (trident) kullanimi: lunge/riptide toleransi
                    if (d != null && (stack.is(net.minecraft.tags.ItemTags.SPEARS)
                            || stack.getItem() instanceof net.minecraft.world.item.TridentItem)) {
                        d.lastSpearTime = System.currentTimeMillis();
                    }
                } catch (Exception ignored) {
                }
            }
            return InteractionResult.PASS;
        });

        // blok koyma takibi (scaffold, asiri hizda iptal)
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                return CombatChecker.onPlace(sp);
            }
            return InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("vex")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§8[§6VexAC§8] §a1.21.11 Fabric aktif\n§7/vex vl <oyuncu> - VL gor\n§7/vex toggle <check> - ac/kapat\n§7/sus - supheliler"),
                                false);
                        return 1;
                    })
                    .then(Commands.literal("vl")
                            .then(Commands.argument("oyuncu", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "oyuncu");
                                        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
                                        if (target == null) {
                                            ctx.getSource().sendFailure(Component.literal("Oyuncu bulunamadi: " + name));
                                            return 0;
                                        }
                                        PlayerData data = VexAntiCheat.INSTANCE.getData(target.getUUID());
                                        double vl = data == null ? 0 : data.getTotalVL();
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§8[§6VexAC§8] §e" + name + " §7VL: §c" + vl),
                                                false);
                                        return 1;
                                    })))
                    .then(Commands.literal("toggle")
                            .then(Commands.argument("check", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String cn = StringArgumentType.getString(ctx, "check").toUpperCase();
                                        try {
                                            CheckType type = CheckType.valueOf(cn);
                                            boolean cur = VexAntiCheat.INSTANCE.getConfig().isEnabled(type);
                                            VexAntiCheat.INSTANCE.getConfig().setEnabled(type, !cur);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("§8[§6VexAC§8] " + type.getName() + ": " + (!cur ? "§aACIK" : "§cKAPALI")),
                                                    false);
                                            return 1;
                                        } catch (Exception e) {
                                            ctx.getSource().sendFailure(Component.literal("Gecersiz check: " + cn));
                                            return 0;
                                        }
                                    }))));

            dispatcher.register(Commands.literal("sus")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .executes(ctx -> {
                        StringBuilder sb = new StringBuilder("§8[§6VexAC§8] §7Supheliler:\n");
                        boolean any = false;
                        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                            PlayerData d = VexAntiCheat.INSTANCE.getData(p.getUUID());
                            if (d != null && d.getTotalVL() >= 3.0) {
                                sb.append("§e").append(p.getScoreboardName())
                                        .append(" §7VL:§c").append(String.format("%.1f", d.getTotalVL())).append("\n");
                                any = true;
                            }
                        }
                        if (!any) {
                            sb.append("§atemiz, supheli yok.");
                        }
                        final String out = sb.toString();
                        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
                        return 1;
                    }));
        });
    }
}
