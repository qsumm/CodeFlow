package com.ainative.ai.adapter;

import com.ainative.json.JSON;
import com.ainative.access.WorkspaceDomainReadOnlyException;
import com.ainative.access.WorkspaceDomainGuard;
import com.ainative.ai.AiConstants;
import com.ainative.ai.AiSessionDO;
import com.ainative.clarification.ClarificationDO;
import com.ainative.clarification.ClarificationDao;
import com.ainative.repo.RepoConclusionDO;
import com.ainative.repo.RepoConclusionDao;
import com.ainative.repo.RepoDO;
import com.ainative.repo.RepoDao;
import com.ainative.workitem.WorkitemCommentDO;
import com.ainative.workitem.WorkitemCommentDao;
import com.ainative.context.RequestContext;
import com.ainative.workitem.WorkitemDO;
import com.ainative.workitem.WorkitemDao;
import com.ainative.workspace.WorkspaceMemberDO;
import com.ainative.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClarificationAdapterTest {

    private ClarificationDao clarificationDao;
    private WorkitemDao workitemDao;
    private WorkitemCommentDao workitemCommentDao;
    private RepoDao repoDao;
    private RepoConclusionDao repoConclusionDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private ClarificationAdapter adapter;

    @BeforeEach
    void setUp() {
        clarificationDao = mock(ClarificationDao.class);
        workitemDao = mock(WorkitemDao.class);
        workitemCommentDao = mock(WorkitemCommentDao.class);
        repoDao = mock(RepoDao.class);
        repoConclusionDao = mock(RepoConclusionDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        adapter = new ClarificationAdapter(clarificationDao, workitemDao,
                workitemCommentDao, repoDao, repoConclusionDao,
                new WorkspaceDomainGuard(workspaceMemberDao));
        RequestContext.get().setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void buildSystemPromptCanReadRepositoryAndWorkitemContextForReadOnlyDomains() {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel("READ_WRITE");
        member.setReadOnlyDomains("[\"REPO\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setCreatorId(7L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(9L);

        assertNotNull(adapter.buildSystemPrompt(session));
        verify(workitemDao).findById(9L);
        verify(repoDao).list(100L, 0, 100);

    }

    @Test
    void buildSystemPromptReadsContextsWhenCreatorHasNoForbiddenDomains() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setCreatorId(7L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(9L);
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(9L);
        workitem.setTitle("原始标题");
        workitem.setContentMd("工作项正文");
        when(workitemDao.findById(9L)).thenReturn(workitem);
        when(workitemCommentDao.listByWorkitem(100L, 9L)).thenReturn(List.of());
        RepoDO repo = new RepoDO();
        repo.setId(5L);
        repo.setName("ainative");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));

        String prompt = adapter.buildSystemPrompt(session);

        assertTrue(prompt.contains("原始标题"));
        assertTrue(prompt.contains("ainative"));
    }

    @Test
    void persistIsBlockedBeforeAnyWriteWhenOperatorForbidsClarificationDomain() {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel("READ_WRITE");
        member.setReadOnlyDomains("[\"CLARIFICATION\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(9L);

        assertThrows(WorkspaceDomainReadOnlyException.class,
                () -> adapter.persistConfirmedResult(session, "{\"clarificationMd\":\"x\"}"));

        verify(clarificationDao, never()).insert(any(ClarificationDO.class));
        verify(clarificationDao, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    void sceneIsClarification() {
        assertEquals(AiConstants.Scene.CLARIFICATION, adapter.scene());
    }

    @Test
    void validateRejectsNullClarificationMd() {
        String result = adapter.validateResult("{\"solution\":\"x\"}");
        assertNotNull(result);
    }

    @Test
    void validateAcceptsValidResult() {
        String json = JSON.toJSONString(new java.util.LinkedHashMap<>() {{
            put("clarificationMd", "# Clarified requirements");
        }});
        assertNull(adapter.validateResult(json));
    }

    @Test
    void persistCreatesWhenNoneExists() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(10L);
        when(clarificationDao.findByWorkitem(10L)).thenReturn(null);

        String json = "{\"clarificationMd\":\"# Result\"}";
        adapter.persistConfirmedResult(session, json);

        ArgumentCaptor<ClarificationDO> cap = ArgumentCaptor.forClass(ClarificationDO.class);
        verify(clarificationDao).insert(cap.capture());
        assertEquals("# Result", cap.getValue().getContentMd());
        assertEquals(100L, cap.getValue().getTenantId());
    }

    @Test
    void buildSystemPromptIncludesWorkitemContext() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setCreatorId(7L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(10L);

        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(10L);
        workitem.setTitle("实现用户登录");
        workitem.setContentMd("需要支持手机号和邮箱登录");
        when(workitemDao.findById(10L)).thenReturn(workitem);

        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setAuthorRef(1L);
        comment.setContentMd("还需要考虑第三方登录");
        when(workitemCommentDao.listByWorkitem(100L, 10L)).thenReturn(List.of(comment));
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());

        String prompt = adapter.buildSystemPrompt(session);

        assertTrue(prompt.contains("需求澄清专家"));
        assertTrue(prompt.contains("实现用户登录"));
        assertTrue(prompt.contains("需要支持手机号和邮箱登录"));
        assertTrue(prompt.contains("还需要考虑第三方登录"));
    }

    @Test
    void buildSystemPromptIncludesRepoContext() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setCreatorId(7L);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(10L);

        when(workitemDao.findById(10L)).thenReturn(null);

        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setName("ainative");
        repo.setUrl("git@github.com:example/ainative.git");
        repo.setDescription("SDLC平台");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));

        RepoConclusionDO conclusion = new RepoConclusionDO();
        conclusion.setPurpose("SDLC全生命周期管理平台");
        conclusion.setKeyBusiness("需求管理、仓库扫描");
        conclusion.setUpstreams("GitLab");
        conclusion.setDownstreams("CI/CD");
        when(repoConclusionDao.findByRepoId(1L)).thenReturn(conclusion);

        String prompt = adapter.buildSystemPrompt(session);

        assertTrue(prompt.contains("ainative"));
        assertTrue(prompt.contains("SDLC全生命周期管理平台"));
        assertTrue(prompt.contains("需求管理、仓库扫描"));
        assertTrue(prompt.contains("GitLab"));
        assertTrue(prompt.contains("CI/CD"));
    }

    @Test
    void buildSystemPromptHandlesNoWorkitemGracefully() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setCreatorId(7L);
        session.setBizRefType("OTHER");
        session.setBizRefId(10L);

        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());

        String prompt = adapter.buildSystemPrompt(session);
        assertTrue(prompt.contains("需求澄清专家"));
        assertFalse(prompt.contains("需求信息"));
    }

    @Test
    void persistUpdatesWhenExists() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(10L);

        ClarificationDO existing = new ClarificationDO();
        existing.setId(5L);
        existing.setTenantId(100L);
        when(clarificationDao.findByWorkitem(10L)).thenReturn(existing);

        String json = "{\"clarificationMd\":\"# Updated\"}";
        adapter.persistConfirmedResult(session, json);

        verify(clarificationDao).update(5L, 100L, "# Updated");
    }
}
