package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 金蝶登录凭证实体 (规范 03 §4.2)
 *
 * <p>密码采用 AES 加密存储；新增/更新时可传入明文，由 Service 加密后覆盖。
 * 查询接口一律不回显密码。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_kingdee_credential")
public class EtlKingdeeCredential extends BaseTenantEntity {

    /** 凭证名称 */
    private String credName;

    /** 金蝶登录接口地址 */
    private String loginUrl;

    /** 数据中心 / 账套代码 acctID */
    private String acctId;

    /** 登录用户名 */
    private String userName;

    /** 加密密码（入参时为明文，落库前由 Service 加密） */
    private String passwordCipher;

    /** 多语言环境代码 */
    private Integer lcid;

    /** 是否默认凭证: 1=是 */
    private Integer isDefault;

    /** 状态: 1=正常, 0=禁用 */
    private Integer status;
}
