package org.fanajing.all_spirit_continent.skill.profile;

/** Mob 攻击模式（近战 / 远程 / 混合）。 */
public enum AttackPattern {
    MELEE("近战", "以 melee 攻击为主"),
    RANGED("远程", "以 projectile 攻击为主"),
    MIXED("混合", "近战 + 远程兼具");

    public final String displayName;
    public final String hint;

    AttackPattern(String displayName, String hint) {
        this.displayName = displayName;
        this.hint = hint;
    }
}