# Spring AI Chat

Spring AI의 핵심 기능(ChatClient, 대화 메모리, Structured Output, Tool Calling, SSE 스트리밍)을 챗봇 도메인에서 직접 구현하며 학습하는 프로젝트

## 구현 기능

| 기능           | 패턴                                      | 핵심 학습 내용                            |
|--------------|-----------------------------------------|-------------------------------------|
| AI 대화        | ChatClient + MessageChatMemoryAdvisor   | 대화 메모리 자동 저장, `ChatMemory` 커스텀 구현체  |
| 메시지 트리 브랜칭   | 재생성 / 편집 후 재전송 / 형제 브랜치 전환              | 메시지를 트리로 모델링, advisor 우회 수동 프롬프트 구성 |
| 채팅방 제목 자동 생성 | Structured Output (BeanOutputConverter) | JSON 스키마 프롬프트 주입, 응답 역직렬화           |
| Tool Calling | `@Tool` 어노테이션, 멀티스텝(2단계) 툴콜             | 모델의 자율적 도구 선택/체이닝, 기본 예외 처리 동작      |

## 기술 스택

| 구분     | 기술                                              |
|--------|-------------------------------------------------|
| 언어     | Java 21                                         |
| 프레임워크  | Spring Boot 4.1.0                               |
| AI     | Spring AI 2.0.0 (NVIDIA NIM API, OpenAI 호환)     |
| 데이터베이스 | PostgreSQL                                      |
| ORM    | Spring Data JPA (Hibernate)                     |
| 웹      | Spring WebFlux                                  |
| ID 생성  | TSID (hypersistence-utils)                      |
| 로깅     | Log4j2                                          |
| 빌드     | Gradle (Kotlin DSL)                             |
| 테스트    | JUnit5, Mockito, Fixture Monkey, Testcontainers |

## 아키텍처

### DDD Bounded Context

패키지로 경계를 구분하는 단일 모듈 구조다. 지금은 `chat` 컨텍스트 하나뿐이지만, 추후 Context가 늘어나도 독립 서비스로 분리 가능하도록 경계를 지킨다.

```
src/main/java/com/example/springaichat/
├── chat/                        ← 채팅 Bounded Context
│   ├── controller/              ChatController, ChatRoomController
│   ├── service/                 ChatService, ChatRoomService, MessageBranchService
│   ├── repository/              ChatRoomRepository, MessageRepository(+Custom)
│   ├── entity/                  ChatRoom, Message, MessageRole
│   ├── memory/                  PersistentChatMemory (ChatMemory PostgreSQL 구현체)
│   ├── tool/                    DateTimeTools, ChatRoomHistoryTools (Tool Calling)
│   ├── dto/                     ChatRoomTitle(Structured Output) + request/response
│   └── exception/
└── common/
    ├── entity/                  BaseEntity (created_at/updated_at/deleted_at)
    ├── exception/                BusinessException, GlobalExceptionHandler
    └── dto/response/             ApiResponse
```

Context 내부는 Controller → Service → Repository 계층을 따른다. Service는 Entity를 절대 반환하지 않고 트랜잭션 경계 안에서 DTO로 변환해 반환한다.
`MessageBranchService`는 예외로, Controller에 직접 노출되지 않고 다른 Service가 내부적으로만 쓰는 협력 객체라 Entity를 그대로 주고받는다.

모든 Entity의 PK는 TSID(Time-Sorted ID)를 사용해 사전순 정렬이 곧 시간순 정렬이 되도록 했고, Soft delete(`deleted_at`)를 공통으로 적용했다.

### 메시지 트리 브랜칭

메시지를 선형 리스트가 아니라 트리로 모델링해 ChatGPT류의 "‹ 1/2 ›" 형제 탐색 UX를 구현했다. `Message`는 `parentMessage`/`activeChildMessage`를
`@ManyToOne`(DB FK 제약은 걸지 않음)으로 갖고, `ChatRoom`은 현재 보고 있는 가지의 끝을 가리키는 `activeLeafMessageId` 컬럼을 갖는다.

```
[ChatRoom.activeLeafMessageId] 가 가리키는 지점까지가 "활성 경로"

Q1(root) ─┬─ A1 ─── Q2 ─── A2(activeLeaf)   ← 정상 대화
          │
          └─ A1'  (재생성으로 만든 형제, activeChild가 A1을 가리키면 그쪽이 화면에 보임)

편집(edit): Q1'을 Q1의 형제로 추가 → 그 아래로 새 가지 시작
전환(switch): 어떤 메시지에서 activeChildMessage를 리프까지 따라 내려가 activeLeaf를 옮김
```

핵심 단순화는 **`activeChildMessage`를 메시지가 새로 추가(append)될 때 그 부모 노드에 한 번만 기록**해두는 것이다. 나중에 그 지점으로 다시 전환(`switchBranch`)할 때는 그냥
포인터를 리프까지 따라 내려가기만 하면 되므로 별도의 "마지막으로 보던 자식" 조회가 필요 없다. 재생성을 두 번 하면 자연히 최신 응답이 활성 경로가 되고 예전 응답은 ‹ 1/2 ›로 되돌아가서 볼 수 있는 것도
이 덕분에 별도 처리 없이 따라온다.

`MessageChatMemoryAdvisor`는 "새 메시지를 활성 경로 끝에 이어붙이는" 정상 대화 경로에서만 쓴다. 재생성/편집은 advisor의 공개 API로는 부모를 지정할 방법이 없어서(`before()`
가 무조건 마지막 유저 메시지를 자동 저장) advisor를 우회하고 `plainChatClient`로 수동 프롬프트 구성 + `MessageBranchService.appendMessage`로 수동 저장한다.

