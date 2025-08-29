resilience4j를 사용하여 서비스의 안정성과 복원력을 강화합니다.

그것을 위한 공통모듈입니다.

**사용법**

./gradlew publishToMavenLocal

이 코드를 터미널에서 실행해 로컬모듈로 쓸 수 있습니다.

사용하고자하는 서비스의 build.gradle에다가 

repositories {
mavenLocal()
}

을 추가하세요