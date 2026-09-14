package com.ecom.domain;

/** 只暴露稳定错误码，不把数据库和模型的内部异常泄露给客户端。 */
public class BusinessException extends RuntimeException {
    private final String code;
    public BusinessException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}

