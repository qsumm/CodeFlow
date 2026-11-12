package com.ainative.scheduledtask;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskWorkspaceVocabularyTest {

    @Test
    void scheduledProductionCodeUsesWorkspaceVocabulary() throws Exception {
        List<String> forbidden = List.of(
                "RequireOrgAccess",
                "OrgAccessLevel",
                "OrgMemberDao",
                "OrgMemberDO",
                "getCurrentOrgId",
                "getOrgAccessLevel",
                "private Long tenantId",
                "@Param(\"tenantId\")",
                "租户");

        try (var files = Files.walk(Path.of(
                "src/main/java/com/ainative/scheduledtask"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String token : forbidden) {
                    assertFalse(source.contains(token),
                            file + " must not use legacy concept token " + token);
                }
            }
        }
    }

    @Test
    void scheduledOperationsJoinNewRunOwnershipToHistoricalTenantColumns() throws Exception {
        String operations = Files.readString(Path.of("docs/scheduled-task-operations.md"));

        assertTrue(operations.contains("r.workspace_id = g.tenant_id"));
        assertFalse(operations.contains("r.tenant_id = g.tenant_id"));
    }

    @Test
    void newCrossDomainApisUseWorkspaceVocabulary() throws Exception {
        List<Path> apiSources = List.of(
                Path.of("src/main/java/com/ainative/dispatch/subject/ExecutionSubject.java"),
                Path.of("src/main/java/com/ainative/dispatch/subject/ExecutionSubjectProvider.java"),
                Path.of("src/main/java/com/ainative/dispatch/subject/WorkitemExecutionSubjectProvider.java"),
                Path.of("src/main/java/com/ainative/websocket/RealtimeChannelAuthorizationService.java"),
                Path.of("src/main/java/com/ainative/websocket/BrowserRealtimeAuthorizationService.java"),
                Path.of("src/main/java/com/ainative/websocket/ConversationRealtimeAuthorizationService.java"),
                Path.of("src/main/java/com/ainative/websocket/BrowserRealtimeEndpoint.java"),
                Path.of("src/main/java/com/ainative/artifact/ArtifactService.java"),
                Path.of("src/main/java/com/ainative/artifact/RequirementDocumentService.java"),
                Path.of("src/main/java/com/ainative/dispatch/DispatchService.java"),
                Path.of("src/main/java/com/ainative/guidance/GuidanceService.java"));
        for (Path source : apiSources) {
            String content = Files.readString(source);
            assertFalse(content.contains("tenantId"), source + " must expose workspaceId");
            assertFalse(content.contains("tenant-validated"), source + " must describe workspace ownership");
        }

        // Temporary design/plan documents are not shipped contracts. Check the real API;
        // ScheduledTaskSchemaContractTest covers workspace columns and trigger uniqueness.
        assertTrue(Files.readString(Path.of(
                "src/main/java/com/ainative/dispatch/subject/ExecutionSubjectProvider.java"))
                .contains("ExecutionSubject load(long workspaceId, long sourceId)"));

        String workitemService = Files.readString(Path.of(
                "src/main/java/com/ainative/workitem/WorkitemService.java"));
        assertTrue(workitemService.contains("createWithOrigin(CreateWorkitemRequest req, long workspaceId"));
        assertTrue(workitemService.contains("listByOrigin(long workspaceId"));
        assertTrue(Files.readString(Path.of(
                "src/main/java/com/ainative/dispatch/DispatchPauseService.java"))
                .contains("requestPauseScheduledRun(long workspaceId"));
        assertFalse(Files.readString(Path.of(
                "src/main/java/com/ainative/workitem/WorkitemCommentDao.java"))
                .contains("tenant-scoped comment owner"));
    }
}
