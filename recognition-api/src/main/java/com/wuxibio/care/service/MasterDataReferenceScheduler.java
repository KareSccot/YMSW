package com.wuxibio.care.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(
        name = "app.master-data-reference-sync.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MasterDataReferenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MasterDataReferenceScheduler.class);

    private final MasterDataReferenceService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MasterDataReferenceScheduler(MasterDataReferenceService service) {
        this.service = service;
    }

    @Scheduled(
            cron = "${app.master-data-reference-sync.cron:0 0 2 * * *}",
            zone = "${app.master-data-reference-sync.zone:Asia/Shanghai}")
    public void syncDaily() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[MASTER-DATA-REFERENCE] Daily full refresh skipped because a previous run is still active");
            return;
        }
        try {
            syncOne("法人公司", service::syncCompanies);
            syncOne("一级组织（事业部）", () -> service.syncRuleReferences("division"));
            syncOne("二级组织（部门）", service::syncDepartments);
            syncOne("三级组织", () -> service.syncRuleReferences("thirdDepartment"));
            syncOne("四级组织", () -> service.syncRuleReferences("fourthDepartment"));
            syncOne("五级组织", () -> service.syncRuleReferences("fifthDepartment"));
            syncOne("国家列表", service::syncCountries);
            syncOne("员工类型", () -> service.syncRuleReferences("employeeType"));
            syncOne("职位", () -> service.syncRuleReferences("jobTitle"));
            syncOne("办公地点", () -> service.syncRuleReferences("location"));
        } finally {
            running.set(false);
        }
    }

    private void syncOne(
            String label,
            Supplier<MasterDataReferenceService.ReferenceSyncResult> operation) {
        try {
            MasterDataReferenceService.ReferenceSyncResult result = operation.get();
            log.info("[MASTER-DATA-REFERENCE] {} daily full refresh completed: {} rows", label, result.total());
        } catch (Exception e) {
            log.error("[MASTER-DATA-REFERENCE] {} daily full refresh failed: {}", label, e.getMessage(), e);
        }
    }
}
