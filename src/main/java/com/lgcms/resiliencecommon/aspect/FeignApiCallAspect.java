// resilience-common/src/main/java/com/lgcms/resiliencecommon/aop/FeignApiCallAspect.java

package com.lgcms.resiliencecommon.aspect;

import com.lgcms.resiliencecommon.annotation.FeignApiCall;
import com.lgcms.resiliencecommon.fallback.DefaultResilienceFallback;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class FeignApiCallAspect {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final DefaultResilienceFallback defaultResilienceFallback;

    @Around("@annotation(com.lgcms.resiliencecommon.annotation.FeignApiCall)")
    public Object feignApiCall(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        FeignApiCall feignApiCall = method.getAnnotation(FeignApiCall.class);

        String instanceName = feignApiCall.name();
        Retry retry = retryRegistry.retry(instanceName);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);

        // 원본 메서드 호출(pjp::proceed)을 CheckedSupplier로 준비합니다.
        CheckedSupplier<Object> originalMethodCall = pjp::proceed;

        // 서킷 브레이커와 재시도 순서로 데코레이팅합니다. (재시도가 서킷 브레이커를 감싸도록)
        CheckedSupplier<Object> decoratedSupplier = CircuitBreaker
                .decorateCheckedSupplier(circuitBreaker, originalMethodCall);
        decoratedSupplier = Retry.decorateCheckedSupplier(retry, decoratedSupplier);

        try {
            // 데코레이팅한 로직 실행
            return decoratedSupplier.get();
        } catch (Throwable throwable) {
            // 리트라이 다 실패할시 이 블록이 동작 !
            log.warn("Resilience4j execution failed for method '{}'. Initiating fallback...",
                    pjp.getSignature().getName(), throwable);
            String fallbackMethodName = feignApiCall.fallbackMethod();

            // DEFAULT면 execute로
            if ("DEFAULT".equals(fallbackMethodName)) {
                return defaultResilienceFallback.execute(throwable);
            }
            // 사용자가 직접 설정할 경우 이것이 동작합니다.
            else if (StringUtils.hasText(fallbackMethodName)) {
                Method fallbackMethod = findFallbackMethod(pjp, fallbackMethodName);
                if (fallbackMethod != null) {
                    return fallbackMethod.invoke(pjp.getTarget(), throwable);
                }
            }

            // 폴백이 지정되지 않은 경우
            throw throwable;
        }
    }



    private Method findFallbackMethod(ProceedingJoinPoint pjp, String fallbackMethodName) {
        try {
            // FeignClient 인터페이스의 default 메서드를 포함하여 검색
            for (Class<?> iface : pjp.getTarget().getClass().getInterfaces()) {
                try {
                    return iface.getMethod(fallbackMethodName, Throwable.class);
                } catch (NoSuchMethodException e) {
                    // 무시하고 다음 인터페이스 확인
                }
            }
            return pjp.getTarget().getClass().getMethod(fallbackMethodName, Throwable.class);
        } catch (NoSuchMethodException e) {
            log.warn("Fallback method '{}' with Throwable parameter not found in class {} or its interfaces",
                    fallbackMethodName, pjp.getTarget().getClass().getName());
            return null;
        }
    }
}