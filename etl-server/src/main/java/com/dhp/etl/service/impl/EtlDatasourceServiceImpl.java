package com.dhp.etl.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.dto.DatasourceTestDTO;
import com.dhp.etl.entity.EtlDatasource;
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtlDatasourceServiceImpl extends BaseCrudServiceImpl<EtlDatasourceMapper, EtlDatasource> implements EtlDatasourceService {

    private final CipherUtil cipherUtil;
    private final ConcurrentHashMap<Long, HikariDataSource> dataSourcePool = new ConcurrentHashMap<>();

    @Override
    protected Class<EtlDatasource> getClaas() {
        return EtlDatasource.class;
    }

    @Override
    public boolean save(EtlDatasource entity) {
        ensureJdbcUrl(entity);
        if (StrUtil.isNotBlank(entity.getPasswordCipher())) {
            entity.setPasswordCipher(cipherUtil.encrypt(entity.getPasswordCipher()));
        }
        return super.save(entity);
    }

    @Override
    public boolean updateById(EtlDatasource entity) {
        ensureJdbcUrl(entity);
        EtlDatasource old = this.getById(entity.getId());
        if (old == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标数据源不存在或已被删除");
        }
        if (StrUtil.isNotBlank(entity.getPasswordCipher())) {
            // 如果传入新密码，重新加密
            entity.setPasswordCipher(cipherUtil.encrypt(entity.getPasswordCipher()));
        } else {
            // 如果未传，不覆盖已有密码
            entity.setPasswordCipher(old.getPasswordCipher());
        }
        // 自动对齐最新乐观锁版本号，避免前端缓存旧版本号导致更新落空
        entity.setObjectVersion(old.getObjectVersion());

        // 清除旧连接池缓存
        HikariDataSource oldDs = dataSourcePool.remove(entity.getId());
        if (oldDs != null && !oldDs.isClosed()) {
            oldDs.close();
        }
        return super.updateById(entity);
    }

    @Override
    public String testConnection(DatasourceTestDTO testDTO) {
        String password = testDTO.getPassword();
        if (StrUtil.isBlank(password) && testDTO.getId() != null) {
            EtlDatasource ds = this.getById(testDTO.getId());
            if (ds != null) {
                password = cipherUtil.decrypt(ds.getPasswordCipher());
            }
        }

        String jdbcUrl = testDTO.getJdbcUrl();
        if (StrUtil.isBlank(jdbcUrl)) {
            if (StrUtil.isNotBlank(testDTO.getHost()) && StrUtil.isNotBlank(testDTO.getDatabaseName())) {
                int port = testDTO.getPort() != null && testDTO.getPort() > 0 ? testDTO.getPort() : 3306;
                String enc = StrUtil.blankToDefault(testDTO.getEncoding(), "UTF-8");
                jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                        testDTO.getHost().trim(), port, testDTO.getDatabaseName().trim(), enc.trim());
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

        try (Connection conn = DriverManager.getConnection(jdbcUrl, testDTO.getUsername(), password);
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
        if (StrUtil.isBlank(entity.getJdbcUrl()) && StrUtil.isNotBlank(entity.getHost()) && StrUtil.isNotBlank(entity.getDatabaseName())) {
            int port = entity.getPort() != null && entity.getPort() > 0 ? entity.getPort() : 3306;
            String enc = StrUtil.blankToDefault(entity.getEncoding(), "UTF-8");
            String url = String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=%s&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                    entity.getHost().trim(), port, entity.getDatabaseName().trim(), enc.trim());
            entity.setJdbcUrl(url);
        }
        if (StrUtil.isBlank(entity.getDriverClass())) {
            entity.setDriverClass("com.mysql.cj.jdbc.Driver");
        }
    }

    @Override
    public DataSource getDataSource(Long datasourceId) {
        return dataSourcePool.computeIfAbsent(datasourceId, id -> {
            EtlDatasource ds = this.getById(id);
            if (ds == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "数据源未找到: id=" + id);
            }
            String password = cipherUtil.decrypt(ds.getPasswordCipher());

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(ds.getJdbcUrl());
            config.setUsername(ds.getUsername());
            config.setPassword(password);
            if (StrUtil.isNotBlank(ds.getDriverClass())) {
                config.setDriverClassName(ds.getDriverClass().trim());
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
