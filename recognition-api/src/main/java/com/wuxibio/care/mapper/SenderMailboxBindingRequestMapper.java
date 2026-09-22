package com.wuxibio.care.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuxibio.care.entity.SenderMailboxBindingRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SenderMailboxBindingRequestMapper extends BaseMapper<SenderMailboxBindingRequest> {

    @Select("SELECT template_id FROM cfg_template_header WHERE template_id = #{templateHeaderId} FOR UPDATE")
    Long lockTemplateHeader(@Param("templateHeaderId") Long templateHeaderId);
}
