package org.fanajing.all_spirit_continent.skill;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 魂技动作原语（AI 输出标准动作序列的最小单元）。
 * <p>
 * 七大系别 + 通用 MC 机制，共约 47 个原语。每个原语声明允许出现的参数
 * （SkillData 解析时校验，非法原语/参数直接丢弃该步骤并日志警告）。
 * 参数名与 AI JSON 输出约定一致（snake_case）。
 */
public enum SkillPrimitive {

    // ===== 控制系 =====
    /** 束缚：减速 + 移动锁定标记（target 目标） */
    BIND(Category.CONTROL, Param.TARGET, Param.DURATION, Param.RADIUS),
    /** 沉默：禁止目标释放魂技标记 */
    SILENCE(Category.CONTROL, Param.TARGET, Param.DURATION),
    /** 致盲：目标施加失明效果 */
    BLIND(Category.CONTROL, Param.TARGET, Param.DURATION, Param.AMPLIFIER),
    /** 定身：移动完全锁定（比束缚更强） */
    ROOT(Category.CONTROL, Param.TARGET, Param.DURATION),
    /** 减速：目标施加缓慢效果 */
    SLOW(Category.CONTROL, Param.TARGET, Param.DURATION, Param.AMPLIFIER),
    /** 虚弱：目标施加虚弱效果 */
    WEAKEN(Category.CONTROL, Param.TARGET, Param.DURATION, Param.AMPLIFIER),
    /** 混乱：目标施加反胃效果 */
    CONFUSE(Category.CONTROL, Param.TARGET, Param.DURATION),
    /** 缴械：目标主手物品掉落并清空 */
    DISARM(Category.CONTROL, Param.TARGET, Param.RADIUS),

    // ===== 强攻系 =====
    /** 爆发：单体高额伤害 */
    BURST(Category.STRONG, Param.TARGET, Param.VALUE, Param.DAMAGE_TYPE, Param.CRIT_CHANCE),
    /** 范围爆发：以自身/目标为中心的范围伤害 */
    AOE_BURST(Category.STRONG, Param.TARGET, Param.VALUE, Param.RADIUS, Param.DAMAGE_TYPE),
    /** 范围伤害：通用范围伤害原语（AI 常用输出） */
    AOE_DAMAGE(Category.STRONG, Param.TARGET, Param.VALUE, Param.RADIUS),
    /** 破甲：目标护甲值削弱 + 额外伤害 */
    ARMOR_BREAK(Category.STRONG, Param.TARGET, Param.VALUE, Param.DURATION),
    /** 处决：目标生命值低于阈值时直接斩杀 */
    EXECUTE(Category.STRONG, Param.TARGET, Param.VALUE, Param.CRIT_CHANCE),
    /** 眩晕：目标定身 + 行动锁定 */
    STUN(Category.STRONG, Param.TARGET, Param.DURATION),
    /** 冲锋：向目标突进并对沿途实体造成伤害 */
    CHARGE(Category.STRONG, Param.TARGET, Param.DISTANCE, Param.VALUE, Param.SPEED),

    // ===== 敏攻系 =====
    /** 突进：朝指定方向位移 */
    DASH(Category.AGILE, Param.DISTANCE, Param.DIRECTION, Param.SPEED),
    /** 连击：对目标造成多段伤害 */
    COMBO(Category.AGILE, Param.TARGET, Param.VALUE, Param.COUNT),
    /** 幻影：短暂隐身 + 分身粒子 */
    PHANTOM(Category.AGILE, Param.DURATION),
    /** 闪避：短暂伤害免疫标记 + 清除负面效果 */
    EVADE(Category.AGILE, Param.DURATION),
    /** 背刺：目标背后时伤害加成 */
    BACKSTAB(Category.AGILE, Param.TARGET, Param.VALUE, Param.CRIT_CHANCE),
    /** 加速：自身施加迅捷效果 */
    ACCELERATE(Category.AGILE, Param.DURATION, Param.AMPLIFIER, Param.PERCENT),

    // ===== 辅助系 =====
    /** 属性增益：提升自身指定属性 */
    BUFF_STATS(Category.SUPPORT, Param.ATTRIBUTE, Param.PERCENT, Param.DURATION, Param.TARGET),
    /** 范围全体增益：提升范围内友方属性 */
    BUFF_ALL(Category.SUPPORT, Param.ATTRIBUTE, Param.PERCENT, Param.DURATION, Param.RADIUS),
    /** 护盾转移：将自身伤害吸收转化为队友护盾（也可用作被动亡魂护盾） */
    SHIELD_TRANSFER(Category.SUPPORT, Param.TARGET, Param.VALUE, Param.DURATION, Param.AMPLIFIER),
    /** 净化：移除目标负面效果 */
    CLEANSE(Category.SUPPORT, Param.TARGET, Param.RADIUS),
    /** 持续治疗：目标获得生命恢复效果 */
    HOT(Category.SUPPORT, Param.TARGET, Param.VALUE, Param.DURATION),
    /** 复苏：治疗重伤目标（大幅回血） */
    REVIVE(Category.SUPPORT, Param.TARGET, Param.VALUE),
    /** 冷却缩减：刷新目标魂技冷却标记 */
    CD_REDUCE(Category.SUPPORT, Param.TARGET, Param.RADIUS),

