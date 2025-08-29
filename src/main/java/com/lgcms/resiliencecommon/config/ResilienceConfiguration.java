package com.lgcms.resiliencecommon.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.time.Duration;

@Configuration
@EnableAspectJAutoProxy // AOP를 활성화합니다.
public class ResilienceConfiguration {

    // 기본 서킷 브레이커 설정을 Bean으로 등록
    @Bean
    public CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f) // 실패율 임계값 50%
                .waitDurationInOpenState(Duration.ofMillis(10000)) // Open 상태 유지 시간 10초
                .permittedNumberOfCallsInHalfOpenState(2) // Half-Open 상태에서 허용할 호출 수
                .slidingWindowSize(5) // 슬라이딩 윈도우 크기
                .build();
    }

    // 위에서 정의한 기본 설정을 사용하는 서킷 브레이커 레지스트리를 Bean으로 등록
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

        CircuitBreakerConfig circuitBreakerConfig = defaultCircuitBreakerConfig();

        circuitBreakerRegistry.addConfiguration("default2", circuitBreakerConfig);
        return circuitBreakerRegistry;
    }
}