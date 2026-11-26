package com.ainative.v2.artifact;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 产物存储:本地受控目录(默认 <workdir>/artifacts/<dispatchId>/)。
 * 对象存储(S3/MinIO)接入留此接口扩展点。
 */
@Component
public class ArtifactStorage {
    private final Path root;

    public ArtifactStorage(@Value("${ainative.artifact-dir:}") String configuredDir) {
        String dir = configuredDir == null || configuredDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/ainative-artifacts"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath();
    }

    public Stored store(long dispatchId, String filename, byte[] content) throws IOException {
        return storeIn(String.valueOf(dispatchId), filename, content);
    }

    /**
     * 落在 <root>/<namespace>/ 下。namespace 可用 '/' 分层(如 conversations/12)。
     *
     * <p>会话产物必须与派发产物分目录:两者都是数字 id,共用一层会互相覆盖。
     */
    public Stored storeIn(String namespace, String filename, byte[] content) throws IOException {
        Path dir = root;
        if (namespace != null && !namespace.isBlank()) {
            for (String segment : namespace.split("/")) {
                if (!segment.isBlank()) {
                    dir = dir.resolve(segment.replaceAll("[^A-Za-z0-9._-]", "_"));
                }
            }
        }
        Files.createDirectories(dir);
        // 文件名可带路径层级(如 tools/apireg/src/apireg/assertions.py):
        // 产物要能按原结构读回去 —— 之前把 '/' 换成 '_',43 个文件全平铺在一个目录里,
        // 数字员工读回来根本拼不出原来的目录结构(2026-11-26 返工时暴露)。
        Path target = dir;
        String name = filename == null || filename.isBlank() ? "artifact" : filename;
        for (String segment : name.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            target = target.resolve(segment.replaceAll("[^A-Za-z0-9._-]", "_"));
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.write(target, content);
        return new Stored(root.relativize(target).toString(), content.length);
    }

    /** 按 objectKey 读回产物内容。objectKey 是相对 root 的路径,来自 as_artifact.object_key。 */
    public byte[] read(String objectKey) throws IOException {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IOException("objectKey 为空");
        }
        Path target = root.resolve(objectKey).normalize();
        // 越界防护:objectKey 来自数据库,但仍要确保解析后仍在 root 之内
        if (!target.startsWith(root)) {
            throw new IOException("objectKey 越界: " + objectKey);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("产物不存在: " + objectKey);
        }
        return Files.readAllBytes(target);
    }

    public record Stored(String objectKey, long sizeBytes) {}
}
