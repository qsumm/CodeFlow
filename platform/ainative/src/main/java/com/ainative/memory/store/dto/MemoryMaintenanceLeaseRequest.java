package com.ainative.memory.store.dto;

public record MemoryMaintenanceLeaseRequest(long storeId, String leaseId) {
    public MemoryMaintenanceLeaseRequest(long storeId) {
        this(storeId, null);
    }
}
