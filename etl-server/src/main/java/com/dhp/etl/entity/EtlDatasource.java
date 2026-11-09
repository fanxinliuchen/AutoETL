package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据源连接实体 (规范 03 §4.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_datasource")
public class EtlDatasource extends BaseTenantEntity {

    /** 数据源名称 */
    private String dsName;

    /** 类型: MYSQL / MYSQL_DW */
    private String dsType;

    /** 驱动类 */
    private String driverClass;

    /** 主机 */
    private String host;

    /** 端口 */
    private Integer port;

    /** 数据库名称 */
    private String databaseName;

    /** 字符编码 */
    private String encoding;

    /** JDBC 连接串 */
    private String jdbcUrl;

    /** 数据库账号 */
    private String username;

    /** 加密密码 */
    private String passwordCipher;

    /** 连接池上限 */
    private Integer maxPoolSize;

    /** 状态: 1=正常, 0=禁用 */
    private Integer status;

    /** 备注说明 */
    private String remark;
}
