package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 告警通道类型枚举
 */
@Getter
@AllArgsConstructor
public enum AlertChannelTypeEnum {
    WECHAT_WORK("WECHAT_WORK", "企业微信机器人"),
    DINGTALK("DINGTALK", "钉钉机器人"),
    FEISHU("FEISHU", "飞书机器人"),
    EMAIL("EMAIL", "邮件通知");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;
}
