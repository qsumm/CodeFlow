package com.ainative.v2.artifact;

import com.ainative.v2.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 产物接收:运行器回传 deliverables → 落存储 + 记 as_artifact。 */
@Service
public class ArtifactIngestService {
    private final JdbcTemplate jdbc;
    private final ArtifactStorage storage;

    public ArtifactIngestService(JdbcTemplate jdbc, ArtifactStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Transactional
    public ArtifactStorage.Stored ingest(long dispatchId, long ticketId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("10001", "产物文件为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException("10500", "读取产物失败");
        }
        ArtifactStorage.Stored stored;
        try {
            stored = storage.store(dispatchId, file.getOriginalFilename(), content);
        } catch (IOException e) {
            throw new BizException("10500", "产物写入失败: " + e.getMessage());
        }
        // 同 dispatch 同名产物:覆盖(SDLC 多步会重复产出同名文件)
        // 工作区取自 rt_run:产物回传走 daemon 通道(无 Bearer),请求上下文会回落到
        // 默认工作区,产物会落到错误的工作区里,前端按当前工作区查不到。
        long workspaceId = workspaceOf(dispatchId);
        jdbc.update("DELETE FROM as_artifact WHERE run_id=? AND name=?",
                dispatchId, file.getOriginalFilename());
        jdbc.update("INSERT INTO as_artifact (workspace_id, ticket_id, run_id, name, kind, object_key, size_bytes) VALUES (?,?,?,?,?,?,?)",
                workspaceId, ticketId, dispatchId,
                file.getOriginalFilename(), "DELIVERABLE", stored.objectKey(), stored.sizeBytes());
        jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, from_val, to_val) VALUES (?,?, 'ARTIFACT_RECEIVED', 'SYSTEM', NULL, ?)",
                workspaceId, ticketId, file.getOriginalFilename());
        return stored;
    }

    /** 该派发所属工作区(以 rt_run 记录为准)。 */
    private long workspaceOf(long dispatchId) {
        try {
            Long ws = jdbc.queryForObject("SELECT workspace_id FROM rt_run WHERE id=?", Long.class, dispatchId);
            return ws == null ? com.ainative.v2.common.RequestContext.workspaceId() : ws;
        } catch (Exception e) {
            return com.ainative.v2.common.RequestContext.workspaceId();
        }
    }
}
