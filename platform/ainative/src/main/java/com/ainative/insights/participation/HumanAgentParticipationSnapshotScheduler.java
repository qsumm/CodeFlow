package com.ainative.insights.participation;

import com.ainative.workspace.WorkspaceDO;
import com.ainative.workspace.WorkspaceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
public class HumanAgentParticipationSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(HumanAgentParticipationSnapshotScheduler.class);

    private final WorkspaceDao workspaceDao;
    private final HumanAgentParticipationRefreshService refreshService;
    private final HumanAgentParticipationProperties properties;

    public HumanAgentParticipationSnapshotScheduler(WorkspaceDao workspaceDao,
                                                     HumanAgentParticipationRefreshService refreshService,
                                                     HumanAgentParticipationProperties properties) {
        this.workspaceDao = workspaceDao;
        this.refreshService = refreshService;
        this.properties = properties;
    }

    @Scheduled(cron = "${ainative.insights.human-agent.cron:0 0 3 * * *}",
               zone = "${ainative.insights.human-agent.timezone:Asia/Shanghai}")
    public void nightlyRebuild() {
        ZoneId zone = ZoneId.of(properties.getTimezone());
        LocalDate dataThrough = LocalDate.now(zone).minusDays(1);
        List<WorkspaceDO> tenants = workspaceDao.listActive();
        log.info("Participation nightly rebuild starting tenants={} dataThrough={}", tenants.size(), dataThrough);
        for (WorkspaceDO tenant : tenants) {
            refreshService.requestRefresh(tenant.getId());
        }
    }
}
