package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.AutoTriggerRunLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AutoTriggerRunLogMapper extends BaseMapper<AutoTriggerRunLog> {

    /**
     * Database-enforced submission claim. Duplicate scheduled fire times and a
     * second active execution are ignored instead of throwing into the caller's transaction.
     */
    @Insert("""
            INSERT IGNORE INTO auto_trigger_run_log (
                trigger_id, execution_mode, trigger_time, scheduled_fire_time,
                status, message, matched_count, sent_count, failed_count,
                task_run_id, idempotency_key, active_lock, created_at
            ) VALUES (
                #{triggerId}, #{executionMode}, #{triggerTime}, #{scheduledFireTime},
                #{status}, #{message}, #{matchedCount}, #{sentCount}, #{failedCount},
                #{taskRunId}, #{idempotencyKey}, #{activeLock}, CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSubmissionClaim(AutoTriggerRunLog row);

    @Update("""
            UPDATE auto_trigger_run_log
            SET status = #{status},
                message = #{message},
                matched_count = #{matchedCount},
                sent_count = #{sentCount},
                failed_count = #{failedCount},
                completed_at = #{completedAt},
                active_lock = NULL
            WHERE id = #{id}
              AND status = 'Running'
            """)
    int completeExecution(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("message") String message,
            @Param("matchedCount") int matchedCount,
            @Param("sentCount") int sentCount,
            @Param("failedCount") int failedCount,
            @Param("completedAt") LocalDateTime completedAt);
}
