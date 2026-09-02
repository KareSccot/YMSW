package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataReferenceSchedulerTest {

    @Mock
    private MasterDataReferenceService service;

    @Test
    void dailySyncRefreshesAllTenReferenceSourcesInOrder() {
        when(service.syncCompanies()).thenReturn(result(33));
        when(service.syncRuleReferences("division")).thenReturn(result(19));
        when(service.syncDepartments()).thenReturn(result(167));
        when(service.syncRuleReferences("thirdDepartment")).thenReturn(result(60));
        when(service.syncRuleReferences("fourthDepartment")).thenReturn(result(80));
        when(service.syncRuleReferences("fifthDepartment")).thenReturn(result(100));
        when(service.syncCountries()).thenReturn(result(246));
        when(service.syncRuleReferences("employeeType")).thenReturn(result(8));
        when(service.syncRuleReferences("jobTitle")).thenReturn(result(15075));
        when(service.syncRuleReferences("location")).thenReturn(result(51));

        new MasterDataReferenceScheduler(service).syncDaily();

        InOrder order = inOrder(service);
        order.verify(service).syncCompanies();
        order.verify(service).syncRuleReferences("division");
        order.verify(service).syncDepartments();
        order.verify(service).syncRuleReferences("thirdDepartment");
        order.verify(service).syncRuleReferences("fourthDepartment");
        order.verify(service).syncRuleReferences("fifthDepartment");
        order.verify(service).syncCountries();
        order.verify(service).syncRuleReferences("employeeType");
        order.verify(service).syncRuleReferences("jobTitle");
        order.verify(service).syncRuleReferences("location");
    }

    @Test
    void dailySyncContinuesWhenOneReferenceSourceFails() {
        when(service.syncCompanies()).thenThrow(new IllegalStateException("SF unavailable"));
        when(service.syncRuleReferences("division")).thenReturn(result(19));
        when(service.syncDepartments()).thenReturn(result(167));
        when(service.syncRuleReferences("thirdDepartment")).thenReturn(result(60));
        when(service.syncRuleReferences("fourthDepartment")).thenReturn(result(80));
        when(service.syncRuleReferences("fifthDepartment")).thenReturn(result(100));
        when(service.syncCountries()).thenReturn(result(246));
        when(service.syncRuleReferences("employeeType")).thenReturn(result(8));
        when(service.syncRuleReferences("jobTitle")).thenReturn(result(15075));
        when(service.syncRuleReferences("location")).thenReturn(result(51));

        new MasterDataReferenceScheduler(service).syncDaily();

        InOrder order = inOrder(service);
        order.verify(service).syncCompanies();
        order.verify(service).syncRuleReferences("division");
        order.verify(service).syncDepartments();
        order.verify(service).syncRuleReferences("thirdDepartment");
        order.verify(service).syncRuleReferences("fourthDepartment");
        order.verify(service).syncRuleReferences("fifthDepartment");
        order.verify(service).syncCountries();
        order.verify(service).syncRuleReferences("employeeType");
        order.verify(service).syncRuleReferences("jobTitle");
        order.verify(service).syncRuleReferences("location");
    }

    @Test
    void dailySyncSkipsAnOverlappingRun() throws Exception {
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        when(service.syncCompanies()).thenAnswer(invocation -> {
            firstRunStarted.countDown();
            releaseFirstRun.await(2, TimeUnit.SECONDS);
            return result(33);
        });
        when(service.syncRuleReferences("division")).thenReturn(result(19));
        when(service.syncDepartments()).thenReturn(result(167));
        when(service.syncRuleReferences("thirdDepartment")).thenReturn(result(60));
        when(service.syncRuleReferences("fourthDepartment")).thenReturn(result(80));
        when(service.syncRuleReferences("fifthDepartment")).thenReturn(result(100));
        when(service.syncCountries()).thenReturn(result(246));
        when(service.syncRuleReferences("employeeType")).thenReturn(result(8));
        when(service.syncRuleReferences("jobTitle")).thenReturn(result(15075));
        when(service.syncRuleReferences("location")).thenReturn(result(51));
        MasterDataReferenceScheduler scheduler = new MasterDataReferenceScheduler(service);

        Thread firstRun = new Thread(scheduler::syncDaily);
        firstRun.start();
        assertTrue(firstRunStarted.await(1, TimeUnit.SECONDS));
        scheduler.syncDaily();
        releaseFirstRun.countDown();
        firstRun.join(3_000);

        verify(service, times(1)).syncCompanies();
    }

    private static MasterDataReferenceService.ReferenceSyncResult result(int total) {
        return new MasterDataReferenceService.ReferenceSyncResult(total, total, 0, "ok");
    }
}
