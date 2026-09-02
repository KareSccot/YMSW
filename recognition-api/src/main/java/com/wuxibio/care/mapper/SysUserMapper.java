package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            <script>
            SELECT u.*
            FROM sys_user u
            INNER JOIN sys_user_role ur ON ur.user_id = u.id
            WHERE ur.role_id = #{roleId}
              AND u.deleted = 0
            <if test="keyword != null and keyword != ''">
              AND (
                u.name LIKE CONCAT('%', #{keyword}, '%')
                OR u.username LIKE CONCAT('%', #{keyword}, '%')
                OR u.employee_id LIKE CONCAT('%', #{keyword}, '%')
                OR u.email LIKE CONCAT('%', #{keyword}, '%')
                OR u.department LIKE CONCAT('%', #{keyword}, '%')
                OR u.company_name LIKE CONCAT('%', #{keyword}, '%')
                OR u.job_title LIKE CONCAT('%', #{keyword}, '%')
              )
            </if>
            ORDER BY
              CASE WHEN u.name IS NULL OR u.name = '' THEN 1 ELSE 0 END,
              u.name,
              u.employee_id,
              u.username,
              u.id
            </script>
            """)
    IPage<SysUser> selectPageByRole(
            Page<SysUser> page,
            @Param("roleId") Long roleId,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT DISTINCT u.*
            FROM sys_user u
            INNER JOIN sys_user_role ur ON ur.user_id = u.id
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE u.deleted = 0
              AND u.employee_id IS NOT NULL
              AND u.employee_id &lt;&gt; ''
              AND r.deleted = 0
              AND r.name &lt;&gt; #{employeeRoleName}
            <if test="keyword != null and keyword != ''">
              AND (
                u.name LIKE CONCAT('%', #{keyword}, '%')
                OR u.username LIKE CONCAT('%', #{keyword}, '%')
                OR u.employee_id LIKE CONCAT('%', #{keyword}, '%')
                OR u.email LIKE CONCAT('%', #{keyword}, '%')
              )
            </if>
            <if test="department != null and department != ''">
              AND u.department = #{department}
            </if>
            ORDER BY
              CASE WHEN u.name IS NULL OR u.name = '' THEN 1 ELSE 0 END,
              u.name,
              u.employee_id,
              u.id
            LIMIT #{limit}
            </script>
            """)
    List<SysUser> selectApprovalCandidates(
            @Param("keyword") String keyword,
            @Param("department") String department,
            @Param("employeeRoleName") String employeeRoleName,
            @Param("limit") int limit);

    @Select("""
            SELECT DISTINCT u.*
            FROM sys_user u
            INNER JOIN sys_user_role ur ON ur.user_id = u.id
            INNER JOIN sys_role r ON r.id = ur.role_id
            WHERE u.employee_id = #{employeeId}
              AND u.deleted = 0
              AND r.deleted = 0
              AND r.name <> #{employeeRoleName}
            LIMIT 1
            """)
    SysUser selectApprovalCandidateByEmployeeId(
            @Param("employeeId") String employeeId,
            @Param("employeeRoleName") String employeeRoleName);
}
