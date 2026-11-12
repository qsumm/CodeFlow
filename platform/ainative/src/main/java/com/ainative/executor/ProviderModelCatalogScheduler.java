package com.ainative.executor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProviderModelCatalogScheduler {

    private final ProviderModelCatalogService service;

    public ProviderModelCatalogScheduler(ProviderModelCatalogService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ainative.provider-model-catalog.refresh-fixed-delay-ms:86400000}")
    public void refreshDueCatalogs() {
        service.requestRefreshIfDue("qoder");
        service.requestRefreshIfDue("qodercn");
    }
}
