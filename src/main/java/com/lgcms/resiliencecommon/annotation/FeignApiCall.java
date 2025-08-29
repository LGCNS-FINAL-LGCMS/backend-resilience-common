// resilience-common/src/main/java/com/lgcms/resiliencecommon/annotation/FeignApiCall.java

package com.lgcms.resiliencecommon.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD) // 메서드에 적용
@Retention(RetentionPolicy.RUNTIME) // 런타임 시에 어노테이션 정보 유지
public @interface FeignApiCall {

    /**
     * config에 정의된 Resilience4j 인스턴스 이름을 지정합니다.
     * 이 이름을 기반으로 Circuit Breaker와 Retry 설정을 가져옵니다.
     */
    String name() default "default";


    String fallbackMethod() default "DEFAULT";
}