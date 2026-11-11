package com.dhp.etl.core.alert;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.etl.entity.EtlAlertConfig;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.service.EtlAlertConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 告警通知中心 - 支持企业微信 / 钉钉 / 飞书 Webhook 机器人及邮件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertService {

    /** 同一流程的告警最小间隔：防止重试/重复触发把群刷屏 */
    private static final long MIN_INTERVAL_MS = 60_000L;

    private final EtlAlertConfigService alertConfigService;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastSentAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 发送任务失败告警通知
     */
    public void sendAlert(EtlPipeline pipeline, EtlTaskInstance instance, Exception ex) {
        if (pipeline.getAlertConfigId() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastSentAt.get(pipeline.getId());
        if (last != null && now - last < MIN_INTERVAL_MS) {
            log.warn("[告警] 流程 {} 距上次告警不足 {} 秒，本次跳过（避免刷屏）",
                    pipeline.getPipelineCode(), MIN_INTERVAL_MS / 1000);
            return;
        }

        EtlAlertConfig config = alertConfigService.getById(pipeline.getAlertConfigId());
        if (config == null || config.getStatus() != 1 || StrUtil.isBlank(config.getWebhookUrl())) {
            return;
        }

        String title = "【ETL 流程执行异常告警】";
        String retryLine = instance.getRetryCount() != null && instance.getRetryCount() > 0
                ? "- **自动重试**: 已重试 " + instance.getRetryCount() + " 次仍失败\n"
                : "";
        String content = String.format("""
                ### 🚨 %s
                - **流程名称**: %s (%s)
                - **运行状态**: %s
                - **触发方式**: %s
                - **任务批次**: %s
                %s- **触发时间**: %s
                - **错误摘要**: %s
                """,
                title,
                pipeline.getPipelineName(), pipeline.getPipelineCode(),
                instance.getTaskStatus() != null ? instance.getTaskStatus().getDesc() : "失败",
                instance.getTriggerType() != null ? instance.getTriggerType().getDesc() : "未知",
                instance.getId(),
                retryLine,
                DateUtil.formatDateTime(new Date()),
                StrUtil.sub(ex.getMessage(), 0, 500)
        );

        log.info("触发告警通知: 流程={}, 通道类型={}, URL={}",
                pipeline.getPipelineCode(), config.getChannelType(), config.getWebhookUrl());

        try {
            switch (config.getChannelType()) {
                case WECHAT_WORK -> sendWechatWorkAlert(config.getWebhookUrl(), content);
                case DINGTALK -> sendDingTalkAlert(config.getWebhookUrl(), title, content);
                case FEISHU -> sendFeishuAlert(config.getWebhookUrl(), title, content);
                default -> sendGenericAlert(config.getWebhookUrl(), content);
            }
            lastSentAt.put(pipeline.getId(), now);
        } catch (Exception e) {
            log.error("发送告警通知异常: {}", e.getMessage());
        }
    }

    private void sendWechatWorkAlert(String url, String content) {
        JSONObject markdown = new JSONObject();
        markdown.set("content", content);
        JSONObject root = new JSONObject();
        root.set("msgtype", "markdown");
        root.set("markdown", markdown);

        HttpRequest.post(url).body(root.toString()).timeout(5000).execute();
    }

    private void sendDingTalkAlert(String url, String title, String content) {
        JSONObject markdown = new JSONObject();
        markdown.set("title", title);
        markdown.set("text", content);
        JSONObject root = new JSONObject();
        root.set("msgtype", "markdown");
        root.set("markdown", markdown);

        HttpRequest.post(url).body(root.toString()).timeout(5000).execute();
    }

    private void sendFeishuAlert(String url, String title, String content) {
        JSONObject zhCn = new JSONObject();
        zhCn.set("title", title);
        zhCn.set("content", JSONUtil.createArray().put(
                JSONUtil.createArray().put(new JSONObject().set("tag", "text").set("text", content))
        ));

        JSONObject post = new JSONObject();
        post.set("zh_cn", zhCn);

        JSONObject root = new JSONObject();
        root.set("msg_type", "post");
        root.set("content", new JSONObject().set("post", post));

        HttpRequest.post(url).body(root.toString()).timeout(5000).execute();
    }

    private void sendGenericAlert(String url, String content) {
        HttpRequest.post(url).body(content).timeout(5000).execute();
    }
}
