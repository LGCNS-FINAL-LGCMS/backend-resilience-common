resilience4j를 사용하여 서비스의 안정성과 복원력을 강화합니다.

그것을 위한 공통모듈입니다.

**사용법**

```
./gradlew publishToMavenLocal
```

이 코드를 터미널에서 실행해 로컬모듈로 쓸 수 있습니다.

사용하고자하는 서버의 build.gradle에다가

```
repositories {
    mavenLocal()
}
dependencies {
    implementation 'com.lgcms:backend-resilience-common:0.1'
}
```

을 추가하세요

---

## 코드에서 사용하기

> ### 1. application파일에 패키지를 스캔하도록 명시해야한다.
> ``` 
> @SpringBootApplication(scanBasePackages = {"com.lgcms.backendguidebot", "com.lgcms.resiliencecommon"})
> public class BackendGuideBotApplication {
>   public static void main(String[] args) {
>       }
> }
> ```

>  ### 2. BaseException이 있을 경우 모듈이 그것도 인지하도록 해야한다.
> yaml파일에 작성할수도 있지만 어노테이션 사용시에
>  ```
>   retryExceptions = {BaseException.class}
> ```
> 속성을 추가해도 됩니다.

> ### 3. 실제 코드에서 사용할 땐 메소드 위에 @(어노테이션)을 붙이면 됩니다.
>
> ```
> @ExternalApiCall(
>        name = "default2",
>        fallbackMethod = "fallbackt",
>        retryExceptions = {BaseException.class} 
>   )
> public ChatResponse getResponse(String userQuery) {
>    throw new BaseException(QnaError.QNA_SERVER_ERROR);
> }
> 
> public ChatResponse fallbackt(String userQuery) {
>        log.info("fallbackt userQuery: {}", userQuery);
>        ChatResponse chatResponse = new ChatResponse();
>        chatResponse.setAnswer("오류가 생겼습니다. 잠시 후 다시 시도해 주세요.");
>        return chatResponse;
> }
>  ```
> 메소드 바로 아래쪽에 원하는 콜백을 작성하면 됩니다. 작성하지 않을 경우 콜백이 동작하지 않고 기존 서버의 오류 메세지가 나갑니다.
>

* 콜백 사용시 200상태로 응답이 나가므로 따로 200이 아닌 에러를 throw해줘야 프론트에서 에러로 캐치합니다.
