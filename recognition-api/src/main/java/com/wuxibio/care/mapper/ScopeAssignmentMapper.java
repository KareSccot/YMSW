package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.ScopeAssignment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScopeAssignmentMapper extends BaseMapper<ScopeAssignment> {

    @Insert("""
            INSERT INTO cfg_scope_assignment (
              user_id, source_role_id, role_id, scope_type, scope_id, condition_expression,
              status, effective_start_date, effective_end_date, granted_by, granted_at, created_at
            )
            SELECT u.username, s.role_id, NULL, s.scope_type, s.scope_id, s.condition_expression,
                   s.status, s.effective_start_date, s.effective_end_date, s.granted_by, s.granted_at, NOW()
            FROM sys_user_role ur
            JOIN sys_user u ON u.id = ur.user_id
            JOIN cfg_scope_assignment s ON s.role_id = ur.role_id
            WHERE ur.role_id = #{roleId}
              AND u.username IS NOT NULL
              AND u.username <> ''
            """)
    int insertProjectionFromRole(@Param("roleId") Long roleId);
}
