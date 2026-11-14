package com.ainative.v2.artifact;

import com.ainative.v2.common.ApiResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行器产物回传端点(带 HMAC 签名,与包下载同一套鉴权)。 */
@RestController
@RequestMapping("/api/daemon/dispatches")
public class DaemonArtifactController {
    private final JdbcTemplate jdbc;
    private final ArtifactIngestService ingest;
    private final com.ainative.v2.dispatch.PackageSigner signer;

    public DaemonArtifactController(JdbcTemplate jdbc, ArtifactIngestService ingest,
                                    com.ainative.v2.dispatch.PackageSigner signer) {
        this.jdbc = jdbc;
        this.ingest = ingest;
        this.signer = signer;
    }

    @PostMapping("/{dispatchId}/artifacts")
    public ApiResult<Map<String, Object>> upload(@PathVariable long dispatchId,
                                                 @RequestParam(name = "k", required = false) String key,
                                                 @RequestParam("file") MultipartFile file) {
        Long ticketId = ticketIdOf(dispatchId);
        if (ticketId == null) {
            return ApiResult.fail("14004", "派发不存在");
        }
        if (!signOk(dispatchId, key)) {
            return ApiResult.fail("10403", "上传签名无效");
        }
        ArtifactStorage.Stored stored = ingest.ingest(dispatchId, ticketId, file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchId", dispatchId);
        out.put("objectKey", stored.objectKey());
        out.put("sizeBytes", stored.sizeBytes());
        return ApiResult.ok(out);
    }

    private Long ticketIdOf(long dispatchId) {
        try {
            return jdbc.queryForObject("SELECT ticket_id FROM rt_run WHERE id=?", Long.class, dispatchId);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean signOk(long dispatchId, String key) {
        return signer.verify(dispatchId + "." + attemptOf(dispatchId), key);
    }

    private int attemptOf(long dispatchId) {
        try {
            Integer a = jdbc.queryForObject("SELECT attempt FROM rt_run WHERE id=?", Integer.class, dispatchId);
            return a == null ? 1 : a;
        } catch (Exception e) {
            return 1;
        }
    }
}
