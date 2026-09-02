package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.MasterDataRuleReference;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MasterDataRuleReferenceMapper extends BaseMapper<MasterDataRuleReference> {

    @Delete("DELETE FROM md_rule_reference WHERE dimension = #{dimension}")
    int deleteDimensionRows(@Param("dimension") String dimension);
}
