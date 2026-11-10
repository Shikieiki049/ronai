package com.ronstrat;

import java.util.Locale;

/**
 * Difficulty tiers. Like the AoE2 ladder, the tiers differ in decision quality and reaction speed,
 * never in free resources: every tier pays normal costs and obeys the same tech tree as a human.
 */
public enum Difficulty {
    EASY("简单"), NORMAL("普通"), HARD("困难"), EXPERT("专家");

    public final String zh;

    Difficulty(String zh) { this.zh = zh; }

    public static Difficulty parse(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "e": case "easy": case "简单": return EASY;
            case "n": case "normal": case "普通": return NORMAL;
            case "h": case "hard": case "困难": return HARD;
            case "x": case "expert": case "extreme": case "专家": return EXPERT;
            default: throw new IllegalArgumentException("unknown difficulty: " + s);
        }
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }
}
