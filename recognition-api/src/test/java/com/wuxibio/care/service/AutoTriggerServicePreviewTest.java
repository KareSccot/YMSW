package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.AutoTriggerRunLogMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the editor's live preview path. These hit-the-wire-style errors used to
 * surface as opaque "服务器内部错误" — we want a regression net for valid expressions
 * + invalid expressions + arbitrary timezones.
 */
@ExtendWith(MockitoExtension.class)
class AutoTriggerServicePreviewTest {

    @Mock private AutoTriggerDefMapper triggerMapper;
    @Mock private AutoTriggerRunLogMapper runLogMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TaskTemplateService taskTemplateService;
    @Mock private SendService sendService;
    @Mock private ConditionExpressionService conditionExpressionService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;

    private AutoTriggerService service;

    @BeforeEach
    void setUp() {
        service = new AutoTriggerService(
                triggerMapper, runLogMapper, sysUserMapper, taskTemplateService, sendService,
                conditionExpressionService, timeDependentService, auditLogService);
    }

    @Test
    void previewNextRuns_returnsFiveAscendingWallClockTimes_inDefaultZone() {
        List<LocalDateTime> runs = service.previewNextRuns("0 0 9 * * ?", "Asia/Shanghai", 5);

        assertThat(runs).hasSize(5);
        // Each entry should be strictly after the previous (cron always moves forward).
        for (int i = 1; i < runs.size(); i++) {
            assertThat(runs.get(i)).isAfter(runs.get(i - 1));
        }
        // All entries fire at 09:00 (per cron).
        assertThat(runs).allSatisfy(t -> {
            assertThat(t.getHour()).isEqualTo(9);
            assertThat(t.getMinute()).isZero();
        });
    }

    @Test
    void previewNextRuns_honorsRequestedZone_byProducingWallClockInThatZone() {
        // Same cron, two different zones — both should hit 09:00 wall-clock in
        // their respective zones. Equality of the wall-clock value (not instant)
        // is the whole point of timezone-aware cron.
        List<LocalDateTime> sh = service.previewNextRuns("0 0 9 * * ?", "Asia/Shanghai", 1);
        List<LocalDateTime> ny = service.previewNextRuns("0 0 9 * * ?", "America/New_York", 1);

        assertThat(sh.get(0).getHour()).isEqualTo(9);
        assertThat(ny.get(0).getHour()).isEqualTo(9);
    }

    @Test
    void previewNextRuns_acceptsNullOrBlankTimezone_andFallsBackToDefault() {
        // Defensive: legacy callers may not include a timezone.
        assertThat(service.previewNextRuns("0 0 9 * * ?", null, 1)).hasSize(1);
        assertThat(service.previewNextRuns("0 0 9 * * ?", "  ", 1)).hasSize(1);
    }

    @Test
    void previewNextRuns_rejectsInvalidCronWithBizException() {
        assertThatThrownBy(() -> service.previewNextRuns("not-a-cron", "Asia/Shanghai", 5))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cronExpr 非法");
    }

    @Test
    void previewNextRuns_rejectsBlankCronWithBizException() {
        assertThatThrownBy(() -> service.previewNextRuns("", "Asia/Shanghai", 5))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cronExpr 不能为空");
    }

    @Test
    void previewNextRuns_clampsCountToTwenty() {
        List<LocalDateTime> runs = service.previewNextRuns("0 0 9 * * ?", "Asia/Shanghai", 999);
        assertThat(runs).hasSize(20);
    }

    @Test
    void previewNextRuns_supportsEveryFifteenMinutes() {
        // Regression for: users using "*/15" in the minute field used to trip
        // the ZonedDateTime path with an UnsupportedTemporalTypeException.
        List<LocalDateTime> runs = service.previewNextRuns("0 */15 * * * ?", "Asia/Shanghai", 5);
        assertThat(runs).hasSize(5);
        for (int i = 1; i < runs.size(); i++) {
            assertThat(runs.get(i)).isAfter(runs.get(i - 1));
        }
    }
}
