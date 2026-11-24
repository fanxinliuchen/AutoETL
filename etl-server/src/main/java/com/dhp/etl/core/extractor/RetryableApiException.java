package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;

/**
 * 可重试的 WebAPI 调用异常：网络超时、连接重置、HTTP 5xx / 429。
 *
 * <p>分页抽取值逻辑只对这类异常做页级重试；业务错误（字段不存在、参数非法、会话失效）
 * 不是它的子类，避免误重试放大问题。
 */
public class RetryableApiException extends BizException {

    public RetryableApiException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }
}
