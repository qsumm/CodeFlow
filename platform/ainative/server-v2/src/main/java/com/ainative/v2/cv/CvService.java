package com.ainative.v2.cv;

import com.ainative.v2.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话澄清域服务:澄清结论 + 澄清会话数据面(LLM 执行归 ag/执行器子系统)。 */
@Service
public class CvService {
    private final CvDao dao;

    public CvService(CvDao dao) {
        this.dao = dao;
    }

    public Map<String, Object> clarification(long ticketId) {
        return dao.findClarification(ticketId).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workitemId", c.ticketId());
            m.put("contentMd", c.contentMd());
            m.put("status", c.status());
            return m;
        }).orElse(null);
    }

    @Transactional
    public Map<String, Object> saveClarification(long ticketId, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "澄清结论不能为空");
        }
        dao.upsertClarification(ticketId, contentMd, "DONE");
        return clarification(ticketId);
    }

    public List<Map<String, Object>> conversations(long agentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CvDao.Chat c : dao.chatsOfAgent(agentId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("agentId", c.agentId());
            m.put("title", c.title());
            m.put("status", c.status());
            m.put("turnCount", dao.turnsOf(c.id()).size());
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createConversation(long agentId, String title, Long userId) {
        long id = dao.insertChat(agentId, title == null ? "新的澄清会话" : title, userId);
        return conversation(id);
    }

    public Map<String, Object> conversation(long chatId) {
        CvDao.Chat c = dao.findChat(chatId).orElseThrow(() -> new BizException("16001", "会话不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("agentId", c.agentId());
        m.put("title", c.title());
        m.put("status", c.status());
        m.put("turns", turns(chatId));
        return m;
    }

    public List<Map<String, Object>> turns(long chatId) {
        dao.findChat(chatId).orElseThrow(() -> new BizException("16001", "会话不存在"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CvDao.Turn t : dao.turnsOf(chatId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("seq", t.seq());
            m.put("role", t.role());
            m.put("contentMd", t.contentMd());
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> appendUserTurn(long chatId, String contentMd) {
        dao.findChat(chatId).orElseThrow(() -> new BizException("16001", "会话不存在"));
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "消息不能为空");
        }
        int seq = dao.nextTurnSeq(chatId);
        long id = dao.appendTurn(chatId, seq, "USER", contentMd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("seq", seq);
        out.put("role", "USER");
        out.put("contentMd", contentMd);
        return out;
    }

    public List<Map<String, Object>> events(long chatId) {
        dao.findChat(chatId).orElseThrow(() -> new BizException("16001", "会话不存在"));
        return List.of();
    }

    @Transactional
    public Map<String, Object> recordUsage(long ticketId, long tokensIn, long tokensOut, String model) {
        dao.recordUsage(ticketId, tokensIn, tokensOut, model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", ticketId);
        return out;
    }

    public List<Map<String, Object>> usage(long ticketId) {
        return dao.usageOf(ticketId);
    }
}
