package org.fanajing.all_spirit_continent.skill;

/**
 * 技能原语参数定义。参数名与 AI JSON 输出约定一致（snake_case）。
 * 由 SkillData 解析时校验，SkillExecutor 执行时按类型取值。
 */
public enum Param {
    /** 数值（伤害量/治疗量/击退力度等），double */
    VALUE("value"),
    /** 半径（格），double */
    RADIUS("radius"),
    /** 时长（tick），int */
    DURATION("duration"),
    /** 目标：SELF / TARGET / TARGET_AREA，String */
    TARGET("target"),
    /** 伤害类型：MELEE / MAGIC / FIRE / EXPLOSION，String */
    DAMAGE_TYPE("damage_type"),
    /** 暴击率 0.0~1.0，double */
    CRIT_CHANCE("crit_chance"),
    /** 属性名：ATTACK_DAMAGE / MOVEMENT_SPEED / MAX_HEALTH，String */
    ATTRIBUTE("attribute"),
    /** 百分比（0.0~1.0 或 >1 按倍率），double */
    PERCENT("percent"),
    /** 距离（格），double */
    DISTANCE("distance"),
    /** 方向：FORWARD / BACKWARD / TOWARD_TARGET / AWAY_FROM_TARGET / UP，String */
    DIRECTION("direction"),
    /** 实体注册名，String */
    ENTITY_ID("entity_id"),
    /** 数量，int */
    COUNT("count"),
    /** 投射物注册名，String */
    PROJECTILE_TYPE("projectile_type"),
    /** 速度倍率，double */
    SPEED("speed"),
    /** MobEffect 注册名，String */
    EFFECT("effect"),
    /** 效果等级（0 起），int */
    AMPLIFIER("amplifier"),
    /** 生长阶段增量，int */
    GROWTH_STAGE("growth_stage"),
    /** 音效注册名，String */
    SOUND("sound"),
    /** 粒子类型注册名，String */
    PARTICLE("particle");

    private final String key;

    Param(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 按 JSON 键名解析参数；未知返回 null（SkillData 校验时忽略未知参数） */
    public static Param byKey(String key) {
        if (key == null) return null;
        for (Param p : values()) {
            if (p.key.equals(key)) return p;
        }
        return null;
    }
}
