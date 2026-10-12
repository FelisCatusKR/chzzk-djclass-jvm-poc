# chzzk-djclass-chat JVM 이전 실험 리포트: Spring Boot · Quarkus (2026-10-12)

[chzzk-djclass-chat](https://github.com/FelisCatusKR/chzzk-djclass-chat)(Go + SQLite)의 핵심 경로를
**Spring Boot**와 **Quarkus**로 각각 옮겼을 때 코드가 어떻게 구성되는지, Go에서 해 둔 튜닝이 각 생태계에서
어떻게 대응되는지, 그리고 이미지 크기·메모리·기동 시간이 얼마나 차이 나는지를 **실제로 측정**한 기록입니다.
같은 JVM 위에서 프레임워크만 바꿔 "차이가 언어에서 오는가, 프레임워크에서 오는가"도 함께 봅니다.

- 대상: Spring Boot 4.1.1 / Quarkus 3.40.1, Java 25 (Temurin 25.0.4, GraalVM CE 25.0.2, Mandrel 25.0.4.1)
- 비교 기준: 프로덕션 Go 이미지 `ghcr.io/feliscatuskr/chzzk-djclass-chat:sha-d04d885`
- 측정 호스트: Raspberry Pi 4 (4코어, RAM 6GB, aarch64) — 실제 프로덕션과 같은 종류의 머신
- 원본 수치: [`results/2026-10-12-rpi4-with-quarkus.txt`](results/2026-10-12-rpi4-with-quarkus.txt)
  (Go·Spring·Quarkus를 연달아 다시 잰 값, 아래 표의 기준), 첫 Spring 측정은
  [`results/2026-10-12-rpi4.txt`](results/2026-10-12-rpi4.txt)

## 1. 요약

| 변형 | 이미지 | 기동 | 유휴 RSS | 부하 중 최대 RSS | CPU 시간 |
| --- | ---: | ---: | ---: | ---: | ---: |
| **Go (현재 프로덕션)** | **17.9MB** | **1.5초** | **15MB** | **34MB** | **14.0초** |
| Spring, 기본 JRE | 423MB | 23.9초 | 207MB | 276MB | 73.0초 |
| Spring, jlink + distroless | 151MB | 23.1초 | 227MB | 296MB | 69.9초 |
| Spring, + AOT 캐시 | 221MB | 11.9초 | 221MB | 311MB | 64.8초 |
| Spring, + AOT 캐시 + JVM 튜닝 | 221MB | 9.0초 | 170MB | 210MB | 66.2초 |
| Spring, GraalVM 네이티브 | 155MB | 2.3초 | 141MB | 170MB | 26.3초 |
| Spring, 네이티브 + `-Xmx64m` | 155MB | 1.8초 | 108MB | 129MB | 28.9초 |
| Quarkus, 기본 JRE | 434MB | 7.8초 | 139MB | 188MB | 61.5초 |
| Quarkus, jlink + distroless | 161MB | 8.3초 | 150MB | 183MB | 61.2초 |
| Quarkus, + AOT 캐시 | 212MB | 5.4초 | 170MB | 240MB | 58.2초 |
| Quarkus, + AOT 캐시 + JVM 튜닝 | 212MB | 5.4초 | 130MB | 163MB | 56.8초 |
| Quarkus, 네이티브 (Mandrel) | 105MB | 1.0초 | 58MB | 106MB | 23.0초 |
| **Quarkus, 네이티브 + `-Xmx64m`** | **105MB** | **1.0초** | **58MB** | **71MB** | **25.4초** |

부하 조건: 20채널 × 위젯 5개 = SSE 100개, 채널당 초당 5개(전체 초당 100개) 채팅, 90초.
기동 시간은 컨테이너 시작부터 `/healthz` 200까지이며 podman 오버헤드 약 1초가 포함되어 있습니다
(그래서 1초 안팎의 차이는 잡음 수준입니다). 모든 변형에서 위젯 하나당 받은 이벤트 수는 356~360개로
같았습니다(기능적으로 동등). JVM 튜닝 플래그는 `-XX:+UseSerialGC -Xmx64m -Xss256k
-XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=96m -XX:CICompilerCount=2`입니다.

**결론**

- **같은 JVM이어도 프레임워크에 따라 차이가 큽니다.** 같은 조건에서 Quarkus는 Spring보다 기동이 1.7~3배
  빠르고, 부하 중 최대 메모리는 20~45% 적었습니다. 네이티브에서는 유휴 메모리가 절반 이하였습니다(58MB vs 141MB).
  Quarkus가 클래스패스 스캔과 빈 연결을 빌드 시점으로 옮긴 효과입니다.
- **그래도 Go와의 격차는 남습니다.** 가장 가벼운 Quarkus 네이티브 + `-Xmx64m`도 부하 중 최대 메모리 2.1배,
  이미지 5.9배, CPU 시간 1.6~1.8배입니다. JVM 모드는 메모리 5~9배, CPU 시간 4~5배입니다.
- **기동 시간은 Quarkus 네이티브가 Go와 같은 수준까지 내려옵니다(약 1초).** 이 서비스처럼 "머지할 때마다
  재시작되는" 구조에서 가장 중요한 지표에서는 격차가 사실상 사라집니다.

## 2. Spring: 코드 구성 (Go 패키지 → Spring)

2~7절의 수치는 첫 Spring 측정([`results/2026-10-12-rpi4.txt`](results/2026-10-12-rpi4.txt)) 기준입니다. 다시 잰 값과의 차이는 몇 % 이내입니다.

```
spring/src/main/java/kr/felis/djclass/
  store/      StoreConfig (풀 2개), Store (writeTx)
  realtime/   Hub (Go와 1:1), ChatMessage
  resolver/   Resolver (Caffeine), CaffeineHints (네이티브 전용)
  web/        WidgetController (SSE), SecurityConfig, PageController, DevController
```

| Go | Spring 프로토타입 | 비고 |
| --- | --- | --- |
| `store`: 읽기 풀(`query_only`) + 쓰기 커넥션 1개(`_txlock=immediate`, WAL, `synchronous=NORMAL`) | HikariCP 두 개. 쓰기는 `maximumPoolSize=1` + `SQLiteConfig.TransactionMode.IMMEDIATE`, 읽기는 `connectionInitSql=PRAGMA query_only=1` | 거의 그대로 대응됩니다. Hikari의 `connectionTimeout`이 "쓰기 대기열 대기 시간" 역할을 합니다 |
| `store.WriteTx` + `ErrNestedWrite` | `TransactionTemplate` + `isActualTransactionActive()`이면 `NestedWriteException` | `@Transactional` 대신 명시적으로 써서 "트랜잭션 안에서 네트워크 금지" 규칙이 호출부에 보이게 했습니다 |
| goose + sqlc | Flyway + `JdbcClient` | 타입 생성까지 원하면 jOOQ가 sqlc에 가장 가깝습니다. scs 세션 테이블은 Spring Session JDBC 스키마로 바꿨습니다 |
| 고루틴 (요청당, 채널당 ingest/flush) | 가상 스레드 (`spring.threads.virtual.enabled=true`, `Thread.ofVirtual()`) | Java 21+ 덕분에 Go 구조를 그대로 옮길 수 있었습니다 |
| `context` 취소 | `Thread.interrupt()` | |
| 버퍼 있는 채널 + `select default` | `ArrayBlockingQueue(64).offer()` | 막힌 위젯이 다른 위젯을 막지 않는 동작이 같습니다 |
| `close(sub.c)` | 큐에 `CLOSED` 센티널 | |
| `time.AfterFunc` (30초 teardown) | `ScheduledExecutorService.schedule` | 타이머가 돈 뒤 재확인하는 로직도 같습니다 |
| `Hub.Close`: 워커 정지 → 구독 닫기 | `SmartLifecycle`, `phase=Integer.MAX_VALUE` | Tomcat 그레이스풀 셧다운보다 먼저 멈춰야 SSE 요청이 셧다운을 붙잡지 않습니다 |
| SSE 핸들러 (블로킹 `select` 루프) | 가상 스레드 위의 블로킹 `poll(15s)` 루프 | `SseEmitter`나 비동기 디스패치가 필요 없습니다 |
| 쓰기마다 10초 데드라인 (`ResponseController.SetWriteDeadline`) | `server.tomcat.connection-timeout=10s` | Tomcat은 이 값을 블로킹 쓰기 타임아웃으로도 씁니다. 연결 단위 근사치라 Go처럼 쓰기마다 다시 거는 정밀함은 없습니다 |
| `resolver` TTL (연동 5분, 클래스 없음 15초, 미연동 10초, 읽어도 연장 없음) | Caffeine `Expiry` (`expireAfterRead`는 남은 시간 그대로 반환) | 결과별로 TTL이 다른 동작까지 그대로 구현됩니다 |
| `http.CrossOriginProtection` + 보안 헤더 | Spring Security `headers()` + 직접 쓴 `CrossOriginProtection` 필터 | Spring 기본값인 CSRF 토큰 방식은 껐습니다 |
| `ratelimit`, `schedule` (18:00 UTC) | Bucket4j, `@Scheduled(cron = "0 0 18 * * *", zone = "UTC")` | 프로토타입에는 넣지 않았습니다 |
| `chzzk/eio3` (손으로 짠 EIO3 클라이언트) | `socket.io-client-java` 1.x (Socket.IO v2 지원) | 프로토타입에는 넣지 않았습니다. ingest는 `/dev/chat` 주입만 받습니다 |

Java 코드는 약 840줄입니다. 그중 `Hub.java`가 345줄로, Go `hub.go`(356줄)와 비슷한 분량입니다.

## 3. Spring: 이미지 크기

- **기본 JRE (423MB):** Ubuntu 기반 Temurin JRE(약 190MB)와 앱(44MB), 그리고 OS 레이어입니다.
- **jlink + distroless (151MB):** `jdeps`로 필요한 모듈만 고른 런타임입니다. 그래도 Spring이 `java.beans`를
  쓰기 때문에 `java.desktop`(AWT)이 딸려옵니다.
- **`sqlite-jdbc` 하나가 14MB입니다.** 모든 OS·아키텍처의 네이티브 라이브러리를 jar에 담고 있기 때문입니다.
  linux-aarch64/x86_64만 남기면 더 줄일 수 있습니다.
- **AOT 캐시(70MB)** 만큼 `jlink-aot` 이미지가 커집니다.
- **네이티브(155MB):** 바이너리가 정적 링크된 Go와 달리 distroless/cc(glibc) 위에 올라가고,
  Spring 메타데이터가 바이너리 안에 들어갑니다.

## 4. Spring: 메모리는 어디에 쓰이는가

`aot-tuned` 플래그 + 같은 부하 30초, Native Memory Tracking(committed):

| 영역 | MB |
| --- | ---: |
| Java Heap (`-Xmx64m`) | 64.0 |
| Shared class space (AOT 캐시 매핑) | 62.1 |
| Code (JIT 컴파일 결과) | 22.2 |
| Metaspace | 9.3 |
| 그 외 (Symbol, Thread, Arena, NMT 등) | 약 12 |
| **합계** | **약 170** |

- **힙은 절반도 안 됩니다.** 힙을 64MB로 묶어도 RSS가 176MB였습니다. JVM 튜닝으로 줄일 수 있는 폭은 20~30%입니다.
- **AOT 캐시는 기동 시간을 메모리로 산 것입니다.** 매핑된 클래스 데이터가 RSS에 그대로 잡힙니다.
- **기본 설정의 JVM은 호스트 RAM의 1/4(약 1.6GB)을 최대 힙으로 잡고 G1을 씁니다.** 부하가 끝난 뒤에도
  RSS가 내려가지 않는(`after` = `peak`) 이유입니다. 같은 호스트에서 다른 서비스와 함께 쓴다면
  `-Xmx`와 GC 선택이 사실상 필수입니다.
- **가상 스레드 100개(SSE 100개)의 스레드 메모리는 약 2MB였습니다.** 연결 수는 메모리 문제의 원인이 아닙니다.

## 5. Spring: 기동 시간

| 방법 | 기동 | 대가 |
| --- | ---: | --- |
| 기본 | 24~26초 | — |
| JDK 25 AOT 캐시 (JEP 483/514/515) | 11.6초 | 빌드 때 학습 실행 1회, 이미지 +70MB |
| AOT 캐시 + SerialGC 등 튜닝 | 9.0초 | 처리량을 일부 포기 |
| GraalVM 네이티브 | 1.9초 | 빌드 23분 + RAM 5GB, 아래 함정들 |

가성비는 AOT 캐시가 가장 좋았습니다. Dockerfile에 스테이지 하나를 추가하는 것으로 끝납니다.

## 6. Spring: 직접 겪은 GraalVM 네이티브 이미지 함정

세 가지 모두 **JVM 모드에서는 재현되지 않았고**, 고칠 때마다 23분씩 다시 빌드해야 했습니다.

1. **Caffeine (`174b2e2`):** 시작하자마자 `ClassNotFoundException: SSMSA`로 실패했습니다. Caffeine은 생성된
   캐시·노드 클래스를 실행 중에 이름으로 고르기 때문에 빌드 시점에 등록해야 합니다(`RuntimeHintsRegistrar`).
2. **`@ConditionalOnProperty` (`216b7ac`):** Spring AOT는 조건을 **빌드할 때** 평가해 굳힙니다. 빌드 시점에
   `DEV`가 없었으니 `DevController`가 이미지에서 빠져 `/dev/chat`이 전부 404였습니다. 런타임 검사로 바꿨습니다.
3. **Jackson의 record 직렬화 (`f76e61c`):** 가장 위험했습니다.
   - `ClassRow` record를 직렬화하려면 리플렉션 등록이 필요한데, GraalVM은 이걸 `UnsupportedFeatureError`,
     즉 `Error`로 던집니다.
   - `catch (RuntimeException)`이 잡지 못해 **flush 스레드가 죽고, 그 채널은 스택 트레이스 한 번 외에는
     조용히 멈췄습니다.** 배지가 없는 발신자의 메시지만 처음 잠깐 전달됐습니다.
   - 그래서 고치기 전 측정에서는 위젯이 이벤트를 1/4만 받았습니다. 그 수치는 결과 파일에 "무효"로 남겨 두었습니다.
   - `@RegisterReflectionForBinding`으로 원인을 고치고, VM 오류가 아닌 throwable은 로그만 남기고
     flush 루프를 계속 돌도록 방어했습니다.

**교훈:** 네이티브 이미지를 쓴다면 CI에서 **네이티브 바이너리로** 통합 테스트(SSE로 실제 배지 메시지 받기)를
돌리는 것이 사실상 필수입니다.

## 7. Spring: 얻는 것과 잃는 것 (측정 후)

**얻는 것**

- 레이트리밋, 캐시, 스케줄러, 보안 헤더, 세션을 검증된 표준 부품으로 해결합니다. 특히 Caffeine `Expiry`는 `ttlcache`를 그대로 대체합니다.
- 가상 스레드 덕분에 Go의 동시성 구조(요청당 블로킹 루프, 채널당 워커)를 거의 그대로 옮길 수 있습니다.
- Actuator, NMT, JFR 같은 관측 도구를 쓸 수 있습니다(이번 메모리 분해도 NMT로 했습니다).

**잃는 것**

- 메모리 4~9배, 이미지 8~24배, CPU 시간 1.7~4.5배(라즈베리 파이 기준).
- 기동 시간: 재시작할 때마다 위젯이 끊기는 시간이 늘어납니다.
- 빌드 시간: 이미지 빌드가 Go 대비 수 분, 네이티브는 23분입니다.
- 명시성: 셧다운 순서(`SmartLifecycle` phase), 트랜잭션 경계, 조건부 빈이 프레임워크 규칙 뒤로 숨습니다.
  네이티브 이미지에서는 이 숨은 규칙이 빌드 시점과 실행 시점으로 또 갈립니다.

## 8. Quarkus

### 8.1 Quarkus란

Red Hat이 만든 Java 프레임워크로, 컨테이너와 GraalVM 네이티브 이미지를 전제로 설계됐습니다.
Spring이 기동할 때 하는 클래스패스 스캔, 설정 해석, 빈 연결을 **빌드 시점**에 끝내고 결과를 바이트코드로
생성해 둡니다(`quarkus-app/quarkus/generated-bytecode.jar`). 각 확장(extension)은 네이티브 이미지용
메타데이터를 함께 제공합니다. HTTP는 서블릿/Tomcat이 아니라 Vert.x(Netty) 위에서 돌고,
`@RunOnVirtualThread`로 가상 스레드를 쓸 수 있습니다.

### 8.2 코드 구성 (Spring 버전과 다른 부분만)

| 관심사 | Spring | Quarkus |
| --- | --- | --- |
| 쓰기 커넥션 1개 + `query_only` 읽기 풀 | HikariCP 두 개를 Java로 구성 | Agroal 기본 데이터소스(`max-size=1`) + 이름 있는 데이터소스 `"read"`. **설정 파일만으로** 구성되고, SQLite pragma는 JDBC URL 파라미터로 넣었습니다 |
| `writeTx` | `TransactionTemplate` | JTA를 끄고(`transactions=disabled`) 순수 JDBC로 구현. 중첩 감지는 `ThreadLocal` |
| SSE | Tomcat 위의 블로킹 루프, 쓰기 타임아웃은 `connection-timeout`으로 근사 | `SseEventSink` + `@RunOnVirtualThread`. **이벤트를 보낼 때마다 10초 타임아웃**(`send().get(10s)`)을 걸 수 있어 Go의 쓰기마다 데드라인에 더 가깝습니다 |
| 셧다운 순서 | `SmartLifecycle` phase | `@Observes ShutdownEvent` |
| 보안 헤더 | Spring Security `headers()` | `quarkus.http.header.*` 설정만으로 해결 |
| Cross-Origin 보호 | Security 필터 체인 | `@ServerRequestFilter` 메서드 하나 |
| Caffeine | 의존성만 추가 (네이티브에서 직접 힌트 필요) | `quarkus-caffeine` (네이티브 지원 포함) |
| 템플릿 | Thymeleaf | Qute (빌드 시점에 템플릿 검사) |
| 서버 세션 저장소 | Spring Session JDBC | **표준 대응 없음.** Quarkus는 무상태 지향이라 실제 이전 시 직접 만들어야 합니다 |

Java 코드는 약 730줄로 Spring 버전(840줄)보다 조금 짧습니다. 차이의 대부분은 데이터소스와 보안 헤더를
코드 대신 설정으로 처리한 덕분입니다. `Hub.java`는 Spring 버전에서 프레임워크 접점만 바꿔 가져왔습니다.

### 8.3 메모리 분해 (Native Memory Tracking, 튜닝 플래그 + 같은 부하 30초, committed MB)

| 영역 | Spring | Quarkus |
| --- | ---: | ---: |
| Java Heap (`-Xmx64m`) | 64.0 | 64.0 |
| Shared class space (AOT 캐시) | 62.1 | 46.9 |
| Code (JIT) | 22.2 | 14.2 |
| Metaspace | 9.3 | 7.6 |
| 그 외 | 약 12 | 약 13 |
| **합계** | **약 170** | **약 146** |

힙이 같을 때 차이는 **로드되는 클래스와 실행되는 코드의 양**에서 납니다. 빌드 시점에 처리를 끝낸 Quarkus는
런타임에 로드하는 클래스가 적어 AOT 캐시, JIT 코드, 메타스페이스가 모두 작습니다.

### 8.4 직접 겪은 함정

1. **Maven 래퍼의 SHA-256 검증 실패:** Temurin 이미지에는 curl과 unzip이 없어서 `mvnw`가 자체 Java
   다운로더로 대체되는데, 그 경로에서 검증이 실패했습니다. 배포 파일 자체는 공식 SHA-512와 일치했습니다.
   검증을 끄지 않고 공식 Maven 이미지로 빌드했습니다.
2. **SSE 응답에 `@ResponseHeader`가 적용되지 않음:** `Cache-Control`과 `X-Accel-Buffering`을 첫 이벤트를
   보내기 전에 Vert.x 응답에 직접 넣었습니다. 또 `OutboundSseEvent`에 `mediaType`을 지정하면 이벤트마다
   비표준 `content-type:` 줄이 붙습니다.
3. **jlink + Quarkus 구조:**
   - `quarkus-run.jar`는 런처일 뿐이라 `jdeps`가 아무 의존성도 찾지 못합니다.
     모든 jar를 `--module-path`와 함께 분석해야 합니다.
   - `jdk.zipfs`가 빠지면 기동이 `Unable to find provider supporting jar scheme`로 실패합니다. Quarkus는
     jar를 `jar:` 파일시스템으로 여는데, 이 provider는 `ServiceLoader`로 로드되어 `jdeps`에 보이지 않습니다.
   - 이 상태에서는 **AOT 학습 실행도 기동 실패를 학습해** 18MB짜리 쓸모없는 캐시를 만들고 빌드는 성공합니다.
     학습 실행의 성공 여부를 빌드에서 확인해야 합니다.
4. **SSE 포맷 차이:** Quarkus는 `event:chat`(콜론 뒤 공백 없음)을 씁니다. 표준상 `event: chat`과 같지만,
   loadgen이 공백 있는 형태만 세서 처음엔 이벤트가 0개로 보였습니다. 파서를 표준대로 고쳤습니다.
   클라이언트가 SSE를 직접 파싱한다면 같은 문제를 겪을 수 있습니다.

### 8.5 네이티브 이미지: Spring과의 차이

- **빌드:** Spring 약 23분 / 최대 RSS 약 5GB → Quarkus(Mandrel) 15분 46초 / 3.84GB (같은 Pi).
- **첫 빌드에 동작:** Caffeine은 `quarkus-caffeine`이 처리해 힌트 코드가 필요 없었습니다.
  `@ConditionalOnProperty` 같은 함정은 처음부터 런타임 설정 검사로 작성해 피했습니다(Quarkus에서는 빌드 시점
  조건이 `@IfBuildProperty`로 이름부터 구분됩니다).
- **record 리플렉션은 Quarkus도 자동으로 잡지 못합니다.** Spring의 교훈으로 `ClassRow`에
  `@RegisterForReflection`을 미리 달았고, 이걸 뺀 이미지를 따로 빌드해 확인했습니다. REST 응답 타입은
  Quarkus가 자동 등록하지만, Hub가 `ObjectMapper`로 직접 직렬화하는 타입은 대상이 아니기 때문입니다.
  - 실패 방식은 달랐습니다. Spring 네이티브는 `UnsupportedFeatureError`(`Error`)라서 flush 스레드가 죽고
    채널 전체가 멈췄습니다. Quarkus 네이티브는 Jackson의 `InvalidDefinitionException`(일반 예외)이라서
    배치 단위 `catch`에 걸려 **배지가 있는 배치만 로그를 남기고 버려지고**, 채널은 계속 동작했습니다.
  - 어느 쪽이든 연동된 시청자의 메시지가 사라지는 버그입니다. "프레임워크 밖에서 리플렉션으로 직렬화하는
    타입은 직접 등록한다"는 규칙은 두 프레임워크에 공통입니다.

## 9. 종합: 무엇이 차이를 만드는가

| 지표 | Go | Quarkus 최선 | Spring 최선 | 차이의 주된 원인 |
| --- | ---: | ---: | ---: | --- |
| 이미지 | 17.9MB | 105MB (네이티브) | 151MB (jlink) | 런타임 동봉 여부, 프레임워크 메타데이터 |
| 기동 | 1.5초 | 1.0초 (네이티브) | 1.8초 (네이티브) | JVM 모드에선 프레임워크(빌드 시점 처리 여부)가 결정 |
| 부하 중 최대 RSS | 34MB | 71MB (네이티브 + 64m) | 129MB (네이티브 + 64m) | 런타임 고정비 + 프레임워크가 로드하는 클래스 양 |
| CPU 시간 | 14.0초 | 23.0초 (네이티브) | 26.3초 (네이티브) | JIT 워밍업과 프레임워크 요청 처리 경로 |

- **"Go라서 가볍다"의 상당 부분은 "프레임워크가 얇아서"였습니다.** 같은 JVM에서 프레임워크만 바꿔도
  메모리와 기동 시간이 크게 달라졌습니다.
- **남은 격차는 런타임 자체의 고정비입니다.** 가장 얇게 만든 Java 네이티브 이미지도 이 워크로드에서
  Go의 약 2배 메모리를 썼습니다.
- **선택 기준:**
  - 이미 Java 팀이고 자원이 빠듯하면, Go로 다시 쓰기 전에 Quarkus 네이티브가 현실적인 중간 지점입니다.
    대가는 빌드 시간(Pi에서 16분)과 네이티브 전용 테스트입니다.
  - Spring을 유지해야 한다면 AOT 캐시와 JVM 튜닝만으로도 기동 시간은 절반 이하로 줄일 수 있습니다.

## 10. 재현 방법

```bash
# 이미지 빌드: spring/, quarkus/ 모두 같은 타깃 이름
for f in spring quarkus; do
  for t in jvm jlink jlink-aot native; do   # native: Pi에서 Spring 약 25분 / Quarkus 약 16분
    podman build --target $t -t djclass-$f:$t $f
  done
done

# 측정 (loadgen/)
cd ../loadgen
go build -o loadgen . && go build -o seedtool ./seed
./bench.sh go     ghcr.io/feliscatuskr/chzzk-djclass-chat:sha-d04d885
./bench.sh spring-jvm     localhost/djclass-spring:jvm
./bench.sh quarkus-native localhost/djclass-quarkus:native
./bench.sh quarkus-native-64m localhost/djclass-quarkus:native --entrypoint '["/djclass","-Xmx64m"]'
./bench.sh spring-aot-tuned localhost/djclass-spring:jlink-aot \
  -e JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx64m -Xss256k -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=96m -XX:CICompilerCount=2"
```

`bench.sh`는 호스트 포트 18080을 쓰고, `podman unshare`로 컨테이너의 SQLite 파일에 측정용 채널 20개를 넣습니다.
