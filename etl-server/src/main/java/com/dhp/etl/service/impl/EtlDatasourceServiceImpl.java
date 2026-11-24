package com.dhp.etl.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.dto.DatasourceTestDTO;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.entity.EtlKingdeeCredential;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.mapper.EtlDatasourceMapper;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.util.CipherUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtlDatasourceServiceImpl extends BaseCrudServiceImpl<EtlDatasourceMapper, EtlDatasource> implements EtlDatasourceService {

    private final CipherUtil cipherUtil;
    private final com.dhp.etl.core.connector.ConnectionManager connectionManager;
    private final ConcurrentHashMap<Long, HikariDataSource> dataSourcePool = new ConcurrentHashMap<>();

    @Override
    protected Class<EtlDatasource> getClaas() {
        return EtlDatasource.class;
    }

    @Override
    public boolean save(EtlDatasource entity) {
        normalizeByType(entity, null);
        return super.save(entity);
    }

    @Override
    public boolean updateById(EtlDatasource entity) {
        EtlDatasource old = this.getById(entity.getId());
        if (old == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标数据源不存在或已被删除");
        }
        normalizeByType(entity, old);
        // 自动对齐最新乐观锁版本号，避免前端缓存旧版本号导致更新落空
        entity.setObjectVersion(old.getObjectVersion());

        // 清除旧连接池缓存
        HikariDataSource oldDs = dataSourcePool.remove(entity.getId());
        if (oldDs != null && !oldDs.isClosed()) {
            oldDs.close();
        }
        connectionManager.invalidate(entity.getId());
        return super.updateById(entity);
    }

    /**
     * 按连接类型归一化：MySQL 走 JDBC 三件套，其余类型走加密的 config JSON。
     *
     * <p>两类参数互斥——API 连接若残留 jdbc_url/username 会让人误以为能当库用。
     */
    private void normalizeByType(EtlDatasource entity, EtlDatasource old) {
        ConnTypeEnum type = ConnTypeEnum.of(entity.getConnType());
        entity.setConnType(type.getCode());
        if (!type.isApi()) {
            ensureJdbcUrl(entity);
            if (StrUtil.isBlank(entity.getDsType())) {
                entity.setDsType(type.getCode());
            }
            if (StrUtil.isNotBlank(entity.getPasswordCipher())) {
                entity.setPasswordCipher(cipherUtil.encrypt(entity.getPasswordCipher()));
            } else if (old != null) {
                // 未传密码表示不覆盖
                entity.setPasswordCipher(old.getPasswordCipher());
            }
            entity.setConfigCipher(null);
            return;
        }
        entity.setJdbcUrl(null);
        entity.setUsername(null);
        entity.setPasswordCipher(null);
        entity.setDsType(type.getCode());
        String oldCipher = old == null ? null : old.getConfigCipher();
        if (StrUtil.isNotBlank(entity.getConfigCipher())) {
            entity.setConfigCipher(connectionManager.mergeSecrets(oldCipher, entity.getConfigCipher()));
        } else if (old != null) {
            entity.setConfigCipher(oldCipher);
        } else {
            throw new BizException(ErrorCode.PARAM_ERROR, "请填写连接配置（" + type.getDesc() + "）");
        }
    }

    @Override
    public String testConnection(DatasourceTestDTO testDTO) {
        // API 类连接：真登录一次验证凭据，不碰 JDBC
        if (StrUtil.isNotBlank(testDTO.getConnType())
                && ConnTypeEnum.of(testDTO.getConnType()).isApi()) {
            ConnTypeEnum type = ConnTypeEnum.of(testDTO.getConnType());
            if (StrUtil.isNotBlank(testDTO.getConfigJson())) {
                // 编辑态弹窗回显的是打码值，带上 id 才能用库里的真实密钥去登录
                return String.valueOf(connectionManager
                        .testApiLogin(type, testDTO.getConfigJson(), testDTO.getId()).get("message"));
            }
            if (testDTO.getId() != null) {
                return String.valueOf(connectionManager.testApiLogin(testDTO.getId()).get("message"));
            }
            throw new BizException(ErrorCode.PARAM_ERROR, "请先填写连接配置再测试");
        }

        String password = testDTO.getPassword();
        String username = testDTO.getUsername();
        if ((StrUtil.isBlank(password) || StrUtil.isBlank(username)) && testDTO.getId() != null) {
            EtlDatasource ds = this.getById(testDTO.getId());
            if (ds != null) {
                if (StrUtil.isBlank(password)) {
                    password = cipherUtil.decrypt(ds.getPasswordCipher());
                }
                // 只传 id 测试（编辑态/命令行）时，账号也要回退到库里存的，否则 PG 会拿操作系统用户名去连
                if (StrUtil.isBlank(username)) {
                    username = ds.getUsername();
                }
            }
        }

        String jdbcUrl = testDTO.getJdbcUrl();
        if (StrUtil.isBlank(jdbcUrl)) {
            if (StrUtil.isNotBlank(testDTO.getHost()) && StrUtil.isNotBlank(testDTO.getDatabaseName())) {
                if (ConnTypeEnum.of(testDTO.getConnType()) == ConnTypeEnum.POSTGRESQL) {
                    int port = testDTO.getPort() != null && testDTO.getPort() > 0 ? testDTO.getPort() : 5432;
                    jdbcUrl = String.format(
                            "jdbc:postgresql://%s:%d/%s?currentSchema=public&sslmode=disable&ApplicationName=dhp-etl",
                            testDTO.getHost().trim(), port, testDTO.getDatabaseName().trim());
                } else {
                    int port = testDTO.getPort() != null && testDTO.getPort() > 0 ? testDTO.getPort() : 3306;
                    String enc = StrUtil.blankToDefault(testDTO.getEncoding(), "UTF-8");
                    jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                            testDTO.getHost().trim(), port, testDTO.getDatabaseName().trim(), enc.trim());
                }
            } else if (testDTO.getId() != null) {
                EtlDatasource ds = this.getById(testDTO.getId());
                if (ds != null) {
                    jdbcUrl = ds.getJdbcUrl();
                }
            }
        }

        if (StrUtil.isBlank(jdbcUrl)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无法获取或合成有效 JDBC 连接串");
        }

        if (StrUtil.isNotBlank(testDTO.getDriverClass())) {
            try {
                Class.forName(testDTO.getDriverClass().trim());
            } catch (ClassNotFoundException ignored) {
            }
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            if (rs.next()) {
                return "连接成功！数据库通信正常。";
            }
            return "连接异常，无响应。";
        } catch (Exception e) {
            log.error("数据源测试连接失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_DATASOURCE_CONNECT_FAIL, "连接失败: " + e.getMessage());
        }
    }

    private void ensureJdbcUrl(EtlDatasource entity) {
        ConnTypeEnum type = ConnTypeEnum.of(entity.getConnType());
        if (StrUtil.isBlank(entity.getJdbcUrl()) && StrUtil.isNotBlank(entity.getHost()) && StrUtil.isNotBlank(entity.getDatabaseName())) {
            if (type == ConnTypeEnum.POSTGRESQL) {
                int port = entity.getPort() != null && entity.getPort() > 0 ? entity.getPort() : 5432;
                entity.setJdbcUrl(String.format(
                        "jdbc:postgresql://%s:%d/%s?currentSchema=public&sslmode=disable&ApplicationName=dhp-etl",
                        entity.getHost().trim(), port, entity.getDatabaseName().trim()));
            } else {
                int port = entity.getPort() != null && entity.getPort() > 0 ? entity.getPort() : 3306;
                String enc = StrUtil.blankToDefault(entity.getEncoding(), "UTF-8");
                entity.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                        entity.getHost().trim(), port, entity.getDatabaseName().trim(), enc.trim()));
            }
        }
        if (StrUtil.isBlank(entity.getDriverClass())) {
            entity.setDriverClass(type == ConnTypeEnum.POSTGRESQL
                    ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
        }
    }

    @Override
    public DataSource getDataSource(Long datasourceId) {
        return dataSourcePool.computeIfAbsent(datasourceId, id -> {
            EtlDatasource ds = this.getById(id);
            if (ds == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "数据源未找到: id=" + id);
            }
            // 云表/金蝶是 API 连接，拿不到 JDBC 数据源，早点报清楚而不是抛空指针
            ConnTypeEnum connType = ConnTypeEnum.of(ds.getConnType());
            if (connType.isApi()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "「" + ds.getDsName() + "」是 " + connType.getDesc()
                                + " 连接，不能当作数据库数据源使用，请改选 MySQL / PostgreSQL 连接");
            }
            String password = cipherUtil.decrypt(ds.getPasswordCipher());

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(ds.getJdbcUrl());
            config.setUsername(ds.getUsername());
            config.setPassword(password);
            if (StrUtil.isNotBlank(ds.getDriverClass())) {
                config.setDriverClassName(ds.getDriverClass().trim());
            }
            if (connType == ConnTypeEnum.POSTGRESQL) {
                // PG 长连接容易被网络设备回收，开 TCP keepAlive；应用名便于在库里排查会话来源
                config.addDataSourceProperty("ApplicationName", "dhp-etl");
                config.addDataSourceProperty("tcpKeepAlive", "true");
            }
            config.setMaximumPoolSize(ds.getMaxPoolSize() != null && ds.getMaxPoolSize() > 0 ? ds.getMaxPoolSize() : 5);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setPoolName("EtlPool-" + id);
            return new HikariDataSource(config);
        });
    }
}
