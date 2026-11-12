package com.ainative.integration.event;

public record WorkitemCommentCreatedEvent(long tenantId, long workitemId, long commentId,
                                          String actorType, long actorRef, String contentMd,
                                          boolean deliveryReport) {
    public WorkitemCommentCreatedEvent(long tenantId, long workitemId, long commentId,
                                       String actorType, long actorRef, String contentMd) {
        this(tenantId, workitemId, commentId, actorType, actorRef, contentMd, false);
    }
}