    // ===== 防御系 =====
    /** 嘲讽：强制周围敌对生物攻击自身 */
    TAUNT(Category.DEFENSE, Param.RADIUS, Param.DURATION),
    /** 反弹：受击反弹伤害标记 */
    REFLECT(Category.DEFENSE, Param.DURATION, Param.PERCENT),
    /** 屏障：自身获得抗性提升 */
    BARRIER(Category.DEFENSE, Param.DURATION, Param.AMPLIFIER),
    /** 铁身：自身获得抗性提升 + 减速 */
    IRON_BODY(Category.DEFENSE, Param.DURATION, Param.AMPLIFIER),
    /** 硬化：自身获得抗性提升（低幅）+ 缓慢 */
    HARDEN(Category.DEFENSE, Param.DURATION, Param.AMPLIFIER),

    // ===== 生活系 =====
    /** 作物催熟：范围内作物生长 +1 阶段 */
    CROP_GROW(Category.LIFE, Param.RADIUS, Param.GROWTH_STAGE),
    /** 食物赐福：回复饥饿值与饱食度 */
    FOOD_BLESS(Category.LIFE, Param.VALUE, Param.RADIUS),
    /** 收割：自动收割范围内成熟作物并掉落 */
    HARVEST(Category.LIFE, Param.RADIUS),
    /** 骨粉效果：范围内作物催熟（等效骨粉） */
    BONEMEAL(Category.LIFE, Param.RADIUS, Param.COUNT),

    // ===== 通用 MC 机制 =====
    /** 召唤实体：在自身附近生成临时实体（entity_id 注册名） */
    SUMMON_ENTITY(Category.GENERAL, Param.ENTITY_ID, Param.COUNT, Param.DURATION),
    /** 发射投射物：向目标发射（projectile_type 注册名） */
    PROJECTILE(Category.GENERAL, Param.TARGET, Param.PROJECTILE_TYPE, Param.SPEED, Param.VALUE),
    /** 爆炸：产生爆炸（radius 为威力） */
    EXPLOSION(Category.GENERAL, Param.RADIUS, Param.VALUE),
    /** 点燃：点燃目标或周围方块/实体 */
    FIRE(Category.GENERAL, Param.TARGET, Param.DURATION, Param.RADIUS),
    /** 音效：播放指定音效 */
    SOUND(Category.GENERAL, Param.SOUND, Param.RADIUS),
    /** 粒子：播放指定粒子效果 */
    PARTICLE(Category.GENERAL, Param.PARTICLE, Param.RADIUS, Param.COUNT),
    /** 传送：将自身/目标传送到指定方向距离 */
    TELEPORT(Category.GENERAL, Param.TARGET, Param.DISTANCE, Param.DIRECTION),
    /** 牵引：将周围实体拉向自身 */
    PULL(Category.GENERAL, Param.RADIUS, Param.VALUE),
    /** 击退：击退目标/周围实体 */
    KNOCKBACK(Category.GENERAL, Param.TARGET, Param.VALUE, Param.RADIUS),
    /** 药水效果：通用药水效果施加（effect 为 MobEffect 注册名） */
    POTION(Category.GENERAL, Param.TARGET, Param.EFFECT, Param.DURATION, Param.AMPLIFIER, Param.RADIUS);

    /** 原语所属系别 */
    private final Category category;
    /** 允许出现的参数集 */
    private final Param[] allowed;

    SkillPrimitive(Category category, Param... allowed) {
        this.category = category;
        this.allowed = allowed;
    }

    public Category category() {
        return category;
    }

    public Param[] allowedParams() {
        return allowed;
    }

    /** 该原语是否允许某参数 */
    public boolean allows(Param param) {
        for (Param p : allowed) {
            if (p == param) return true;
        }
        return false;
    }

    /** 按字符串名解析原语（不区分大小写）；非法返回 null */
    public static SkillPrimitive byName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 可用于融合被动（passive）的原语白名单：仅自身增益/治疗/防御/净化/表现类 */
    private static final Set<SkillPrimitive> PASSIVE_ALLOWED = EnumSet.of(
            ACCELERATE, BUFF_STATS, BUFF_ALL, HOT, SHIELD_TRANSFER, CLEANSE,
            REFLECT, BARRIER, PHANTOM, PARTICLE);

    /** 该原语是否允许出现在融合被动（passive）中 */
    public static boolean isPassiveAllowed(SkillPrimitive p) {
        return p != null && PASSIVE_ALLOWED.contains(p);
    }

    /** 原语系别 */
    public enum Category {
        CONTROL("control"),
        STRONG("strong"),
        AGILE("agile"),
        SUPPORT("support"),
        DEFENSE("defense"),
        LIFE("life"),
        GENERAL("general");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        /** 语言键后缀（用于原语系别显示名） */
        public String key() {
            return key;
        }
    }
}
