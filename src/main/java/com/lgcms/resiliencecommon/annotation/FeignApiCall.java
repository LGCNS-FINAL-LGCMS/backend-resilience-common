// resilience-common/src/main/java/com/lgcms/resiliencecommon/annotation/FeignApiCall.java

package com.lgcms.resiliencecommon.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Feign은 서킷브레이크나 리트라이를 쓰고 폴백메소드를 각 서버에서 적용하면 될것 같습니다.
 */
@Target(ElementType.METHOD) // 메서드에 적용
@Retention(RetentionPolicy.RUNTIME) // 런타임 시에 어노테이션 정보 유지
public @interface FeignApiCall {

    /**
     * config에 정의된 Resilience4j 인스턴스 이름을 지정합니다.
     * 이 이름을 기반으로 Circuit Breaker와 Retry 설정을 가져옵니다.
     */
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


}