package com.ainative.v2.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 打包执行器的 capsule 压缩包(与 ymai-codeflow 运行器契约一致):
 * manifest.json(schemaVersion=ainative.taskPackage.v1 + fileDigests)/ workitem.md /
 * sdlc.json / identity.json / roster.json / policy.json。
 */
@Component
public class CapsuleBuilder {
    public static final String SCHEMA_VERSION = "ainative.taskPackage.v1";
    private final ObjectMapper mapper = new ObjectMapper();

    public record Capsule(byte[] bytes, String md5) {}

    public Capsule build(String title, String contentMd, List<Map<String, Object>> steps,
                         String agentName, String roleCode, String roleName,
                         List<Map<String, Object>> roster) {
        try {
            List<byte[]> entries = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String workitemMd = "# 工单\n" + title + "\n\n" + (contentMd == null ? "" : contentMd) + "\n";
            add(names, entries, "workitem.md", workitemMd.getBytes(StandardCharsets.UTF_8));
            add(names, entries, "sdlc.json", mapper.writeValueAsBytes(Map.of("steps", steps)));
            add(names, entries, "identity.json", mapper.writeValueAsBytes(Map.of(
                    "name", agentName == null ? "" : agentName,
                    "roleCode", roleCode == null ? "" : roleCode)));
            add(names, entries, "roster.json", mapper.writeValueAsBytes(Map.of("members", roster)));
            add(names, entries, "policy.json", mapper.writeValueAsBytes(Map.of()));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", SCHEMA_VERSION);
            manifest.put("roleCode", roleCode == null ? "FS_DEV" : roleCode);
            manifest.put("roleName", roleName == null ? "" : roleName);
            manifest.put("sdlcStepId", "");
            Map<String, String> digests = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                digests.put(names.get(i), "sha256:" + sha256Hex(entries.get(i)));
            }
            manifest.put("fileDigests", digests);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                // 固定条目时间戳:同一派发的包字节必须可复现(下载端按 md5 校验)
                zip.putNextEntry(entry("manifest.json"));
                zip.write(mapper.writeValueAsBytes(manifest));
                zip.closeEntry();
                for (int i = 0; i < names.size(); i++) {
                    zip.putNextEntry(entry(names.get(i)));
                    zip.write(entries.get(i));
                    zip.closeEntry();
                }
            }
            byte[] bytes = out.toByteArray();
            return new Capsule(bytes, md5Hex(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("capsule build failed", e);
        }
    }

    private static ZipEntry entry(String name) {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        return e;
    }

    private static void add(List<String> names, List<byte[]> entries, String name, byte[] data) {
        names.add(name);
        entries.add(data);
    }

    static String sha256Hex(byte[] data) {
        return hex("SHA-256", data);
    }

    static String md5Hex(byte[] data) {
        return hex("MD5", data);
    }

    private static String hex(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return new BigInteger(1, md.digest(data)).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
