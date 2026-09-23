package org.fanajing.all_spirit_continent.skill.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * §10 范例与现行规则系统的校验报表（引擎文档 §10 + §4.3）。
 * <p>
 * 用途：测试 §10 完整范例（巫妖王 9 环）按当前 {@link RingPositionRules} 严格校验
 * 的对齐报告。每个范例技能跑 {@code SkillValidator.collect}，输出「通过/失败 + 错误列表」。
 * <p>
 * 典型报告（logs/SkillFixture_Report.log）：
 * <pre>{@code
 * §10 巫妖王 9 环技能组校验报告（共 11 个）
 * [1 环] 昊天·巫妖霜锤 - 原语数 [1,1] 实际 2 -> 不通过（需 1 原语）
 * [2 环] 昊天·巫妖霜盾 - 通过
 * ...
 * [9 环] 昊天·巫妖天灾 - 9 环禁止 SUMMON_ENTITY -> 不通过
 * 通过率: 1/11
 * }</pre>
 * <p>
 * 本类只跑校验，不修改任何数据；可在测试或管理员命令中调用。
 */
public final class SkillFixture {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** §10.2 完整范例文件（嵌入在 mod_skills/ 内，loadAndReport 时使用） */
    public static final Path DEFAULT_FIXTURE = ModDataSkillLoader.CONFIG_DIR
            .resolve("twilightforest_lich.json");

    /**
     * 单条报告。errors 是环位排除 ring 偏差（必出现），balanceErrors 是 §13.6 被动平衡软警告（仅当出现时列出）。
     * <p>
     * 注：passes 只受 errors 影响，balanceErrors 只用于诊断输出，不会让整条判定为 REJECT。
     */
    public record Entry(
            int slot,
            String name,
            boolean passes,
            int executionCount,
            List<String> errors,
            List<String> balanceErrors) {
    }

    /** 整体报告 */
    public record Report(List<Entry> entries, int passed, int total) {
        public double passRate() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
        /** 至少有一条 §13.6 被动平衡偏差的条目数 */
        public long balanceWarnings() {
            return entries.stream().filter(e -> !e.balanceErrors().isEmpty()).count();
        }
    }

    private SkillFixture() {
    }

    /**
     * 加载 + 校验 §10.2 巫妖王 9 环范例（默认 fixture 文件）。
     *
     * @return Report；fixture 文件不存在时返回空报告（total=0）
     */
    public static Report loadAndReport() {
        return loadAndReport(DEFAULT_FIXTURE);
    }

    /**
     * 加载 + 校验任意 fixture 文件（与 mod_skills/*.json 同格式）。
     */
    public static Report loadAndReport(Path path) {
        List<Entry> entries = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) {
            LOGGER.warn("§10 fixture 文件不存在: {}", path);
            return new Report(entries, 0, 0);
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String mobId = root.has("mob_id") ? root.get("mob_id").getAsString().toLowerCase(Locale.ROOT) : "?";
            String display = root.has("display_name") ? root.get("display_name").getAsString() : mobId;
            int passed = 0;
            int total = 0;
            for (var el : root.getAsJsonArray("templates")) {
                JsonObject obj = el.getAsJsonObject();
                int slot = obj.has("slot") ? obj.get("slot").getAsInt() : -1;
                String name = obj.has("name") ? obj.get("name").getAsString() : "?";
                SkillData data;
                try {
                    data = SkillData.fromAiJson(obj, "", mobId, "");
                } catch (Exception parseEx) {
                    entries.add(new Entry(slot, name, false, 0,
                            List.of("SkillData.fromAiJson 失败: " + parseEx.getClass().getSimpleName()),
                            List.of()));
                    total++;
                    continue;
                }
                List<String> errors = SkillValidator.collect(data, slot, null);
                List<String> balanceErrors = PassiveBalanceValidator.collect(data);
                boolean pass = errors.isEmpty();
                int execCount = data.execution() == null ? 0 : data.execution().size();
                entries.add(new Entry(slot, name, pass, execCount, errors, balanceErrors));
                total++;
                if (pass) passed++;
            }
            Report report = new Report(entries, passed, total);
            LOGGER.info("§10 {} ({} 个) 校验报告：通过 {}/{} ({}) | 被动平衡警告 {} 条",
                    display, total, passed, total, String.format(Locale.ROOT, "%.1f%%", report.passRate() * 100),
                    report.balanceWarnings());
            for (Entry e : entries) {
                if (e.passes() && e.balanceErrors().isEmpty()) continue;
                StringBuilder msg = new StringBuilder();
                if (!e.passes()) {
                    msg.append("✗ [第 ").append(e.slot()).append(" 环] ").append(e.name())
                            .append(" - 原语数 ").append(e.executionCount())
                            .append(" → ").append(e.errors());
                }
                if (!e.balanceErrors().isEmpty()) {
                    if (msg.length() > 0) msg.append(" | ");
                    msg.append("§13.6 ").append(e.balanceErrors());
                }
                LOGGER.warn("  {}", msg);
            }
            return report;
        } catch (Exception e) {
            LOGGER.warn("§10 fixture 读取失败: {} - {}", path, e.getClass().getSimpleName());
            return new Report(entries, 0, 0);
        }
    }

    /**
     * 把报告渲染为可读字符串（多行）。
     */
    public static String render(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("§10 巫妖王 9 环技能组校验报告（共 ").append(report.total()).append(" 个）\n");
        for (Entry e : report.entries()) {
            sb.append(String.format(Locale.ROOT, "[第 %d 环] %s - 原语数 %d %s\n",
                    e.slot(), e.name(), e.executionCount(),
                    e.passes() ? "✓ 通过" : "✗ " + String.join("; ", e.errors())));
            if (!e.balanceErrors().isEmpty()) {
                sb.append("    §13.6 平衡警告: ").append(String.join("; ", e.balanceErrors())).append("\n");
            }
        }
        sb.append(String.format(Locale.ROOT, "通过率: %d/%d (%.1f%%) | 平衡警告 %d 条\n",
                report.passed(), report.total(), report.passRate() * 100, report.balanceWarnings()));
        return sb.toString();
    }
}