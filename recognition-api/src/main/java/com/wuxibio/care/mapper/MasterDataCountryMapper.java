package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.MasterDataCountry;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MasterDataCountryMapper extends BaseMapper<MasterDataCountry> {

    @Delete("DELETE FROM md_country WHERE id IS NOT NULL")
    int deleteAllRows();
}
