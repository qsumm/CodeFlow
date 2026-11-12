package com.ainative.memory.store.dto;

import com.ainative.memory.store.MemoryChangeDO;

import java.util.List;

public record MemoryChangePageVO(long storeId, long currentRevision, List<MemoryChangeDO> changes,
                                 boolean hasMore) {
}
