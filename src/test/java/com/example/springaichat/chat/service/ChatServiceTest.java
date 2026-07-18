package com.example.springaichat.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.response.ChatResponse;
import com.example.springaichat.chat.dto.response.EditAndResendResponse;
import com.example.springaichat.chat.dto.response.MessageResponse;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.exception.InvalidBranchTargetException;
import com.example.springaichat.chat.memory.PersistentChatMemory;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.chat.service.MessageBranchService.BranchNode;
import com.example.springaichat.chat.tool.ChatRoomHistoryTools;
import com.example.springaichat.chat.tool.DateTimeTools;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String CHAT_ROOM_ID = "chat-room-id";

    // ChatService의 TITLE_GENERATION_PROMPT에 포함된 문구로, 목 응답을 분기하는 데 사용한다
    private static final String TITLE_PROMPT_MARKER = "채팅방 제목";

    @Mock
    private ChatModel chatModel;

    @Mock
    private PersistentChatMemory chatMemory;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageBranchService messageBranchService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        // ChatClient가 요청을 조립할 때 ChatModel의 기본 옵션을 참조하므로 null이 아닌 값을 반환하도록 한다.
        // 검증 실패로 ChatClient까지 도달하지 않는 테스트도 있어 lenient로 둔다.
        lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
        chatService = new ChatService(chatClientBuilder, chatMemory, chatRoomRepository, messageRepository,
                messageBranchService, new DateTimeTools(),
                new ChatRoomHistoryTools(chatRoomRepository, messageBranchService));
    }

    @Test
    @DisplayName("채팅 요청을 보내면 AI 응답을 반환한다")
    void chat() {
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageRepository.existsByChatRoomId(CHAT_ROOM_ID)).willReturn(true);
        given(chatMemory.get(anyString())).willReturn(List.of());
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("안녕하세요! 무엇을 도와드릴까요?"));

        ChatResponse response = chatService.chat(CHAT_ROOM_ID, new ChatRequest("안녕"));

        assertThat(response.content()).isEqualTo("안녕하세요! 무엇을 도와드릴까요?");
    }

    @Test
    @DisplayName("첫 대화가 끝나면 채팅방 제목을 비동기로 생성해 저장한다")
    void chat_firstConversation_generatesTitle() {
        ChatRoom chatRoom = ChatRoom.builder().id(CHAT_ROOM_ID).title("새 대화").build();
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageRepository.existsByChatRoomId(CHAT_ROOM_ID)).willReturn(false);
        given(chatMemory.get(anyString())).willReturn(List.of());
        given(chatModel.call(any(Prompt.class))).willAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            boolean titleRequest = prompt.getContents().contains(TITLE_PROMPT_MARKER);
            return titleRequest
                ? chatResponseOf("{\"title\":\"김치찌개 레시피\"}")
                : chatResponseOf("김치찌개는 이렇게 끓여요.");
        });
        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(chatRoom));

        chatService.chat(CHAT_ROOM_ID, new ChatRequest("김치찌개 끓이는 법 알려줘"));

        verify(chatRoomRepository, timeout(2000)).save(chatRoom);
        assertThat(chatRoom.getTitle()).isEqualTo("김치찌개 레시피");
    }

    @Test
    @DisplayName("스트리밍 채팅 응답은 토큰 단위로 전달된다")
    void chatStream() {
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageRepository.existsByChatRoomId(CHAT_ROOM_ID)).willReturn(true);
        given(chatMemory.get(anyString())).willReturn(List.of());
        given(chatModel.stream(any(Prompt.class)))
            .willReturn(Flux.just(chatResponseOf("안녕"), chatResponseOf("하세요")));

        Flux<String> result = chatService.chatStream(CHAT_ROOM_ID, new ChatRequest("안녕"));

        StepVerifier.create(result)
            .expectNext("안녕", "하세요")
            .verifyComplete();
    }

    @Test
    @DisplayName("유저 메시지를 지정해 재생성하면 새 형제 응답이 저장된다")
    void regenerate() {
        String userMessageId = "user-msg-id";
        Message userMessage = Message.builder().id(userMessageId).role(MessageRole.USER).content("질문").build();
        Message assistantMessage = Message.builder().id("assistant-msg-2").role(MessageRole.ASSISTANT)
                .content("두 번째 답변").build();

        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, userMessageId)).willReturn(List.of(userMessage));
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("두 번째 답변"));
        given(messageBranchService.appendMessage(CHAT_ROOM_ID, userMessageId, MessageRole.ASSISTANT, "두 번째 답변"))
                .willReturn(assistantMessage);
        given(messageBranchService.getActivePathWithSiblingInfo(CHAT_ROOM_ID)).willReturn(List.of(
                new BranchNode(userMessage, 1, 1, List.of(userMessageId)),
                new BranchNode(assistantMessage, 2, 2, List.of("assistant-msg-1", "assistant-msg-2"))));

        MessageResponse response = chatService.regenerate(CHAT_ROOM_ID, userMessageId);

        assertThat(response.id()).isEqualTo("assistant-msg-2");
        assertThat(response.content()).isEqualTo("두 번째 답변");
        assertThat(response.siblingIndex()).isEqualTo(2);
        assertThat(response.siblingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("유저 메시지가 아닌 대상으로 재생성하면 예외가 발생한다")
    void regenerate_invalidTarget() {
        String messageId = "assistant-msg-id";
        Message assistantMessage = Message.builder().id(messageId).role(MessageRole.ASSISTANT).content("답변").build();
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, messageId)).willReturn(List.of(assistantMessage));

        assertThatThrownBy(() -> chatService.regenerate(CHAT_ROOM_ID, messageId))
                .isInstanceOf(InvalidBranchTargetException.class);
    }

    @Test
    @DisplayName("재생성 스트림은 완료 시 새 응답을 저장한다")
    void regenerateStream() {
        String userMessageId = "user-msg-id";
        Message userMessage = Message.builder().id(userMessageId).role(MessageRole.USER).content("질문").build();
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, userMessageId)).willReturn(List.of(userMessage));
        given(chatModel.stream(any(Prompt.class)))
                .willReturn(Flux.just(chatResponseOf("두"), chatResponseOf("번째")));

        Flux<String> result = chatService.regenerateStream(CHAT_ROOM_ID, userMessageId);

        StepVerifier.create(result)
                .expectNext("두", "번째")
                .verifyComplete();

        verify(messageBranchService, timeout(2000))
                .appendMessage(CHAT_ROOM_ID, userMessageId, MessageRole.ASSISTANT, "두번째");
    }

    @Test
    @DisplayName("재생성 스트림 완료 후 응답 저장이 실패해도 스트림 자체는 정상 완료된다")
    void regenerateStream_persistFailure_doesNotPropagateError() {
        String userMessageId = "user-msg-id";
        Message userMessage = Message.builder().id(userMessageId).role(MessageRole.USER).content("질문").build();
        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, userMessageId)).willReturn(List.of(userMessage));
        given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(chatResponseOf("답변")));
        given(messageBranchService.appendMessage(CHAT_ROOM_ID, userMessageId, MessageRole.ASSISTANT, "답변"))
                .willThrow(new RuntimeException("DB 오류"));

        Flux<String> result = chatService.regenerateStream(CHAT_ROOM_ID, userMessageId);

        StepVerifier.create(result)
                .expectNext("답변")
                .verifyComplete();

        verify(messageBranchService, timeout(2000))
                .appendMessage(CHAT_ROOM_ID, userMessageId, MessageRole.ASSISTANT, "답변");
    }

    @Test
    @DisplayName("유저 메시지를 편집해 재전송하면 새 형제 유저 메시지와 응답이 저장된다")
    void editAndResend() {
        String userMessageId = "user-msg-id";
        Message original = Message.builder().id(userMessageId).role(MessageRole.USER).content("원래 질문").build();
        Message newUserMessage = Message.builder().id("user-msg-2").role(MessageRole.USER).content("수정된 질문").build();
        Message assistantMessage = Message.builder().id("assistant-msg-2").role(MessageRole.ASSISTANT)
                .content("수정된 답변").build();

        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, userMessageId)).willReturn(List.of(original));
        given(messageBranchService.appendMessage(CHAT_ROOM_ID, null, MessageRole.USER, "수정된 질문"))
                .willReturn(newUserMessage);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, "user-msg-2")).willReturn(List.of(newUserMessage));
        given(chatModel.call(any(Prompt.class))).willReturn(chatResponseOf("수정된 답변"));
        given(messageBranchService.appendMessage(CHAT_ROOM_ID, "user-msg-2", MessageRole.ASSISTANT, "수정된 답변"))
                .willReturn(assistantMessage);
        given(messageBranchService.getActivePathWithSiblingInfo(CHAT_ROOM_ID)).willReturn(List.of(
                new BranchNode(newUserMessage, 2, 2, List.of(userMessageId, "user-msg-2")),
                new BranchNode(assistantMessage, 1, 1, List.of("assistant-msg-2"))));

        EditAndResendResponse response = chatService.editAndResend(CHAT_ROOM_ID, userMessageId, "수정된 질문");

        assertThat(response.userMessage().id()).isEqualTo("user-msg-2");
        assertThat(response.userMessage().content()).isEqualTo("수정된 질문");
        assertThat(response.assistantMessage().id()).isEqualTo("assistant-msg-2");
        assertThat(response.assistantMessage().content()).isEqualTo("수정된 답변");
    }

    @Test
    @DisplayName("편집 재전송 스트림은 완료 시 새 유저 메시지의 자식으로 응답을 저장한다")
    void editAndResendStream() {
        String userMessageId = "user-msg-id";
        Message original = Message.builder().id(userMessageId).role(MessageRole.USER).content("원래 질문").build();
        Message newUserMessage = Message.builder().id("user-msg-2").role(MessageRole.USER).content("수정된 질문").build();

        given(chatRoomRepository.existsById(CHAT_ROOM_ID)).willReturn(true);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, userMessageId)).willReturn(List.of(original));
        given(messageBranchService.appendMessage(CHAT_ROOM_ID, null, MessageRole.USER, "수정된 질문"))
                .willReturn(newUserMessage);
        given(messageBranchService.getAncestorChain(CHAT_ROOM_ID, "user-msg-2")).willReturn(List.of(newUserMessage));
        given(chatModel.stream(any(Prompt.class)))
                .willReturn(Flux.just(chatResponseOf("수정"), chatResponseOf("된 답변")));

        Flux<String> result = chatService.editAndResendStream(CHAT_ROOM_ID, userMessageId, "수정된 질문");

        StepVerifier.create(result)
                .expectNext("수정", "된 답변")
                .verifyComplete();

        verify(messageBranchService, timeout(2000))
                .appendMessage(CHAT_ROOM_ID, "user-msg-2", MessageRole.ASSISTANT, "수정된 답변");
    }

    private org.springframework.ai.chat.model.ChatResponse chatResponseOf(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
            List.of(new Generation(new AssistantMessage(content))));
    }

}
