package com.example.springaichat.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MessageBranchServiceTest {

    private static final String CHAT_ROOM_ID = "chat-room-id";

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private MessageBranchService messageBranchService;

    @Test
    @DisplayName("getAncestorChain은 소속을 검증한 뒤 리포지토리 결과(리프가 먼저)를 시간순으로 뒤집어 반환한다")
    void getAncestorChain_validatesAndReversesRepositoryResult() {
        ChatRoom chatRoom = ChatRoom.builder().id(CHAT_ROOM_ID).build();
        Message leaf = Message.builder().id("msg-2").chatRoom(chatRoom).role(MessageRole.USER).content("메시지-2")
            .build();
        given(messageRepository.findById("msg-2")).willReturn(Optional.of(leaf));

        Message repoLeaf = Message.builder().id("msg-2").role(MessageRole.USER).content("메시지-2").build();
        Message repoParent = Message.builder().id("msg-1").role(MessageRole.ASSISTANT).content("메시지-1").build();
        Message repoRoot = Message.builder().id("msg-0").role(MessageRole.USER).content("메시지-0").build();
        given(messageRepository.findAncestorChain(eq("msg-2"), anyInt()))
            .willReturn(List.of(repoLeaf, repoParent, repoRoot));

        List<Message> result = messageBranchService.getAncestorChain(CHAT_ROOM_ID, "msg-2");

        assertThat(result).extracting(Message::getId).containsExactly("msg-0", "msg-1", "msg-2");
    }

}
