# chzzk-djclass-chat Spring Boot 이전 실험 리포트 (2026-10-12)

[chzzk-djclass-chat](https://github.com/FelisCatusKR/chzzk-djclass-chat)(Go + SQLite)의 핵심 경로를
Spring Boot로 옮겼을 때 코드가 어떻게 구성되는지, Go에서 해 둔 튜닝이 Spring 생태계에서 어떻게
대응되는지, 그리고 이미지 크기·메모리·기동 시간이 얼마나 차이 나는지를 **실제로 측정**한 기록입니다.

- 대상: Spring Boot 4.1.1, Java 25 (Temurin 25.0.4 / GraalVM CE 25.0.2)
- 비교 기준: 프로덕션 Go 이미지 `ghcr.io/feliscatuskr/chzzk-djclass-chat` (revision `d04d885`)
- 측정 호스트: Raspberry Pi 4 (4코어, RAM 6GB, aarch64) — 실제 프로덕션과 같은 종류의 머신
- 원본 수치: [`results/2026-10-12-rpi4.txt`](results/2026-10-12-rpi4.txt)

## 1. 요약

| 변형 | 이미지 | 기동 | 유휴 RSS | 부하 중 최대 RSS | CPU 시간 |
| --- | ---: | ---: | ---: | ---: | ---: |
| **Go (현재 프로덕션)** | **17.9MB** | **1.1초** | **15MB** | **35MB** | **15.6초** |
| Spring, 기본 JRE (`jvm`) | 423MB | 23.8초 | 216MB | 273MB | 69.8초 |
| jlink + distroless (`jlink`) | 151MB | 26.1초 | 258MB | 306MB | 65.8초 |
| + JDK 25 AOT 캐시 (`jlink-aot`) | 221MB | 11.6초 | 222MB | 311MB | 66.2초 |
| + JVM 튜닝 (`aot-tuned`) | 221MB | 9.0초 | 176MB | 212MB | 67.5초 |
| GraalVM 네이티브 (`native`) | 155MB | 1.9초 | 141MB | 174MB | 28.0초 |
| 네이티브 + `-Xmx64m` | 155MB | 1.8초 | 109MB | 137MB | 26.5초 |

부하 조건: 20채널 × 위젯 5개 = SSE 100개, 채널당 초당 5개(전체 초당 100개) 채팅, 90초.
기동 시간은 컨테이너 시작부터 `/healthz` 200까지이며 podman 오버헤드 약 1초가 포함되어 있습니다.
모든 변형에서 위젯 하나당 받은 이벤트 수는 357~360개로 같았습니다(기능적으로 동등).

**결론:** 동작은 같게 만들 수 있지만, 이 호스트 기준으로 메모리는 Go의 4~9배, 이미지는 8~24배,
CPU 시간은 1.7~4.5배입니다. 기동 시간은 JVM이면 9~26초, 네이티브여도 Go보다 깁니다.
"머지할 때마다 프로덕션이 재시작되는" 이 서비스에서는 기동 시간이 곧 위젯이 끊기는 시간입니다.

## 2. 코드 구성: Go 패키지 → Spring

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

## 3. 이미지 크기

- **기본 JRE (423MB):** Ubuntu 기반 Temurin JRE(약 190MB)와 앱(44MB), 그리고 OS 레이어입니다.
- **jlink + distroless (151MB):** `jdeps`로 필요한 모듈만 고른 런타임입니다. 그래도 Spring이 `java.beans`를
  쓰기 때문에 `java.desktop`(AWT)이 딸려옵니다.
- **`sqlite-jdbc` 하나가 14MB입니다.** 모든 OS·아키텍처의 네이티브 라이브러리를 jar에 담고 있기 때문입니다.
  linux-aarch64/x86_64만 남기면 더 줄일 수 있습니다.
- **AOT 캐시(70MB)** 만큼 `jlink-aot` 이미지가 커집니다.
- **네이티브(155MB):** 바이너리가 정적 링크된 Go와 달리 distroless/cc(glibc) 위에 올라가고,
  Spring 메타데이터가 바이너리 안에 들어갑니다.

## 4. 메모리: 어디에 쓰이는가

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

## 5. 기동 시간

| 방법 | 기동 | 대가 |
| --- | ---: | --- |
| 기본 | 24~26초 | — |
| JDK 25 AOT 캐시 (JEP 483/514/515) | 11.6초 | 빌드 때 학습 실행 1회, 이미지 +70MB |
| AOT 캐시 + SerialGC 등 튜닝 | 9.0초 | 처리량을 일부 포기 |
| GraalVM 네이티브 | 1.9초 | 빌드 23분 + RAM 5GB, 아래 함정들 |

가성비는 AOT 캐시가 가장 좋았습니다. Dockerfile에 스테이지 하나를 추가하는 것으로 끝납니다.

## 6. 직접 겪은 GraalVM 네이티브 이미지 함정

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

## 7. 얻는 것과 잃는 것 (측정 후)

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

## 8. 재현 방법

```bash
# 이미지 빌드 (spring/)
cd spring
podman build --target jvm       -t djclass-spring:jvm .
podman build --target jlink     -t djclass-spring:jlink .
podman build --target jlink-aot -t djclass-spring:jlink-aot .
podman build --target native    -t djclass-spring:native .     # Pi에서 약 25분, RAM 5GB

# 측정 (loadgen/)
cd ../loadgen
go build -o loadgen . && go build -o seedtool ./seed
./bench.sh go     ghcr.io/feliscatuskr/chzzk-djclass-chat:sha-d04d885
./bench.sh jvm    localhost/djclass-spring:jvm
./bench.sh native localhost/djclass-spring:native
./bench.sh aot-tuned localhost/djclass-spring:jlink-aot \
  -e JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xmx64m -Xss256k -XX:ReservedCodeCacheSize=32m -XX:MaxMetaspaceSize=96m -XX:CICompilerCount=2"
```

`bench.sh`는 호스트 포트 18080을 쓰고, `podman unshare`로 컨테이너의 SQLite 파일에 측정용 채널 20개를 넣습니다.
