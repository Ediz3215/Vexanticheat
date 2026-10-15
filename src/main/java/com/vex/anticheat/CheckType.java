package com.vex.anticheat;

public enum CheckType {
    SPEED("Speed"),
    FLY("Fly"),
    MULTIACTION("MultiAction"),
    REACH("Reach"),
    KILLAURA("KillAura"),
    CRITICALS("Criticals"),
    SCAFFOLD("Scaffold"),
    TIMER("Timer"),
    VELOCITY("Velocity"),
    NOSLOW("NoSlow"),
    GROUNDSPOOF("GroundSpoof"),
    HIGHJUMP("HighJump"),
    STEP("Step"),
    SPRINT("Sprint"),
    INVENTORYMOVE("InventoryMove"),
    LIQUIDWALK("LiquidWalk"),
    BADPACKETS("BadPackets"),
    TRIGGERBOT("Triggerbot"),
    FASTBREAK("FastBreak");

    private final String name;

    CheckType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
