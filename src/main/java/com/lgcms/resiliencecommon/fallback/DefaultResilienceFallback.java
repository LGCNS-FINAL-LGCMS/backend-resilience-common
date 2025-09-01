package com.lgcms.resiliencecommon.fallback;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DefaultResilienceFallback {
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public class ChatResponse {
        private String answer;
        private String imageUrl;
    }

    // 모듈이 제공하는 기본 폴백 메서드
    public ResponseEntity<String> execute(Throwable t) {
        log.warn("Executing default fallback. Reason: {}", t.getMessage());
        return ResponseEntity
                .status(503)
                .body("Service is unavailable. A default fallback response was provided.");
    }

    // 모듈이 제공하는 기본 폴백 메서드
    public ChatResponse execute2(Throwable t) {
        log.warn("Executing default fallback. Reason: {}", t.getMessage());
        ChatResponse chatResponse = new ChatResponse();
        chatResponse.answer = "Hello World";
        chatResponse.imageUrl = "https://www.google.com";
        return chatResponse;
    }
}