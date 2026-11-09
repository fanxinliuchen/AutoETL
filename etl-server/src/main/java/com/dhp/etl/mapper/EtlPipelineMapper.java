package com.dhp.etl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dhp.etl.entity.EtlPipeline;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EtlPipelineMapper extends BaseMapper<EtlPipeline> {
}
