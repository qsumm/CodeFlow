package com.ainative.integration;

import com.ainative.json.JSON;
import com.ainative.integration.common.ExternalPrincipalDO;
import com.ainative.integration.common.ExternalPrincipalDao;
import com.ainative.integration.common.PrincipalRelationSnapshot;
import com.ainative.integration.provider.ExternalPrincipalRef;
import com.ainative.integration.provider.ExternalPrincipalRelation;
import com.ainative.integration.provider.ExternalWorkitemDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalPrincipalServiceTest {

    @Test
    void resolvesStablePrincipalsAndGenericRelationSnapshot() {
        ExternalPrincipalDao principalDao = mock(ExternalPrincipalDao.class);
        when(principalDao.findBySource("ZENTAO", "reporter"))
                .thenReturn(principal(10001L, "reporter", "需求提出人"));
        when(principalDao.findBySource("ZENTAO", "owner"))
                .thenReturn(principal(10002L, "owner", "业务负责人"));
        when(principalDao.findBySource("ZENTAO", "collaborator"))
                .thenReturn(principal(10003L, "collaborator", "协作者"));
        when(principalDao.upsert(any())).thenReturn(1);

        ExternalWorkitemDetail detail = new ExternalWorkitemDetail();
        detail.setReporter(ExternalPrincipalRef.user("reporter", "需求提出人"));
        detail.setBusinessOwner(ExternalPrincipalRef.user("owner", "业务负责人"));
        ExternalPrincipalRelation relation = new ExternalPrincipalRelation();
        relation.setSourceKey("collaborators");
        relation.setDisplayName("协作者");
        relation.setPrincipals(List.of(
                ExternalPrincipalRef.user("collaborator", "协作者"),
                ExternalPrincipalRef.user("collaborator", "重复协作者")));
        detail.setPrincipalRelations(List.of(relation));

        ExternalPrincipalService.IdentitySnapshot result = new ExternalPrincipalService(principalDao)
                .resolveWorkitem("ZENTAO", detail);

        assertEquals(10001L, result.reporterPrincipalId());
        assertEquals(10002L, result.businessOwnerPrincipalId());
        List<PrincipalRelationSnapshot> snapshots = JSON.parseArray(
                result.principalRelationsJson(), PrincipalRelationSnapshot.class);
        assertEquals(1, snapshots.size());
        assertEquals("collaborators", snapshots.get(0).getSourceKey());
        assertEquals("协作者", snapshots.get(0).getDisplayName());
        assertEquals(List.of(10003L), snapshots.get(0).getPrincipalIds());
    }

    private ExternalPrincipalDO principal(long id, String subjectId, String displayName) {
        ExternalPrincipalDO principal = new ExternalPrincipalDO();
        principal.setId(id);
        principal.setSubjectId(subjectId);
        principal.setDisplayName(displayName);
        return principal;
    }
}
