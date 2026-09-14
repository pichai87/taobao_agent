package com.ecom.api;

import com.ecom.domain.BusinessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Map<String,String>> business(BusinessException error) {
        String code=error.code();
        HttpStatus status=code.equals("RUN_NOT_FOUND")?HttpStatus.NOT_FOUND:
            code.equals("INVALID_RUN_STATE") || code.equals("IDEMPOTENCY_CONFLICT")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code",code));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST"));
    }
}

