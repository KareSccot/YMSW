package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.MasterDataCompany;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MasterDataCompanyMapper extends BaseMapper<MasterDataCompany> {

    @Delete("DELETE FROM md_company WHERE id IS NOT NULL")
    int deleteAllRows();
}
