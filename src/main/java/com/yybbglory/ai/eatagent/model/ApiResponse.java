package com.yybbglory.ai.eatagent.model;

/**
 * 统一 API 响应格式
 *
 * @param code    状态码（200=成功，其他=错误）
 * @param message 提示信息
 * @param data    响应数据
 */
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 创建成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 创建错误响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
