package com.yybbglory.ai.eatagent.exception;

/**
 * 业务异常类
 * 用于表示可预期的业务错误，携带 HTTP 状态码和错误消息
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
