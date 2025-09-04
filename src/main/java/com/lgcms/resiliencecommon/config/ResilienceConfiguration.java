package com.lgcms.resiliencecommon.config;

import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;


/**
 *  "payment" = 서킷브레이크, 타임리미터
 *  "videoProcessing" = 서킷브레이크, 리트라이, 스레드풀벌크헤드
 *  "aiApi" = 서킷브레이크, 리트라이, 벌크헤드(ai는 시간오래걸리니까 장애격리 용도로)
 *  ----
 *  "dbConnection" = 서킷브레이크, 벌크헤드
 *  ---
 *  "feign" = 서킷브레이크, 리트라이
 */
@Configuration
@EnableAspectJAutoProxy // AOP를 활성화합니다.
public class ResilienceConfiguration {

//  서킷브레이크 설정
    /**
     *  영상 처리
     * - 작업 시간이 길고, 서버 부하가 높을 수 있음. 일시적 실패는 허용 가능.
     * - 실패에 다소 관대하지만, 한번 Open 되면 길게 대기하여 서버가 복구될 시간을 줌.
     */
    @Bean
    public CircuitBreakerConfig videoProcessingCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f) // 실패율 50%
                .waitDurationInOpenState(Duration.ofSeconds(60)) // open 상태 유지 시간 60초
                .permittedNumberOfCallsInHalfOpenState(5) // halfopen 상태에서 5번 테스트 호출
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED) // 숫자기반 슬라이딩윈도우
                .slidingWindowSize(20) // 20번호출 기준
                .build();
    }




    // 레지스트리에 빈 등록 해서 사용
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

        // 영상 처리
        //- 작업 시간이 길고, 서버 부하가 높을 수 있음. 일시적 실패는 허용 가능.
        //- 실패에 다소 관대하지만, 한번 Open 되면 길게 대기하여 서버가 복구될 시간을 줌.
        CircuitBreakerConfig videoProcessingCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f) // 실패율 50%
                .waitDurationInOpenState(Duration.ofSeconds(60)) // open 상태 유지 시간 60초
                .permittedNumberOfCallsInHalfOpenState(5) // halfopen 상태에서 5번 테스트 호출
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED) // 숫자기반 슬라이딩윈도우
                .slidingWindowSize(20) // 20번호출 기준
                .build();

        // 외부 결제 연동
        // 실패에 매우 민감하게 반응하고, 한번 Open 되면 아주 길게 대기하여 PG사 장애가 완전히 해결될 때까지 대기
        CircuitBreakerConfig paymentCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(25.0f)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .permittedNumberOfCallsInHalfOpenState(1)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .build();

        //          AI API 호출
//          외부 API이므로 Throttling 등 일시적 오류가 잦을 수 있음. 주기적 호출 필요
        CircuitBreakerConfig aiApiCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(40.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(30)
                .build();

        CircuitBreakerConfig dbConnectionCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50) // 시도 50번 호출을 기준으로 실패율 계산
                .minimumNumberOfCalls(20) // 최소 20번에 요청이 있어야 중지
                .failureRateThreshold(60.0f) // 실패율 60퍼이상시
                .waitDurationInOpenState(Duration.ofSeconds(20)) // 오픈상태 20초 유지
                .build();

        CircuitBreakerConfig feignCircuitbreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(30) // 시도 30번 호출을 기준으로 실패율 계산
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();

        // 각 시나리오의 이름을 키로 사용하여 설정을 레지스트리에 추가합니다.
        registry.addConfiguration("videoProcessing", videoProcessingCircuitBreakerConfig);
        registry.addConfiguration("payment", paymentCircuitBreakerConfig);
        registry.addConfiguration("aiApi", aiApiCircuitBreakerConfig);
        registry.addConfiguration("dbConnection", dbConnectionCircuitBreakerConfig);
        registry.addConfiguration("feign", feignCircuitbreakerConfig);

        return registry;
    }

//  Retry 컨피그 작성
    @Bean
    public RetryRegistry retryRegistry() {
        // AI API는 bedrock 예외 목록을 기준으로 동작합니다.
        // 429에러인 throttling 에러에 자동으로 캐치합니다. (Too many request... )
        RetryConfig aiApiRetryConfig = RetryConfig.custom()
                .maxAttempts(4)
                // 250ms부터 2배씩 대기시간가지고 리트라이 시도
                .intervalFunction(IntervalFunction.ofExponentialBackoff(250, 2.0))
                .build();

        // 기본적으로 재시도 3번입니다.
        RetryConfig videoProcessingRetryConfig = RetryConfig.custom()
                .intervalFunction(IntervalFunction.ofExponentialBackoff(2000, 2.0)) // 2초 -> 4초 간격으로 재시도
                .retryOnException(e -> e instanceof IOException || e instanceof TimeoutException)
                .build();

        // 기본적으로 재시도 3번
        RetryConfig paymentRetryConfig = RetryConfig.custom()
                .waitDuration(Duration.ofSeconds(1))
                .retryOnException(e -> e instanceof SocketTimeoutException)
                .build();

        // 재시도 (maxAttempts 3번)
        RetryConfig feignRetryConfig = RetryConfig.custom()
                .waitDuration(Duration.ofMillis(500))
                .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2.0))
                .build();

        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.addConfiguration("aiApi", aiApiRetryConfig);
        registry.addConfiguration("videoProcessing", videoProcessingRetryConfig);
        registry.addConfiguration("feignRetryConfig", paymentRetryConfig);
//        registry.addConfiguration("payment", paymentRetryConfig);
        return registry;
    }

//    Bulkhead - 세마포어
    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.ofDefaults();

        // ai용 세마포어 벌크헤드
        BulkheadConfig aiApiBulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ofMillis(500))
                .build();

        BulkheadConfig dbConnectionBulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(20) // 동시 db요청 스레드 수
                .maxWaitDuration(Duration.ofMillis(500)) // 스레드가 대기할 최대 시간 (초과시  BulkheadFullException)
                .build();

        bulkheadRegistry.addConfiguration("aiApi", aiApiBulkheadConfig);
        bulkheadRegistry.addConfiguration("dbConnection", dbConnectionBulkheadConfig);

        return bulkheadRegistry;
    }

//    Bulkhead - ThreadPool
    @Bean
    public ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry(){
        ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.ofDefaults();

        //영상용 ThreadPoopBulkhead
        ThreadPoolBulkheadConfig videoProcessingThreadPoolBulkheadConfig = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(5)  // 최대 5개 사이즈
                .coreThreadPoolSize(2) //일반적으로 2개 사이즈
                .queueCapacity(10) // 10개작업 대기큐 저장 (default : 100)
                .keepAliveDuration(Duration.ofSeconds(60)) // 유휴상태인 스레드 지우기 (default : 20ms)
                .build();

        registry.addConfiguration("videoProcessing", videoProcessingThreadPoolBulkheadConfig);
        return registry;
    }

//    Timelimiter (결제 승인에 사용합니다.)
    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(){
        TimeLimiterRegistry registry = TimeLimiterRegistry.ofDefaults();

        TimeLimiterConfig paymentTimeLimiterConfig = TimeLimiterConfig.custom()
                .cancelRunningFuture(true) // default : false. 서버스레드자원회수를 위해 true로
                .timeoutDuration(Duration.ofSeconds(5))
                .build();

        registry.addConfiguration("payment", paymentTimeLimiterConfig);
        return registry;
    }
}