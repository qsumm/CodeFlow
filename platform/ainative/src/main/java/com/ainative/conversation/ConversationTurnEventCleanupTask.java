package com.ainative.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

@Component
public class ConversationTurnEventCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnEventCleanupTask.class);

    private final AgentConversationTurnEventDao eventDao;

    @Value("${ainative.conversation.events.retention-days:30}")
    private int retentionDays;

    @Value("${ainative.conversation.events.cleanup-batch-size:1000}")
    private int batchSize;

    public ConversationTurnEventCleanupTask(AgentConversationTurnEventDao eventDao) {
        this.eventDao = eventDao;
    }

    @Scheduled(fixedDelayString = "${ainative.conversation.events.cleanup-fixed-delay-ms:3600000}")
    public void cleanup() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -retentionDays);
        Date cutoff = cal.getTime();
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = eventDao.deleteExpiredBatch(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted >= batchSize);
        if (totalDeleted > 0) {
            log.info("conversation turn event cleanup deleted={} rows older than {} days",
                    totalDeleted, retentionDays);
        }
    }
}
