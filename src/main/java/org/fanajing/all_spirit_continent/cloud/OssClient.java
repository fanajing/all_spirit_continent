package org.fanajing.all_spirit_continent.cloud;

import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.config.CloudConfig;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 阿里云 OSS REST 直连客户端（不引入 SDK）。
 * <ul>
 *   <li>匿名 GET：下载 public_skills.json / blacklist.json / version.json（bucket 公共读）</li>
 *   <li>签名 PUT：OSS V1 签名（HMAC-SHA1）写回合并后的数据（需 AccessKey）</li>
 * </ul>
 * 凭据为空时写操作直接失败（日志警告，不影响读与本地功能）。
 */
public class OssClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final String endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String accessKeySecret;

    public OssClient(String endpoint, String bucket, String accessKeyId, String accessKeySecret) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.trim();
        this.accessKeySecret = accessKeySecret == null ? "" : accessKeySecret.trim();
    }

    /** 从模组配置构建（endpoint 末尾斜杠去除） */
    public static OssClient fromConfig() {
        String ep = CloudConfig.OSS_ENDPOINT.get();
        while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        return new OssClient(ep, CloudConfig.OSS_BUCKET.get(),
                Credentials.ossAccessKeyId(), Credentials.ossAccessKeySecret());
    }

    public boolean isConfigured() {
        return !endpoint.isEmpty() && !bucket.isEmpty();
    }

    public boolean canWrite() {
        return isConfigured() && !accessKeyId.isEmpty() && !accessKeySecret.isEmpty();
    }

    /** 匿名下载对象内容；不存在/失败返回 empty（日志只记录状态码，不含敏感信息） */
    public CompletableFuture<Optional<String>> get(String objectKey) {
        if (!isConfigured()) return CompletableFuture.completedFuture(Optional.empty());
        String url = endpoint + "/" + encodeKey(objectKey);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        return Optional.of(resp.body());
                    }
                    if (resp.statusCode() != 404) {
                        LOGGER.warn("OSS GET {} 失败: HTTP {}", objectKey, resp.statusCode());
                    }
                    return Optional.<String>empty();
                })
                .exceptionally(ex -> {
                    LOGGER.warn("OSS GET {} 异常: {}", objectKey, ex.getClass().getSimpleName());
                    return Optional.empty();
                });
    }

    /** 签名上传对象内容；成功返回 true */
    public CompletableFuture<Boolean> put(String objectKey, String content) {
        if (!canWrite()) {
            LOGGER.warn("OSS 写操作被禁用：未配置 AccessKey（上传/评分同步将跳过）");
            return CompletableFuture.completedFuture(false);
        }
        String url = endpoint + "/" + encodeKey(objectKey);
        String contentType = "application/json";
        String date = RFC1123.format(ZonedDateTime.now(ZoneOffset.UTC));
        String canonicalResource = "/" + bucket + "/" + objectKey;
        String signature = sign("PUT", "", contentType, date, canonicalResource);
        String authorization = "OSS " + accessKeyId + ":" + signature;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .header("Date", date)
                .header("Authorization", authorization)
                .PUT(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code == 200 || code == 204) {
                        return true;
                    }
                    LOGGER.warn("OSS PUT {} 失败: HTTP {} (body: {})", objectKey, code, truncate(resp.body()));
                    return false;
                })
                .exceptionally(ex -> {
                    LOGGER.warn("OSS PUT {} 异常: {}", objectKey, ex.getClass().getSimpleName());
                    return false;
                });
    }

    /** OSS V1 签名（使用本实例的 AccessKeySecret） */
    private String sign(String method, String contentMd5, String contentType, String date, String canonicalResource) {
        String stringToSign = method + "\n"
                + (contentMd5 == null ? "" : contentMd5) + "\n"
                + (contentType == null ? "" : contentType) + "\n"
                + date + "\n"
                + canonicalResource;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("OSS 签名失败", e);
        }
    }

    /** object key URL 编码（保留 '/'） */
    private static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (String seg : key.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
