package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.EmployeeAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmployeeAssignmentMapper extends BaseMapper<EmployeeAssignment> {

    @Update("UPDATE md_employee_assignment SET source_active = 0 "
            + "WHERE source_type = #{sourceType} AND deleted = 0")
    int markSourceInactive(String sourceType);
}
