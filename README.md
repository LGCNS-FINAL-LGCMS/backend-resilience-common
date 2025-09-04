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
```

을 추가하세요

---

### 코드에서 사용하기

> 1. application파일에 패키지를 스캔하도록 명시해야한다.
> ``` 
> @SpringBootApplication(scanBasePackages = {"com.lgcms.backendguidebot", "com.lgcms.resiliencecommon"})
> public class BackendGuideBotApplication {
>   public static void main(String[] args) {
>       }
> }
> ```

[//]: # (> 2. BaseException이 있을 경우 모듈이 그것도 인지하도록 해야한다.)

[//]: # (> yaml파일에 작성합니다.)

[//]: # (> ```)

[//]: # (> string:)

[//]: # (>   resilience4j:)

[//]: # (>    circuitbreaker:)

[//]: # (>      configs:)

[//]: # (>        default:)

[//]: # (>          record-exceptions:)

[//]: # (>            - com.lgcms.backendguidebot.common.dto.exception.BaseException)

[//]: # (>           # 각자 서버에 맞게 바꾸시면 됩니다.)

[//]: # (> ```)
> 3. 실제 코드에서 사용할 땐 메소드 위에 @(어노테이션)을 붙이면 됩니다.
>   
> ```
> @ExternalApiCall(name = "default2", fallbackMethod = "fallbackt")
> public ChatResponse getResponse(String userQuery) {
>    throw new BaseException(QnaError.QNA_SERVER_ERROR);
>   }
> 
> public ChatResponse fallbackt(String userQuery) {
>        log.info("fallbackt userQuery: {}", userQuery);
>        ChatResponse chatResponse = new ChatResponse();
>        chatResponse.setAnswer("오류가 생겼습니다. 잠시 후 다시 시도해 주세요.");
>        return chatResponse;
>    }
>  ```
> 메소드 바로 아래쪽에 원하는 콜백을 작성하면 됩니다. 작성하지 않을 경우 콜백이 동작하지 않고 기존 서버의 오류 메세지가 나갑니다.
> 
