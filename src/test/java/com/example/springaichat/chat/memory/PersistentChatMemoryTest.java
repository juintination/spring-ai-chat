package com.example.springaichat.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import com.example.springaichat.chat.service.MessageBranchService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

@ExtendWith(MockitoExtension.class)
class PersistentChatMemoryTest {

    private static final String CHAT_ROOM_ID = "chat-room-id";

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageBranchService messageBranchService;

    @InjectMocks
    private PersistentChatMemory chatMemory;

    @Test
    @DisplayName("add()는 유저/어시스턴트 메시지만 엔티티로 변환해 활성 브랜치에 append를 위임한다")
    void add_delegatesToMessageBranchService() {
        List<org.springframework.ai.chat.messages.Message> messages = List.of(
            new UserMessage("질문"),
            new SystemMessage("무시되어야 함"),
            new AssistantMessage("답변")
        );

        chatMemory.add(CHAT_ROOM_ID, messages);

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageBranchService).appendToActiveBranch(eq(CHAT_ROOM_ID), captor.capture());

        List<Message> templates = captor.getValue();
        assertThat(templates).hasSize(2);
        assertThat(templates.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(templates.get(0).getContent()).isEqualTo("질문");
        assertThat(templates.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(templates.get(1).getContent()).isEqualTo("답변");
    }

    @Test
    @DisplayName("get()은 MessageBranchService가 돌려준 활성 경로를 그대로 AI 메시지로 매핑한다")
    void get_mapsActivePathToAiMessages() {
        // 창 크기 제한은 MessageBranchService.getActivePath가 walk 단계에서부터 적용하므로,
        // 여기서는 순수 매핑(위임)만 검증한다.
        List<Message> activePath = List.of(
            entity(MessageRole.USER, "질문"),
            entity(MessageRole.ASSISTANT, "답변"));
        given(messageBranchService.getActivePath(CHAT_ROOM_ID)).willReturn(activePath);

        List<org.springframework.ai.chat.messages.Message> result = chatMemory.get(CHAT_ROOM_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("clear()는 방의 메시지를 모두 삭제하고 activeLeafMessageId를 초기화한다")
    void clear_deletesMessagesAndResetsActiveLeaf() {
        ChatRoom chatRoom = ChatRoom.builder().id(CHAT_ROOM_ID).title("대화").activeLeafMessageId("msg-1").build();
        List<Message> messages = List.of(entity(MessageRole.USER, "질문"));
        given(messageRepository.findByChatRoomIdOrderByIdAsc(CHAT_ROOM_ID)).willReturn(messages);
        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(chatRoom));

        chatMemory.clear(CHAT_ROOM_ID);

        verify(messageRepository).deleteAll(messages);
        assertThat(chatRoom.getActiveLeafMessageId()).isNull();
    }

    private Message entity(MessageRole role, String content) {
        return Message.builder().role(role).content(content).build();
    }

}
