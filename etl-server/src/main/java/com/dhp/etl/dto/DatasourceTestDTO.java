package com.dhp.etl.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 测试数据源连接入参 DTO
 */
@Data
public class DatasourceTestDTO implements Serializable {

    private Long id;

    /** 主机 */
    private String host;

    /** 端口 */
    private Integer port;

    /** 数据库名称 */
    private String databaseName;

    /** 编码 */
    private String encoding;

    /** 驱动 */
    private String driverClass;

    /** JDBC 连接串 (可选，若为空则由 host+port+databaseName+encoding 自动合成) */
    private String jdbcUrl;

    /** 数据库用户名 */
    private String username;

    /** 数据库密码 */
    private String password;
}
