package com.dhp.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 租户基础实体类 (规范 03 §3.1)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public abstract class BaseTenantEntity extends BaseEntity {

    /** 租户ID */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;
}
