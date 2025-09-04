// resilience-common/src/main/java/com/lgcms/resiliencecommon/aop/FeignApiCallAspect.java

package com.lgcms.resiliencecommon.aspect;

import com.lgcms.resiliencecommon.annotation.ExternalApiCall;
import com.lgcms.resiliencecommon.annotation.FeignApiCall;
import com.lgcms.resiliencecommon.fallback.DefaultResilienceFallback;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

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
        String configName = feignApiCall.name();

        String instanceName = feignApiCall.name();

        // 원본 메서드 호출(pjp::proceed)을 CheckedSupplier로 준비합니다.
        CheckedSupplier<Object> originalMethodCall = pjp::proceed;

        // 동적으로 생성
        Retry retry = createDynamicRetry(feignApiCall, configName);
        CircuitBreaker circuitBreaker = createDynamicCircuitBreaker(feignApiCall, configName);

        // 서킷 브레이커와 재시도 순서로 데코레이팅합니다. (재시도가 서킷 브레이커를 감싸도록)
        CheckedSupplier<Object> decoratedSupplier = CircuitBreaker.decorateCheckedSupplier(circuitBreaker, originalMethodCall);
        decoratedSupplier = Retry.decorateCheckedSupplier(retry, decoratedSupplier);

        try {
            // 데코레이팅한 로직 실행
            return decoratedSupplier.get();
        } catch (Throwable throwable) {
            // 리트라이 다 실패할시 이 블록이 동작 !
            Throwable originalCause = unwrapAsyncExceptions(throwable);
            log.warn("Resilience4j모듈이 다음 메소드때문에 실패 '{}'. 이유: {}",
                    pjp.getSignature().getName(), originalCause.toString());
            return handleFallback(pjp, feignApiCall.fallbackMethod(), originalCause);
        }
    }

    private CircuitBreaker createDynamicCircuitBreaker(FeignApiCall annotation, String configName) {
        CircuitBreakerConfig baseConfig = circuitBreakerRegistry.getConfiguration(configName).orElse(CircuitBreakerConfig.ofDefaults());
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.from(baseConfig);
        if (annotation.failureRateThreshold() != -1.0f) {
            builder.failureRateThreshold(annotation.failureRateThreshold());
        }
        if (annotation.waitDurationInOpenState() != -1) {
            builder.waitDurationInOpenState(Duration.ofSeconds(annotation.waitDurationInOpenState()));
        }
        if (annotation.permittedNumberOfCallsInHalfOpenState() != -1){
            builder.permittedNumberOfCallsInHalfOpenState(annotation.permittedNumberOfCallsInHalfOpenState());
        }
        if (annotation.slidingWindowSize() != -1) {
            builder.slidingWindowSize(annotation.slidingWindowSize());
        }
        return CircuitBreaker.of(configName + "-dynamic-" + System.nanoTime(), builder.build());
    }

    private Retry createDynamicRetry(FeignApiCall annotation, String configName) {
        RetryConfig baseConfig = retryRegistry.getConfiguration(configName).orElse(RetryConfig.ofDefaults());
        RetryConfig.Builder<Object> builder = RetryConfig.from(baseConfig);
        if (annotation.maxAttempts() != -1) {
            builder.maxAttempts(annotation.maxAttempts());
        }
        if (annotation.intervalFunction() != -1){
            builder.intervalFunction(IntervalFunction.ofExponentialBackoff(annotation.intervalFunction(), 2));
        }
        if (annotation.retryExceptions().length > 0) {
            builder.retryExceptions(annotation.retryExceptions());
        }

        return Retry.of(configName + "-dynamic-" + System.nanoTime(), builder.build());
    }

    private Object handleFallback(ProceedingJoinPoint pjp, String fallbackMethodName, Throwable throwable) throws Throwable {
        // 1. "DEFAULT" 라고 명시적으로 요청한 경우, 기본 폴백 실행
        if ("DEFAULT".equals(fallbackMethodName)) {
            log.info("default fallback 실행");
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
        log.warn("폴백메소드가 없거나 지정한 커스텀 폴백을 찾지 못했습니다. Re-throwing original exception.");
        throw throwable;
    }


    /**
     * FeignClient의 특성을 고려하여 인터페이스와 구현 클래스 모두를 검색합니다.
     * 1. 원본 메서드와 시그니처가 같은 폴백 메서드를 먼저 찾음
     * 2. 없다면, 원본 메서드 시그니처 + Throwable 파라미터를 갖는 폴백 메서드를 찾음
     */
    private Method findFallbackMethod(ProceedingJoinPoint pjp, String fallbackMethodName, Throwable cause) {
        Method originalMethod = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?>[] originalParamTypes = originalMethod.getParameterTypes();
        Class<?> targetClass = pjp.getTarget().getClass();

        // 검색할 파라미터 타입 배열 준비
        Class<?>[] paramsForExactMatch = originalParamTypes;
        Class<?>[] paramsWithThrowable = Arrays.copyOf(originalParamTypes, originalParamTypes.length + 1);
        paramsWithThrowable[paramsWithThrowable.length - 1] = Throwable.class;

        // 1. 인터페이스 먼저 검색 (FeignClient의 default 메서드 대응)
        for (Class<?> iface : targetClass.getInterfaces()) {
            try {
                return iface.getMethod(fallbackMethodName, paramsForExactMatch);
            } catch (NoSuchMethodException e) {
                try {
                    return iface.getMethod(fallbackMethodName, paramsWithThrowable);
                } catch (NoSuchMethodException ex) {
                    // 이 인터페이스에는 없으므로 다음 인터페이스로 넘어감
                }
            }
        }

        // 2. 구현 클래스에서 검색 (별도의 @Component로 Fallback을 구현한 경우)
        try {
            return targetClass.getMethod(fallbackMethodName, paramsForExactMatch);
        } catch (NoSuchMethodException e) {
            try {
                return targetClass.getMethod(fallbackMethodName, paramsWithThrowable);
            } catch (NoSuchMethodException ex) {
                log.warn("Fallback method '{}' not found with original or extended signature in class {} or its interfaces",
                        fallbackMethodName, targetClass.getName());
                return null;
            }
        }
    }

    private Throwable unwrapAsyncExceptions(Throwable throwable) {
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            if (throwable.getCause() != null) {
                return throwable.getCause();
            }
        }
        return throwable;
    }
}