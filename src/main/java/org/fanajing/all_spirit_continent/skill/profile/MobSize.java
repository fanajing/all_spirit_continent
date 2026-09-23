package org.fanajing.all_spirit_continent.skill.profile;

/** Mob 体型分类（按 hitBox 体积）。由 MobObserver 根据实体体积自动归类。 */
public enum MobSize {
    SMALL("小型", "< 0.8 × 0.8 × 0.8"),
    MEDIUM("中型", "0.8 ~ 1.5"),
    LARGE("大型", "≥ 1.5");

    public final String displayName;
    public final String hint;

    MobSize(String displayName, String hint) {
        this.displayName = displayName;
        this.hint = hint;
    }
}