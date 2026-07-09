package com.example.springaichat.chat.memory;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화 메모리를 messages 테이블에 영속화하는 ChatMemory 구현체 MessageChatMemoryAdvisor가 유저/어시스턴트 메시지 저장과 히스토리 조회를 이 클래스에 위임한다.
 */
@Component
@RequiredArgsConstructor
public class PersistentChatMemory implements ChatMemory {

    private static final int MEMORY_WINDOW_SIZE = 20;

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        ChatRoom chatRoom = chatRoomRepository.findById(conversationId)
            .orElseThrow(ChatRoomNotFoundException::new);

        List<com.example.springaichat.chat.entity.Message> entities = messages.stream()
            .map(message -> toEntity(chatRoom, message))
            .filter(Objects::nonNull)
            .toList();

        messageRepository.saveAll(entities);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> get(String conversationId) {
        List<com.example.springaichat.chat.entity.Message> recentMessages = messageRepository
            .findByChatRoomIdOrderByIdDesc(conversationId, PageRequest.of(0, MEMORY_WINDOW_SIZE));

        return recentMessages.reversed().stream()
            .map(this::toAiMessage)
            .toList();
    }

    @Override
    @Transactional
    public void clear(String conversationId) {
        List<com.example.springaichat.chat.entity.Message> messages = messageRepository
            .findByChatRoomIdOrderByIdAsc(conversationId);

        messageRepository.deleteAll(messages);
    }

    private com.example.springaichat.chat.entity.Message toEntity(ChatRoom chatRoom, Message message) {
        MessageRole role = switch (message.getMessageType()) {
            case USER -> MessageRole.USER;
            case ASSISTANT -> MessageRole.ASSISTANT;
            default -> null;
        };
        if (role == null) {
            return null;
        }
        return com.example.springaichat.chat.entity.Message.builder()
            .chatRoom(chatRoom)
            .role(role)
            .content(message.getText())
            .build();
    }

    private Message toAiMessage(com.example.springaichat.chat.entity.Message message) {
        return switch (message.getRole()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
        };
    }

}