```
ChatClient 3단 구성 (ChatService)

titleChatClient   = 기본 빌더                                    (시스템 프롬프트 X, Tool X, Advisor X)
       └─ mutate()
plainChatClient   = titleChatClient + DEFAULT_SYSTEM_PROMPT + Tools   (재생성/편집이 사용)
       └─ mutate()
chatClient        = plainChatClient + MessageChatMemoryAdvisor        (정상 대화가 사용)
```

### Tool Calling

`@Tool` 어노테이션이 붙은 메서드를 `plainChatClient.defaultTools(...)`로 등록하면, 모델이 필요하다고 판단할 때 스스로 호출한다.

- **현재 시각 조회** (`DateTimeTools`): 파라미터 없는 단일 툴. 사용자가 오늘 날짜/요일/시각을 물으면 모델이 실제 시스템 시각을 조회해 답한다.
- **다른 채팅방 대화 참조** (`ChatRoomHistoryTools`): `listChatRooms` → `getChatRoomHistory`의 2단계 멀티스텝 툴콜.

```
사용자: "이력서 작성 방에서 만든 내용을 참고해서 포트폴리오를 써줘"
   │
   ▼
모델이 listChatRooms() 호출        → 전체 채팅방 id/제목 목록 확보
   │  (제목으로 "이력서 작성" 방을 스스로 특정)
   ▼
모델이 getChatRoomHistory(id) 호출 → 그 방의 활성 경로 대화 내용 확보
   │
   ▼
모델이 두 결과를 종합해 답변 생성
```

`getChatRoomHistory`는 존재하지 않는 chatRoomId가 들어와도 별도 try/catch를 두지 않는다. Spring AI 2.0.0의
`DefaultToolExecutionExceptionProcessor`는 `DEFAULT_ALWAYS_THROW = false`라서(바이트코드로 확인) 툴 메서드의 예외를 기본적으로 잡아 모델에게 메시지로 돌려주기
때문이다. "이력서 작성" 방에 실제로 만든 내용을 "포트폴리오 작성" 방에서 그대로 인용해 답변하도록 요청하는 시나리오로 실제 NVIDIA NIM 모델 대상 End-to-End 검증을 마쳤다.

### SSE 스트리밍

`chat/stream`, `regenerate/stream`, `edit/stream` 세 엔드포인트가 같은 방식으로 동작한다. Controller가 `Flux<String>`을 반환하면 WebFlux가 토큰
델타마다 `data: <delta>\n\n`으로 인코딩해 흘려보내고, 클라이언트는 `fetch` + `ReadableStream.getReader()`로 그걸 직접 파싱해 버블에 이어붙인다. `EventSource`
를 안 쓰는 이유는 `edit/stream`이 POST 바디(수정된 메시지)를 보내야 하는데 `EventSource`는 GET만 지원하기 때문이다.

`stream-start`/`done` 같은 이름 붙은 이벤트 없이 스트림 종료는 SSE 레벨이 아니라 HTTP 전송 레벨에서 감지한다.

```
[ChatService]                              [브라우저]
chatClient.stream().content()
  │ 토큰 델타 방출
  ▼
doOnNext: buffer 누적      data: <delta>\n\n  ──►  reader.read() → { done: false, value }
  │                                                버블에 delta append
  ▼ Flux onComplete (HTTP 응답 종료)
doOnComplete:                                 ──►  reader.read() → { done: true }
  appendMessage로 최종 텍스트 저장                    GET .../messages 재호출로
                                                    진짜 id/형제 인덱스 반영
```

`reader.read()`가 `{ done: true }`를 돌려주는 시점은 서버의 `Flux`가 `onComplete`로 끝나 HTTP 응답이 닫힌 시점 그대로다. 별도의 종료 이벤트를 보내는 게 아니라 HTTP
스트림 종료 신호를 그대로 재사용하는 것이다. 스트림이 도중에 끊기면(`doOnError`/`doOnCancel`) `appendMessage`는 아예 호출되지 않는다.

## 테스트 구조

```
test/java/com/example/springaichat/
├── chat/
│   ├── controller/
│   │   ├── ChatControllerTest.java          ← Testcontainers 통합 테스트 (대화/재생성/편집/SSE)
│   │   └── ChatRoomControllerTest.java      ← Testcontainers 통합 테스트 (방 CRUD, 브랜치 전환)
│   ├── service/
│   │   ├── ChatServiceTest.java             ← Mockito 단위 테스트
│   │   ├── ChatRoomServiceTest.java         ← Mockito 단위 테스트
│   │   └── MessageBranchServiceTest.java    ← Mockito 단위 테스트 (트리 순회/전환 로직)
│   ├── memory/PersistentChatMemoryTest.java ← Mockito 단위 테스트 (add/get/clear 위임 검증)
│   ├── repository/MessageRepositoryCustomImplTest.java  ← Testcontainers 통합 테스트
│   └── tool/
│       ├── DateTimeToolsTest.java
│       └── ChatRoomHistoryToolsTest.java
└── support/TestcontainersConfiguration.java ← PostgreSQL 컨테이너 공통 설정
```

Controller/Repository 계층은 Testcontainers로 PostgreSQL을 실제로 띄워 검증하고, Service/Tool 계층은 Mockito로 협력 객체를 목킹한 단위 테스트로 검증한다.
