package com.ainative.v2.mem;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 记忆与知识域服务:记忆条目 CRUD + 检索。 */
@Service
public class MemoryService {
    private final MemoryDao dao;

    public MemoryService(MemoryDao dao) {
        this.dao = dao;
    }

    public PageResult<Map<String, Object>> list(String keyword, int pageNum, int pageSize) {
        long storeId = dao.ensureDefaultStore();
        List<Map<String, Object>> list = dao.list(storeId, keyword, pageNum, pageSize).stream()
                .map(this::toVO).toList();
        return new PageResult<>(list, dao.count(storeId, keyword), pageNum, pageSize);
    }

    public Map<String, Object> get(long id) {
        return toVO(dao.find(id).orElseThrow(() -> new BizException("19001", "记忆不存在")));
    }

    @Transactional
    public Map<String, Object> create(String title, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "内容不能为空");
        }
        long storeId = dao.ensureDefaultStore();
        long id = dao.insert(storeId, title == null || title.isBlank() ? "memory-" + System.currentTimeMillis() : title.trim(), contentMd);
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(long id, String title, String contentMd) {
        MemoryDao.Memory m = dao.find(id).orElseThrow(() -> new BizException("19001", "记忆不存在"));
        dao.update(id, title == null || title.isBlank() ? m.path() : title.trim(), contentMd);
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        dao.find(id).orElseThrow(() -> new BizException("19001", "记忆不存在"));
        dao.delete(id);
    }

    public Map<String, Object> groupedCount() {
        long storeId = dao.ensureDefaultStore();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("default", dao.count(storeId, null));
        return m;
    }

    private Map<String, Object> toVO(MemoryDao.Memory m) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", m.id());
        vo.put("storageId", m.storeId());
        vo.put("path", m.path());
        vo.put("contentMd", m.contentMd());
        return vo;
    }
}
