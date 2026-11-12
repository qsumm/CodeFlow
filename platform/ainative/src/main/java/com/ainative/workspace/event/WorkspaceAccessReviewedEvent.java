package com.ainative.workspace.event;

public record WorkspaceAccessReviewedEvent(long tenantId, long requestId, long requesterId,
                                           long reviewerId, String reviewerDisplayName,
                                           String workspaceName, String requestedLevel,
                                           String outcome, String rejectReason) {
}
