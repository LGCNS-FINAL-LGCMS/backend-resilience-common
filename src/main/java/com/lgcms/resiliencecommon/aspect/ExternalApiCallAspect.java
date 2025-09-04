package com.lgcms.resiliencecommon.aspect;

import com.lgcms.resiliencecommon.annotation.ExternalApiCall;
import com.lgcms.resiliencecommon.fallback.DefaultResilienceFallback;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;


@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalApiCallAspect {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final DefaultResilienceFallback defaultResilienceFallback;
    private final BulkheadRegistry bulkheadRegistry;


    @Around("@annotation(com.lgcms.resiliencecommon.annotation.ExternalApiCall)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        ExternalApiCall externalApiCall = method.getAnnotation(ExternalApiCall.class);

        // 캐시 키 생성
        String cacheKey = generateCacheKey(signature.toLongString(), externalApiCall);

        // Registry에 cacheKey로 된 인스턴스가 있으면 반환, 없으면 생성 후 등록하고 반환합니다.
        // 이 과정에서 'onEntryAdded' 이벤트가 발생합니다.
        Supplier<CircuitBreakerConfig> circuitBreakerConfigSupplier = () -> createDynamicCircuitBreakerConfig(externalApiCall);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(cacheKey, circuitBreakerConfigSupplier);

        Supplier<RetryConfig> retryConfigSupplier = () -> createDynamicRetryConfig(externalApiCall);
        Retry retry = retryRegistry.retry(cacheKey, retryConfigSupplier);

        Supplier<BulkheadConfig> bulkheadConfigSupplier = () -> createDynamicBulkheadConfig(externalApiCall);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(cacheKey, bulkheadConfigSupplier);

        String configName = externalApiCall.name();

        // 2. 원본 메소드 호출 (타임리미터 쓸거면 비동기로 바꿔서 Supplier를 쓴다. )
        CheckedSupplier<Object> originMethodCall = pjp::proceed;

        // Resilience4j 패턴들을 순서대로 데코레이팅 (실행순서: Retry -> 서킷브레이커 -> bulkhead -> 원본메서드)
        CheckedSupplier<Object> bulkheadedCall = Bulkhead.decorateCheckedSupplier(bulkhead, originMethodCall);
        CheckedSupplier<Object> circuitBrokenCall = CircuitBreaker.decorateCheckedSupplier(circuitBreaker, bulkheadedCall);
        CheckedSupplier<Object> retriedCall = Retry.decorateCheckedSupplier(retry, circuitBrokenCall);

        // 최종 데코레이팅된 메서드를 실행하고, 예외 발생 시 폴백 처리
        try {
            return retriedCall.get();
        } catch (Throwable throwable) {
            Throwable originalCause = unwrapAsyncExceptions(throwable);
            log.warn("Resilience4j모듈이 다음 메소드때문에 실패 '{}'. 이유: {}, 메세지 : {}",
                    pjp.getSignature().getName(), originalCause.toString(), throwable.getMessage());
            return handleFallback(pjp, externalApiCall.fallbackMethod(), originalCause);
        }
    }

    private String generateCacheKey(String methodSignature, ExternalApiCall annotation) {
        // 어노테이션의 모든 속성값을 조합하여 고유성을 보장하고 객체 재사용
        return methodSignature + "#" + annotation.name() +
                "#" + annotation.failureRateThreshold() +
                "#" + annotation.waitDurationInOpenState() +
                "#" + annotation.permittedNumberOfCallsInHalfOpenState() +
                "#" + annotation.slidingWindowSize() +
                "#" + annotation.maxAttempts() +
                "#" + annotation.intervalFunction() +
                "#" + Arrays.toString(annotation.retryExceptions()) +
                "#" + annotation.maxConcurrentCalls() +
                "#" + annotation.maxWaitDuration();
    }

    private RetryConfig createDynamicRetryConfig(ExternalApiCall annotation) {
        RetryConfig baseConfig = retryRegistry.getConfiguration(annotation.name()).orElse(RetryConfig.ofDefaults());
        RetryConfig.Builder<Object> builder = RetryConfig.from(baseConfig);
        if (annotation.maxAttempts() != -1) builder.maxAttempts(annotation.maxAttempts());
        if (annotation.intervalFunction() != -1) builder.intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(annotation.intervalFunction()), 2));
        if (annotation.retryExceptions().length > 0) builder.retryExceptions(annotation.retryExceptions());
        return builder.build();
    }

    private CircuitBreakerConfig createDynamicCircuitBreakerConfig(ExternalApiCall annotation) {
        CircuitBreakerConfig baseConfig = circuitBreakerRegistry.getConfiguration(annotation.name()).orElse(CircuitBreakerConfig.ofDefaults());
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.from(baseConfig);
        if (annotation.failureRateThreshold() != -1.0f) builder.failureRateThreshold(annotation.failureRateThreshold());
        if (annotation.waitDurationInOpenState() != -1) builder.waitDurationInOpenState(Duration.ofMillis(annotation.waitDurationInOpenState()));
        if (annotation.permittedNumberOfCallsInHalfOpenState() != -1) builder.permittedNumberOfCallsInHalfOpenState(annotation.permittedNumberOfCallsInHalfOpenState());
        if (annotation.slidingWindowSize() != -1) builder.slidingWindowSize(annotation.slidingWindowSize());
        return builder.build();
    }

    private BulkheadConfig createDynamicBulkheadConfig(ExternalApiCall annotation){
        BulkheadConfig baseConfig = bulkheadRegistry.getConfiguration(annotation.name()).orElse(BulkheadConfig.ofDefaults());
        BulkheadConfig.Builder builder = BulkheadConfig.from(baseConfig);
        if (annotation.maxConcurrentCalls() != -1) builder.maxConcurrentCalls(annotation.maxConcurrentCalls());
        if (annotation.maxWaitDuration() != -1L) builder.maxWaitDuration(Duration.ofMillis(annotation.maxWaitDuration()));
        return builder.build();
    }
