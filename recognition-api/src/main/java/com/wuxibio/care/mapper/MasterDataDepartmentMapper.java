package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.MasterDataDepartment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MasterDataDepartmentMapper extends BaseMapper<MasterDataDepartment> {

    @Delete("DELETE FROM md_department WHERE id IS NOT NULL")
    int deleteAllRows();
}
