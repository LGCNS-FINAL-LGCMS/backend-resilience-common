package com.lgcms.resiliencecommon.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 외부 API 호출에 Resilience4j 패턴을 동적으로 적용하기 위한 어노테이션입니다.
 *
 * <h2>기본 사용법</h2>
 * <pre>{@code
 * @ExternalApiCall(name = "aiApi", fallbackMethod = "DEFAULT")
 * public String getAnswer(...)
 * }</pre>
 *
 * <h2>동적 속성 재정의 (Override)</h2>
 * <p>
 * 중앙 설정(Configuration 클래스 또는 yaml)을 상속받되, 특정 메서드에서만 개별 속성을 변경할 수 있습니다.
 * 기본값(-1 또는 비어있는 값)으로 두면 중앙 설정을 그대로 따릅니다.
 * </p>
 *
 * <h3>변경 가능한 속성 목록:</h3>
 * <ul>
 * <li><b>name</b>: 적용할 중앙 설정의 기본 이름 (e.g., "aiApi", "payment")</li>
 * <li><b>fallbackMethod</b>: 폴백 메서드 이름. "DEFAULT" 지정 시 기본 폴백, 비워두면 폴백 없음.</li>
 * </ul>
 *
 * <h4>[CircuitBreaker]</h4>
 * <ul>
 * <li><b>failureRateThreshold</b>: 실패율 임계값 (%)</li>
 * <li><b>waitDurationInOpenState</b>: 서킷이 OPEN 상태를 유지하는 시간 (ms)</li>
 * <li><b>permittedNumberOfCallsInHalfOpenState</b>: HALF_OPEN 상태에서 허용할 테스트 호출 수</li>
 * <li><b>slidingWindowSize</b>: 실패율 계산에 사용될 슬라이딩 윈도우 크기</li>
 * </ul>
 *
 * <h4>[Retry]</h4>
 * <ul>
 * <li><b>maxAttempts</b>: 최대 시도 횟수</li>
 * <li><b>intervalFunction</b>: 재시도 초기 대기 시간 (ms). 지정된 시간부터 2배씩 대기 시간이 늘어납니다 (Exponential Backoff).</li>
 * <li><b>retryExceptions</b>: 재시도할 특정 예외 클래스 목록. 기본 설정 대신 이 목록을 사용합니다.</li>
 * </ul>
 *
 * <h4>[Bulkhead]</h4>
 * <ul>
 * <li><b>maxConcurrentCalls</b>: 최대 동시 호출 수</li>
 * <li><b>maxWaitDuration</b>: 추가 실행을 위해 대기할 최대 시간 (ms)</li>
 * </ul>
 *
 * <h3>재정의 예시:</h3>
 * <pre>{@code
 * @ExternalApiCall(
 * name = "aiApi",
 * fallbackMethod = "myFallback",
 * maxAttempts = 5, // 재시도 횟수만 5회로 변경
 * failureRateThreshold = 30.0f // 실패율은 30%로 더 엄격하게 변경
 * )
 * public String getImportantAnswer(...)
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExternalApiCall {
    String name() default "default";
    String fallbackMethod() default "";

    // 서킷브레이크 재설정
    float failureRateThreshold() default -1.0f;
    int waitDurationInOpenState() default -1;
    int permittedNumberOfCallsInHalfOpenState() default -1;
    int slidingWindowSize() default -1;

    // retry 재설정
    int maxAttempts() default -1;

    /**
     * 입력단위는 ms.
     * 시작시간을 정하면 두배씩 늘려가며 대기한다.
     * @return
     */
    int intervalFunction() default -1;
    /**
     * [Retry] 재시도할 예외 클래스 목록을 지정합니다. (기본 설정에 추가됩니다)
     * 예: retryExceptions = {BaseException.class, CustomTimeoutException.class}
     */
    Class<? extends Throwable>[] retryExceptions() default {};

//    // timelimiter 재설정
//    long timeoutDurationMs() default -1L;

    // bulkhead 설정
    int maxConcurrentCalls() default -1;
    long maxWaitDuration() default -1L;
}
