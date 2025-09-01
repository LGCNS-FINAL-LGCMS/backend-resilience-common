package com.lgcms.resiliencecommon.aspect;

import com.lgcms.resiliencecommon.annotation.ExternalApiCall;
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
import java.util.Arrays;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ExternalApiCallAspect {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final DefaultResilienceFallback defaultResilienceFallback;

    @Around("@annotation(com.lgcms.resiliencecommon.annotation.ExternalApiCall)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        ExternalApiCall externalApiCall = method.getAnnotation(ExternalApiCall.class);

        String instanceName = externalApiCall.name();
        Retry retry = retryRegistry.retry(instanceName);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);

        CheckedSupplier<Object> originalMethodCall = pjp::proceed;

        CheckedSupplier<Object> decoratedSupplier = CircuitBreaker
                .decorateCheckedSupplier(circuitBreaker, originalMethodCall);
        decoratedSupplier = Retry.decorateCheckedSupplier(retry, decoratedSupplier);

        try {
            return decoratedSupplier.get();
        } catch (Throwable throwable) {
            log.warn("Resilience4j execution failed for method '{}'. Initiating fallback...",
                    pjp.getSignature().getName(), throwable);

            String fallbackMethodName = externalApiCall.fallbackMethod();

            // 1. "DEFAULT" 라고 명시적으로 요청한 경우, 기본 폴백 실행
            if ("DEFAULT".equals(fallbackMethodName)) {
                log.info("Explicit 'DEFAULT' fallback requested. Executing default fallback...");
                return defaultResilienceFallback.execute2(throwable);
            }

            // 2. 다른 이름의 커스텀 폴백을 명시적으로 요청한 경우, 해당 폴백 찾아 실행
            if (StringUtils.hasText(fallbackMethodName)) {
                log.info("Custom fallback method '{}' requested. Attempting to find and execute...", fallbackMethodName);
                Method fallbackMethod = findFallbackMethod(pjp, fallbackMethodName, throwable);
                if (fallbackMethod != null) {
                    if (fallbackMethod.getParameterCount() == pjp.getArgs().length) {
                        return fallbackMethod.invoke(pjp.getTarget(), pjp.getArgs());
                    } else {
                        Object[] fallbackArgs = Arrays.copyOf(pjp.getArgs(), pjp.getArgs().length + 1);
                        fallbackArgs[fallbackArgs.length - 1] = throwable;
                        return fallbackMethod.invoke(pjp.getTarget(), fallbackArgs);
                    }
                }
            }

            // 3. fallbackMethod를 지정하지 않았거나, 지정된 커스텀 폴백을 찾지 못한 경우 -> 원래 예외를 그냥 던짐
            log.warn("No fallback method specified or the specified one was not found. Re-throwing original exception.");
            throw throwable;
        }
    }

    /**
     * 1. 원본 메서드와 시그니처가 같은 폴백 메서드를 먼저 찾음
     * 2. 없다면, 원본 메서드 시그니처 + Throwable 파라미터를 갖는 폴백 메서드를 찾음
     */
    private Method findFallbackMethod(ProceedingJoinPoint pjp, String fallbackMethodName, Throwable cause) {
        Method originalMethod = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?>[] originalParamTypes = originalMethod.getParameterTypes();
        Object target = pjp.getTarget();

        // 1. 원본 메서드와 동일한 시그니처의 폴백 메서드 탐색
        try {
            return target.getClass().getMethod(fallbackMethodName, originalParamTypes);
        } catch (NoSuchMethodException e) {
            // 2. 원본 메서드 시그니처 + Throwable 파라미터를 갖는 폴백 메서드 탐색
            Class<?>[] paramsWithThrowable = Arrays.copyOf(originalParamTypes, originalParamTypes.length + 1);
            paramsWithThrowable[paramsWithThrowable.length - 1] = Throwable.class;
            try {
                return target.getClass().getMethod(fallbackMethodName, paramsWithThrowable);
            } catch (NoSuchMethodException ex) {
                log.warn("Fallback method '{}' not found with original or extended signature in class {}",
                        fallbackMethodName, target.getClass().getName());
                return null;
            }
        }
    }
}