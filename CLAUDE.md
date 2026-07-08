# CLAUDE.md

## 프로젝트 목적

Spring AI 학습을 위한 챗봇 프로젝트.
핵심 목표는 Spring AI의 주요 기능(ChatClient, MessageChatMemoryAdvisor, Structured Output, SSE 스트리밍)을 직접 구현하며 익히는 것이다.

## 기술 스택

- 언어: Java 21
- 프레임워크: Spring Boot 4.1.0
- Spring AI: 2.0.0
- AI 모델: NVIDIA NIM API (build.nvidia.com)
- 데이터베이스: PostgreSQL
- ORM: Spring Data JPA
- 웹: Spring WebFlux
- 로깅: Log4j2
- 유틸리티: Lombok
- 빌드 도구: Gradle

## 아키텍처 원칙

- 전체 구조는 DDD의 Bounded Context 개념을 따른다.
- 각 Bounded Context 내부는 Controller → Service → Repository 레이어드 아키텍처를 따른다.
- Context 간 직접 호출을 금지한다. 필요 시 이벤트로만 소통한다.
- 각 Context는 추후 독립적인 서비스로 분리 가능한 구조를 갖춰야 한다.
- 각 레이어의 책임은 다음과 같다.
    - Controller: 요청/응답 처리만 담당
    - Service: 비즈니스 로직 + 트랜잭션 관리, **반드시 DTO를 반환한다 (Entity 반환 금지)**
    - Repository: 데이터 접근 (Spring Data JPA 직접 사용)
- Service가 Entity를 반환하면 영속성 컨텍스트가 Controller까지 누수되어 LazyInitializationException 또는 추가 쿼리가 발생할 수 있다. 트랜잭션 경계 안에서 DTO로 변환한 뒤 반환한다.

## 패키지 구조 원칙

```
src/main/java/
├── chat/                        ← Bounded Context
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   └── exception/
├── {context}/                   ← 추후 추가될 Bounded Context
└── common/
    ├── entity/
    ├── exception/
    └── dto/
        └── response/
```

- 패키지 경계를 엄격히 지켜 추후 멀티모듈/멀티서비스 분리가 가능한 구조를 유지한다.

- Entity ↔ DTO 변환은 별도 mapper 클래스 없이 `from()` / `of()` 정적 팩토리 메서드를 사용한다.
- 공통 응답 포맷은 `common/dto/response/ApiResponse`로 통일한다.

## Entity 네이밍 규칙

- Entity 클래스명에 `Entity` 접미사를 붙이지 않는다.
- 테이블명은 `@Table(name = "...")`으로 명시적으로 지정한다.
- `entity/` 패키지 안에 위치하므로 접미사 없이도 역할이 명확하다.

```
❌ ChatRoomEntity, MessageEntity
✅ chat/entity/ChatRoom.java, chat/entity/Message.java
```

## ID 정책

- 모든 Entity의 PK는 TSID(Time-Sorted ID)를 사용한다.
- `io.hypersistence.utils.hibernate.id.Tsid` 어노테이션을 String 필드에 사용한다.
- 컬럼 타입은 `VARCHAR(13)`이다 (TSID의 표준 문자열 표현인 Crockford base32는 13자).
- 사전순 정렬 = 시간순 정렬이 성립하고, 분산 환경에서도 충돌 없이 생성된다.
- 문자열 ID이므로 JS Number 정밀도(2^53) 문제없이 API에 그대로 노출할 수 있다.

```java
@Id
@Tsid
@Column(length = 13)
private String id;
```

- UUID가 필요한 모든 곳(이벤트 ID 등)에서 `UUID.randomUUID()` 대신 `TSID.fast().toString()`을 사용한다. 시간순 정렬이 가능하고 프로젝트 전체의 ID 전략이 일관된다.
- `io.hypersistence.tsid.TSID` 클래스는 `hypersistence-utils` 의존성에 번들로 포함되어 있어 별도 추가 없이 사용 가능하다.

## Soft Delete 원칙

- 모든 Entity는 Soft delete를 적용한다.
- `common/entity/` 패키지의 `BaseEntity`를 상속받아 다음 공통 필드를 관리한다.
    - `created_at`: 생성 시각
    - `updated_at`: 수정 시각
    - `deleted_at`: 삭제 시각 (null이면 유효한 데이터)
- `@SQLRestriction("deleted_at is null")`로 삭제된 데이터를 자동 필터링한다.
- `@SQLDelete`로 DELETE 쿼리를 `UPDATE deleted_at = now()`로 대체한다.

## Entity 생성 원칙

- 모든 Entity는 Lombok `@Builder`로 생성한다.
- JPA용 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 선언한다.
- `@AllArgsConstructor(access = AccessLevel.PRIVATE)`로 Builder가 사용하는 생성자를 private으로 제한한다.

```java
@Entity
@Table(name = "chat_rooms")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ChatRoom extends BaseEntity {

    @Id
    @Tsid
    @Column(length = 13)
    private String id;

    private String title;
}
```

사용 예시는 다음과 같다.

```java
ChatRoom chatRoom = ChatRoom.builder()
        .title(title)
        .build();
```

## 입력값 검증 원칙

- 모든 요청 DTO는 Spring Validation(`@Valid`)으로 입력값을 검증한다.
- 검증 어노테이션은 DTO 필드에 직접 선언한다.

```java
public record CreateChatRoomRequest(
    @NotBlank(message = "제목은 필수입니다")
    String title
) {}
```

- Controller에서 `@Valid`로 검증을 활성화한다.
- 검증 실패 시 `GlobalExceptionHandler`에서 `ApiResponse`로 일관된 에러 응답을 반환한다.
- 입력값 형식 검증은 DTO, 비즈니스 규칙 검증은 Service로 역할을 분리한다.

## 로깅 원칙

- 로깅 백엔드는 Log4j2를 사용한다. (`spring-boot-starter-logging`을 제외하고 `spring-boot-starter-log4j2`를 사용)
- 로거 선언은 Lombok `@Log4j2` 어노테이션을 사용한다. `@Slf4j`는 사용하지 않는다.

```java
// ❌ @Slf4j
// ✅ @Log4j2
@Log4j2
@RestControllerAdvice
public class GlobalExceptionHandler { ... }
```

## 코드 품질 원칙

- Spring의 암묵적 동작보다 명시적인 코드를 선호한다.
- 각 클래스/메서드는 단일 책임에 집중한다.
- 문자열 리터럴을 여러 곳에서 참조하는 경우 반드시 상수로 추출한다.

```java
// ❌ 하드코딩
throw new BusinessException("채팅방을 찾을 수 없습니다");

// ✅ 상수 추출
private static final String CHAT_ROOM_NOT_FOUND = "채팅방을 찾을 수 없습니다";
throw new BusinessException(CHAT_ROOM_NOT_FOUND);
```

## 구현 전 필수 단계

새로운 기능을 구현하기 전에 반드시 다음 순서를 따른다.

1. 기존 코드를 먼저 읽는다.
    - 구현할 기능과 유사한 기존 코드를 전부 파악한다.
    - 코드 스타일, 패턴, 컨벤션을 정리한다.

2. 파악한 스타일을 그대로 따른다.
    - 예외 처리 방식
    - 네이밍 컨벤션
    - 코드 구조와 패턴
    - 의존성 주입 방식

3. 기존 코드와 다른 방식으로 구현해야 할 이유가 있다면 구현 전에 반드시 이유를 설명하고 확인을 받는다.
