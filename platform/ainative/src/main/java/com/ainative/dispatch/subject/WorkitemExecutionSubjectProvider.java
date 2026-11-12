package com.ainative.dispatch.subject;

import com.ainative.agent.AgentVersionDO;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.dispatch.DispatchDO;
import com.ainative.dispatch.ExecutionSourceType;
import com.ainative.dispatch.PackageContextAssembler;
import com.ainative.taskpackage.PackageContext;
import com.ainative.workitem.WorkitemDO;
import com.ainative.workitem.WorkitemDao;

/** Adapter preserving the existing Workitem package assembly byte-for-byte. */
public final class WorkitemExecutionSubjectProvider implements ExecutionSubjectProvider {
    private final WorkitemDao workitemDao;
    private final PackageContextAssembler assembler;

    public WorkitemExecutionSubjectProvider(WorkitemDao workitemDao, PackageContextAssembler assembler) {
        this.workitemDao = workitemDao;
        this.assembler = assembler;
    }

    @Override
    public ExecutionSourceType type() {
        return ExecutionSourceType.WORKITEM;
    }

    @Override
    public ExecutionSubject load(long workspaceId, long sourceId) {
        WorkitemDO workitem = workitemDao.findById(sourceId);
        if (workitem == null || workitem.getId() == null || workitem.getId() != sourceId
                || workitem.getTenantId() == null || workitem.getTenantId() != workspaceId) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        return new WorkitemSubject(new ExecutionSubjectRef(type(), sourceId), workitem);
    }

    @Override
    public PackageContext assemble(DispatchDO dispatch, ExecutionSubject subject, AgentVersionDO version) {
        if (!(subject instanceof WorkitemSubject workitemSubject)
                || !subject.ref().equals(new ExecutionSubjectRef(type(), dispatch.getWorkitemId()))) {
            throw new IllegalArgumentException("workitem execution subject mismatch");
        }
        return assembler.assembleWorkitem(dispatch, version, workitemSubject.workitem());
    }

    private record WorkitemSubject(ExecutionSubjectRef ref, WorkitemDO workitem) implements ExecutionSubject { }
}
