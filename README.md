# chzzk-djclass-jvm-poc

> ⚠️ **학습용 프로토타입입니다. 운영 환경에 쓰지 마세요.**
> 로그인(치지직 OAuth), V-ARCHIVE 연동, 레이트리밋, 실제 치지직 채팅 수신이 빠져 있습니다.
> `DEV=1`은 주소 확인 없이 플래그만 보고 채팅 주입 엔드포인트를 엽니다.

[chzzk-djclass-chat](https://github.com/FelisCatusKR/chzzk-djclass-chat)(Go + SQLite)의 핵심 경로
(SSE Hub, SQLite 읽기/쓰기 풀, 배지 resolver 캐시, 보안 헤더)를 **Spring Boot 4**와 **Quarkus 3**(Java 25)로
각각 옮기고, 같은 라즈베리 파이에서 Go 서버와 똑같은 부하로 이미지 크기, 메모리, 기동 시간, CPU 시간을
비교한 실험입니다. (이전 이름: `chzzk-djclass-spring-poc`)

**결과와 해설은 [REPORT.md](REPORT.md)에 있습니다.**

| | Go | Spring (JVM, 튜닝) | Spring (네이티브) | Quarkus (JVM, 튜닝) | Quarkus (네이티브) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 이미지 | 17.9MB | 221MB | 155MB | 212MB | 105MB |
| 기동 | 1.5초 | 9.0초 | 1.8초 | 5.4초 | 1.0초 |
| 부하 중 최대 RSS | 34MB | 210MB | 129MB | 163MB | 71MB |

네이티브 두 열은 `-Xmx64m` 기준입니다.

## 구성

- `spring/`: Spring Boot 4.1 프로토타입 (Gradle)
- `quarkus/`: Quarkus 3.40 프로토타입 (Maven)
- 두 Dockerfile 모두 `jvm`, `jlink`, `jlink-aot`, `native` 타깃을 같은 이름으로 제공합니다.
- `loadgen/`: SSE 부하 생성기(`main.go`), 측정용 채널 시드(`seed/`), 측정 스크립트(`bench.sh`)
- `results/`: 측정 원본 출력과 실행 환경

커밋 히스토리가 작업 순서입니다: Spring 프로토타입 → 측정 도구 → AOT/네이티브 → 네이티브 함정 수정 →
리포트 → Quarkus 프로토타입 → jlink/AOT → loadgen 수정 → 비교 리포트.

## 라이선스

MIT
