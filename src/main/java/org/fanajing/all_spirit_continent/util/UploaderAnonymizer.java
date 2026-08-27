package org.fanajing.all_spirit_continent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 上传者匿名化：绝不将玩家游戏 ID / UUID 明文写入云端数据。
 * 以 SHA-256(玩家UUID + 固定盐) 前 16 位十六进制作为匿名标识：
 * <ul>
 *   <li>匿名：无法从哈希反推真实玩家；</li>
 *   <li>可追踪：同一玩家上传的魂技哈希一致，后台可据此统计 / 封禁违规上传者。</li>
 * </ul>
 */
public final class UploaderAnonymizer {

    private static final String SALT = "all-spirit-continent-anon@2026";

    private UploaderAnonymizer() {
    }

    /** 生成上传者匿名标识；玩家为空或计算失败返回空串 */
    public static String hash(UUID playerId) {
        if (playerId == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SALT + playerId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
