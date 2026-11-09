package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ETL 流程目录实体 (规范 03 §4.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_folder")
public class EtlFolder extends BaseTenantEntity {

    /** 父目录ID (0为根) */
    private Long parentId;

    /** 目录名称 */
    private String folderName;

    /** 排序号 */
    private Integer folderSort;
}
