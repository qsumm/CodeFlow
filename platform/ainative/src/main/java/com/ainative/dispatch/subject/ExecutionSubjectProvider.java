package com.ainative.dispatch.subject;

import com.ainative.agent.AgentVersionDO;
import com.ainative.dispatch.DispatchDO;
import com.ainative.dispatch.ExecutionSourceType;
import com.ainative.taskpackage.PackageContext;

public interface ExecutionSubjectProvider {
    ExecutionSourceType type();

    /** Loads the source by a workspace-qualified identity and fails when unavailable or foreign. */
    ExecutionSubject load(long workspaceId, long sourceId);

    /**
     * Builds the immutable package view. Workitems receive the already selected online version;
     * scheduled runs additionally verify it against their frozen snapshot.
     */
    PackageContext assemble(DispatchDO dispatch, ExecutionSubject subject, AgentVersionDO version);
}
