package com.example.springaichat.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.springaichat.chat.dto.request.ChatRequest;
import com.example.springaichat.chat.dto.response.ChatResponse;
import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.memory.PersistentChatMemory;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
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

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        // ChatClient가 요청을 조립할 때 ChatModel의 기본 옵션을 참조하므로 null이 아닌 값을 반환하도록 한다
        given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());

        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
        chatService = new ChatService(chatClientBuilder, chatMemory, chatRoomRepository, messageRepository);
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

    private org.springframework.ai.chat.model.ChatResponse chatResponseOf(String content) {
        return new org.springframework.ai.chat.model.ChatResponse(
            List.of(new Generation(new AssistantMessage(content))));
    }

}
