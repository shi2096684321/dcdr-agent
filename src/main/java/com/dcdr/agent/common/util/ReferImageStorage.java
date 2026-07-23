package com.dcdr.agent.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 参考图存储: 将前端传来的 base64 data URL 落盘, 数据库仅保存文件路径,
 * 避免超大 base64 写入 VARCHAR 列导致超长错误, 也避免数据库急剧膨胀。
 * 模型调用仍使用请求体中的原始 data URL, 不受此处影响。
 */
@Component
public class ReferImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ReferImageStorage.class);
    private final Path dir;

    public ReferImageStorage(@Value("${agent.storage.image-dir:./data/images}") String imageDir) {
        this.dir = Path.of(imageDir);
    }

    /**
     * 若为 base64 data URL 则写入磁盘并返回文件路径; 否则(远程 URL/空)原样返回或返回 null。
     * 基于内容哈希命名, 相同图片不会产生重复文件。
     */
    public String save(String referImage) {
        if (referImage == null || referImage.isBlank()) {
            return null;
        }
        if (!referImage.startsWith("data:image")) {
            return referImage;
        }
        try {
            int comma = referImage.indexOf(',');
            String meta = referImage.substring(0, comma);
            String base64 = referImage.substring(comma + 1);
            byte[] data = Base64.getDecoder().decode(base64);

            String ext = "img";
            if (meta.contains("png")) ext = "png";
            else if (meta.contains("jpeg") || meta.contains("jpg")) ext = "jpg";
            else if (meta.contains("webp")) ext = "webp";

            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            String filename = "ref_" + hash + "." + ext;

            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            if (!Files.exists(target)) {
                Files.write(target, data);
                log.debug("参考图已落盘: {}", target);
            }
            return target.toString();
        } catch (IOException | RuntimeException e) {
            log.warn("参考图落盘失败, 回退为原始 data URL", e);
            return referImage;
        } catch (Exception e) {
            log.warn("参考图落盘失败(哈希计算异常), 回退为原始 data URL", e);
            return referImage;
        }
    }
}
