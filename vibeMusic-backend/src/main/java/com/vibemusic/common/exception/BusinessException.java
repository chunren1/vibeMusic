package com.vibemusic.common.exception;

import lombok.Getter;

/**
 * 业务异常（自定义运行时异常）
 *
 * 使用方式：throw new BusinessException(400, "xxx 不能为空");
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }
}
