package com.lgcms.resiliencecommon.fallback;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DefaultResilienceFallback {

    // 모듈이 제공하는 기본 폴백 메서드
    public ResponseEntity<String> execute(Throwable t) {
        log.warn("Executing default fallback. Reason: {}", t.getMessage());
        return ResponseEntity
                .status(503)
                .body("Service is unavailable. A default fallback response was provided.");
    }
}