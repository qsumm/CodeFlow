package com.ainative.v2.daemon;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 执行器下载 capsule 包(URL 自带 HMAC 签名,无需登录态)。 */
@RestController
@RequestMapping("/api/daemon")
public class DaemonPackageController {
    private final com.ainative.v2.dispatch.DispatchLaunchService launchService;
    private final JdbcTemplate jdbc;

    public DaemonPackageController(com.ainative.v2.dispatch.DispatchLaunchService launchService, JdbcTemplate jdbc) {
        this.launchService = launchService;
        this.jdbc = jdbc;
    }

    @GetMapping("/dispatches/{dispatchId}/package")
    public ResponseEntity<byte[]> packageOf(@PathVariable long dispatchId, @RequestParam(name = "k", required = false) String key) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT attempt FROM rt_run WHERE id=?", dispatchId);
        if (runs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int attempt = ((Number) runs.get(0).get("attempt")).intValue();
        if (!launchService.verifyPackageKey(dispatchId, attempt, key)) {
            return ResponseEntity.status(403).build();
        }
        var capsule = launchService.rebuild(dispatchId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("Content-MD5", capsule.md5())
                .body(capsule.bytes());
    }
}
