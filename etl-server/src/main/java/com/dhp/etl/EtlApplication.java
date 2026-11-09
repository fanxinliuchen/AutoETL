package com.dhp.etl;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DHP 轻量级 ETL 服务启动类 (规范 03 §7.1)
 */
@SpringBootApplication(scanBasePackages = {"com.dhp.etl", "com.dhp.common"})
@MapperScan("com.dhp.etl.mapper")
@EnableScheduling
public class EtlApplication {

    public static void main(String[] args) {
        SpringApplication.run(EtlApplication.class, args);
    }
}
