package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import com.dhp.etl.enums.AlertChannelTypeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 告警配置实体 (规范 03 §4.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_alert_config")
public class EtlAlertConfig extends BaseTenantEntity {

    /** 通道名称 */
    private String channelName;

    /** 通道类型: WECHAT_WORK / DINGTALK / FEISHU / EMAIL */
    private AlertChannelTypeEnum channelType;

    /** Webhook 地址 */
    private String webhookUrl;

    /** 验签密钥 */
    private String secretToken;

    /** 邮件接收人(逗号分隔) */
    private String emailReceivers;

    /** 状态: 1=正常, 0=禁用 */
    private Integer status;
}
