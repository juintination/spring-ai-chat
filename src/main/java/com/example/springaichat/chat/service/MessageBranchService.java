package com.example.springaichat.chat.service;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.chat.exception.ChatRoomNotFoundException;
import com.example.springaichat.chat.exception.MessageNotFoundException;
import com.example.springaichat.chat.repository.ChatRoomRepository;
import com.example.springaichat.chat.repository.MessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지 트리(브랜치) 순회/조작을 담당한다. LLM 호출은 하지 않으며, ChatService/ChatRoomService/PersistentChatMemory가 내부적으로 사용하는 협력
 * 객체다(Controller에서 직접 주입하지 않음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageBranchService {

    private static final int MEMORY_WINDOW_SIZE = 20;

    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;

    public List<Message> getActivePath(String chatRoomId) {
        ChatRoom chatRoom = getChatRoomOrThrow(chatRoomId);
        String leafId = chatRoom.getActiveLeafMessageId();
        return (leafId == null) ? List.of() : getAncestorChain(chatRoomId, leafId);
    }

    public List<BranchNode> getActivePathWithSiblingInfo(String chatRoomId) {
        ChatRoom chatRoom = getChatRoomOrThrow(chatRoomId);
        String leafId = chatRoom.getActiveLeafMessageId();
        if (leafId == null) {
            return List.of();
        }

        List<Message> allMessages = messageRepository.findByChatRoomIdOrderByIdAsc(chatRoomId);
        Map<String, Message> byId = allMessages.stream()
            .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<String, List<Message>> childrenByParentId = allMessages.stream()
            .filter(message -> message.getParentMessage() != null)
            .collect(Collectors.groupingBy(message -> message.getParentMessage().getId()));
        List<Message> roots = allMessages.stream()
            .filter(message -> message.getParentMessage() == null)
            .toList();

        List<Message> path = new ArrayList<>();
        Message current = byId.get(leafId);
        while (current != null) {
            path.add(current);
            Message parent = current.getParentMessage();
            current = (parent == null) ? null : byId.get(parent.getId());
        }
        Collections.reverse(path);

        List<BranchNode> result = new ArrayList<>();
        for (Message message : path) {
            Message parent = message.getParentMessage();
            List<Message> siblings = (parent == null)
                ? roots
                : childrenByParentId.getOrDefault(parent.getId(), List.of());
            int siblingIndex = siblings.indexOf(message) + 1;
            List<String> siblingIds = siblings.stream().map(Message::getId).toList();
            result.add(new BranchNode(message, siblingIndex, siblings.size(), siblingIds));
        }
        return result;
    }

    public List<Message> getAncestorChain(String chatRoomId, String messageId) {
        List<Message> chain = new ArrayList<>();
        Message current = getMessageInRoomOrThrow(chatRoomId, messageId);
        while (current != null && chain.size() < MEMORY_WINDOW_SIZE) {
            chain.add(current);
            current = current.getParentMessage();
        }
        Collections.reverse(chain);
        return chain;
    }

    @Transactional
    public void appendToActiveBranch(String chatRoomId, List<Message> newMessagesInOrder) {
        for (Message template : newMessagesInOrder) {
            ChatRoom chatRoom = getChatRoomOrThrow(chatRoomId);
            appendMessage(chatRoomId, chatRoom.getActiveLeafMessageId(), template.getRole(), template.getContent());
        }
    }

    @Transactional
    public Message appendMessage(String chatRoomId, String parentMessageId, MessageRole role, String content) {
        ChatRoom chatRoom = getChatRoomOrThrow(chatRoomId);
        Message parent = (parentMessageId == null) ? null : getMessageInRoomOrThrow(chatRoomId, parentMessageId);

        Message saved = messageRepository.save(Message.builder()
            .chatRoom(chatRoom)
            .role(role)
            .content(content)
            .parentMessage(parent)
            .build());

        if (parent != null) {
            parent.updateActiveChildMessage(saved);
        }
        chatRoom.updateActiveLeafMessageId(saved.getId());
        return saved;
    }

    @Transactional
    public void switchBranch(String chatRoomId, String messageId) {
        ChatRoom chatRoom = getChatRoomOrThrow(chatRoomId);
        Message current = getMessageInRoomOrThrow(chatRoomId, messageId);
        while (current.getActiveChildMessage() != null) {
            current = current.getActiveChildMessage();
        }
        chatRoom.updateActiveLeafMessageId(current.getId());
    }

    private ChatRoom getChatRoomOrThrow(String chatRoomId) {
        return chatRoomRepository.findById(chatRoomId).orElseThrow(ChatRoomNotFoundException::new);
    }

    private Message getMessageInRoomOrThrow(String chatRoomId, String messageId) {
        Message message = messageRepository.findById(messageId).orElseThrow(MessageNotFoundException::new);
        if (!message.getChatRoom().getId().equals(chatRoomId)) {
            throw new MessageNotFoundException();
        }
        return message;
    }

    public record BranchNode(Message message, int siblingIndex, int siblingCount, List<String> siblingIds) {

    }

}
