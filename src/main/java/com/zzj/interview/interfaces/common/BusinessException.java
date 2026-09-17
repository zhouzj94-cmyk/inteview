package com.zzj.interview.interfaces.common;

/**
 * 业务异常
 * 用于表示可预见的业务错误（如参数校验失败、状态不合法等）
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
