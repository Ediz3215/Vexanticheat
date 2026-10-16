package com.vex.anticheat;

import java.util.EnumMap;
import java.util.Map;

public class CheckConfig {
    private final Map<CheckType, Boolean> enabled = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Double> vlAmount = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Double> vlThreshold = new EnumMap<>(CheckType.class);

    public CheckConfig() {
        for (CheckType t : CheckType.values()) {
            enabled.put(t, true);
            vlAmount.put(t, 1.0);
            vlThreshold.put(t, 50.0);
        }
        vlAmount.put(CheckType.SPEED, 1.2);
        vlAmount.put(CheckType.FLY, 1.5);
        vlAmount.put(CheckType.REACH, 2.0);
        vlAmount.put(CheckType.KILLAURA, 1.5);
        vlAmount.put(CheckType.SCAFFOLD, 1.5);
        vlAmount.put(CheckType.TIMER, 2.0);
        vlAmount.put(CheckType.VELOCITY, 3.0);
        vlAmount.put(CheckType.TRIGGERBOT, 1.5);
        vlAmount.put(CheckType.MULTIACTION, 2.0);
        vlAmount.put(CheckType.LIQUIDWALK, 2.0);
    }

    public boolean isEnabled(CheckType t) {
        return enabled.getOrDefault(t, true);
    }

    public void setEnabled(CheckType t, boolean v) {
        enabled.put(t, v);
    }

    public double getVlAmount(CheckType t) {
        return vlAmount.getOrDefault(t, 1.0);
    }

    public double getVlThreshold(CheckType t) {
        return vlThreshold.getOrDefault(t, 50.0);
    }
}
