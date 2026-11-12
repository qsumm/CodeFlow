package com.ainative.integration.event;

public record WorkitemContentUpdatedEvent(long tenantId, long workitemId, String title,
                                          String contentMd, long userId) {
}
