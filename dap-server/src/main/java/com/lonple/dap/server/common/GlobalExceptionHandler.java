package com.lonple.dap.server.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 统一异常处理。返回 {@link ResponseEntity} 并强制 JSON，避免 SSE
 *（{@code produces=text/event-stream}）接口抛错时 Content-Type 已锁定导致二次失败。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        log.warn("biz error code={} message={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.OK;
        if (e.getCode() == 401) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (e.getCode() == 403) {
            status = HttpStatus.FORBIDDEN;
        }
        return json(status, ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValid(Exception e) {
        String message = e.getMessage();
        if (e instanceof MethodArgumentNotValidException manv && manv.getBindingResult().getFieldError() != null) {
            var err = manv.getBindingResult().getFieldError();
            message = err.getField() + ": " + err.getDefaultMessage();
        } else if (e instanceof BindException be && be.getBindingResult().getFieldError() != null) {
            var err = be.getBindingResult().getFieldError();
            message = err.getField() + ": " + err.getDefaultMessage();
        }
        log.warn("参数校验失败: {}", message);
        return json(HttpStatus.OK, ApiResponse.fail(400, message));
    }

    /**
     * SSE 客户端断开等场景：响应通道已不可用，不再尝试写 JSON。
     */
    @ExceptionHandler({
            AsyncRequestNotUsableException.class,
            HttpMessageNotWritableException.class
    })
    public ResponseEntity<Void> handleUnusableResponse(Exception e, HttpServletRequest request) {
        log.debug("响应不可写 path={} msg={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e, HttpServletRequest request) {
        log.error("unhandled error path={}", request.getRequestURI(), e);
        return json(HttpStatus.OK, ApiResponse.fail(500, "服务器内部错误"));
    }

    private static ResponseEntity<ApiResponse<Void>> json(HttpStatus status, ApiResponse<Void> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
