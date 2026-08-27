package org.fanajing.all_spirit_continent.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 内置作者凭据的混淆容器（随 jar 分发，供共享阵营玩家免配置使用）。
 * 统一管理：作者 AI API Key、OSS AccessKey ID、OSS AccessKey Secret。
 * <p>
 * 安全边界（务必知晓）：
 * <ul>
 *   <li>只能挡住普通玩家解包后"一眼看出凭据"，属于提高门槛，不是加密；</li>
 *   <li>会反编译 / 内存 dump / HTTPS 抓包（Authorization 头）的人仍可还原，请勿把
 *       高权限凭据打包发布，建议在 DeepSeek 控制台设置余额上限与配额、OSS 使用受限子账号；</li>
 *   <li>注入优先级最低，仅当环境变量与配置文件均无值时才使用（见 {@link Credentials}）。</li>
 * </ul>
 * <p>
 * 混淆算法（对称、可逆）：每字节 {@code enc = (c ^ 0xA5) + 29}，再 Base64。
 * <p>
 * 生成方式（编译后执行，凭据不出本机）：
 * <pre>
 *   set ASC_AI_AUTHOR_API_KEY=sk-xxxx
 *   set ASC_OSS_ACCESS_KEY_ID=xxxx
 *   set ASC_OSS_ACCESS_KEY_SECRET=xxxx
 *   java -cp build/classes/java/main org.fanajing.all_spirit_continent.cloud.ObfuscatedSecrets -gen
 * </pre>
 * 输出三行 BLOB，分别填入下方三个常量（勿把明文凭据写入代码）。
 * 自检：{@code ... ObfuscatedSecrets -check}
 */
public final class ObfuscatedSecrets {

    private static final int MASK = 0xA5;
    private static final int SHIFT = 29;

    /** 作者 AI API Key（混淆后；留空则跳过内置兜底） */
    private static final String AI_KEY_BLOB =
            "8+ulst3k3q65sN3jubStruPer7rjubnjuuG0ud253q3gr60=";

    /** OSS AccessKey ID（混淆后；留空则跳过内置兜底） */
    private static final String OSS_ID_BLOB =
            "Bg4BCa3usP7/AO7kBeG07f6z8uDh7OAE";

    /** OSS AccessKey Secret（混淆后；留空则跳过内置兜底） */
    private static final String OSS_SECRET_BLOB =
            "8Qb0363+7N/tE68ZEv0NARKyA+YRDwDu3vzo8QH+";

    private ObfuscatedSecrets() {
    }

    /** 还原作者 AI API Key；缺失/损坏返回空串（由上层降级处理） */
    public static String aiKey() {
        return decode(AI_KEY_BLOB);
    }

    /** 还原 OSS AccessKey ID；缺失/损坏返回空串 */
    public static String ossAccessKeyId() {
        return decode(OSS_ID_BLOB);
    }

    /** 还原 OSS AccessKey Secret；缺失/损坏返回空串 */
    public static String ossAccessKeySecret() {
        return decode(OSS_SECRET_BLOB);
    }

    private static String encode(String plain) {
        byte[] src = plain.getBytes(StandardCharsets.US_ASCII);
        byte[] enc = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            enc[i] = (byte) (((src[i] & 0xFF) ^ MASK) + SHIFT);
        }
        return Base64.getEncoder().encodeToString(enc);
    }

    private static String decode(String blob) {
        if (blob == null || blob.isEmpty()) return "";
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(blob);
        } catch (IllegalArgumentException e) {
            return "";
        }
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int b = raw[i] & 0xFF;
            b = (b - SHIFT) & 0xFF;
            out[i] = (byte) (b ^ MASK);
        }
        return new String(out, StandardCharsets.US_ASCII);
    }

    /** 生成器 / 自检入口 */
    public static void main(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("用法:");
            System.err.println("  " + ObfuscatedSecrets.class.getName() + " -check                 # 自检往返");
            System.err.println("  " + ObfuscatedSecrets.class.getName() + " -gen                   # 从环境变量生成三个 BLOB");
            System.err.println("  " + ObfuscatedSecrets.class.getName() + " <明文>                 # 单值编码输出");
            return;
        }
        if ("-check".equals(args[0])) {
            String probe = "sk-selfcheck-0123456789abcdef";
            String blob = encode(probe);
            String dec = decode(blob);
            System.out.println(probe.equals(dec) ? "SELFCHECK_OK" : "SELFCHECK_FAIL: " + dec);
            return;
        }
        if ("-gen".equals(args[0])) {
            gen("AI_KEY", System.getenv("ASC_AI_AUTHOR_API_KEY"));
            gen("OSS_ID", System.getenv("ASC_OSS_ACCESS_KEY_ID"));
            gen("OSS_SECRET", System.getenv("ASC_OSS_ACCESS_KEY_SECRET"));
            return;
        }
        System.out.println(encode(args[0]));
    }

    private static void gen(String label, String plain) {
        if (plain == null || plain.isBlank()) {
            System.out.println(label + "=<跳过:未设置环境变量>");
            return;
        }
        System.out.println(label + "_BLOB=" + encode(plain.trim()));
    }
}