//    //  Retry 어노테이션 동적 생성
//    private Retry createDynamicRetry(String cacheKey, ExternalApiCall annotation) {
//        RetryConfig baseConfig = retryRegistry.getConfiguration(annotation.name()).orElse(RetryConfig.ofDefaults());
//        RetryConfig.Builder<Object> builder = RetryConfig.from(baseConfig);
//
//        if (annotation.maxAttempts() != -1) builder.maxAttempts(annotation.maxAttempts());
//        if (annotation.intervalFunction() != -1) builder.intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(annotation.intervalFunction()), 2));
//        if (annotation.retryExceptions().length > 0) builder.retryExceptions(annotation.retryExceptions());
//
//        return Retry.of(cacheKey, builder.build());
//    }
//
//    private CircuitBreaker createDynamicCircuitBreaker(String cacheKey, ExternalApiCall annotation) {
//        CircuitBreakerConfig baseConfig = circuitBreakerRegistry.getConfiguration(annotation.name()).orElse(CircuitBreakerConfig.ofDefaults());
//        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.from(baseConfig);
//
//        if (annotation.failureRateThreshold() != -1.0f) builder.failureRateThreshold(annotation.failureRateThreshold());
//        if (annotation.waitDurationInOpenState() != -1) builder.waitDurationInOpenState(Duration.ofMillis(annotation.waitDurationInOpenState()));
//        if (annotation.permittedNumberOfCallsInHalfOpenState() != -1) builder.permittedNumberOfCallsInHalfOpenState(annotation.permittedNumberOfCallsInHalfOpenState());
//        if (annotation.slidingWindowSize() != -1) builder.slidingWindowSize(annotation.slidingWindowSize());
//
//        return CircuitBreaker.of(cacheKey, builder.build());
//    }
//
//    private Bulkhead createDynamicBulkhead(String cacheKey, ExternalApiCall annotation){
//        BulkheadConfig baseConfig = bulkheadRegistry.getConfiguration(annotation.name()).orElse(BulkheadConfig.ofDefaults());
//        BulkheadConfig.Builder builder = BulkheadConfig.from(baseConfig);
//        if (annotation.maxConcurrentCalls() != -1) builder.maxConcurrentCalls(annotation.maxConcurrentCalls());
//        if (annotation.maxWaitDuration() != -1L) builder.maxWaitDuration(Duration.ofMillis(annotation.maxWaitDuration()));
//
//        return Bulkhead.of(cacheKey, builder.build());
//    }

    private Object handleFallback(ProceedingJoinPoint pjp, String fallbackMethodName, Throwable throwable) throws Throwable {
        if ("DEFAULT".equals(fallbackMethodName)) {
            return defaultResilienceFallback.execute2(throwable);
        }

        if (StringUtils.hasText(fallbackMethodName)) {
            Method fallbackMethod = findFallbackMethod(pjp, fallbackMethodName);
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
        throw throwable;
    }
    private Method findFallbackMethod(ProceedingJoinPoint pjp, String fallbackMethodName) {
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
    private Throwable unwrapAsyncExceptions(Throwable throwable) {
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            if (throwable.getCause() != null) {
                return throwable.getCause();
            }
        }
        return throwable;
    }
}
