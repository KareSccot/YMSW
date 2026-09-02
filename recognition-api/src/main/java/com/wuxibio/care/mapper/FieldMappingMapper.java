package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.FieldMapping;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FieldMappingMapper extends BaseMapper<FieldMapping> {
    @Delete("DELETE FROM cfg_field_mapping WHERE query_config_id = #{queryConfigId}")
    int hardDeleteByQueryConfigId(@Param("queryConfigId") Long queryConfigId);
}
