package com.example.springaichat.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springaichat.chat.entity.ChatRoom;
import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import com.example.springaichat.common.config.JpaAuditingConfig;
import com.example.springaichat.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class MessageRepositoryCustomImplTest {

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    @DisplayName("findAncestorChain은 조상이 많아도 limit개까지만 리프부터 역순으로 반환한다")
    void findAncestorChain_boundedAndOrderedFromLeaf() {
        ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("대화").build());
        Message parent = null;
        Message leaf = null;
        for (int i = 0; i < 25; i++) {
            leaf = messageRepository.save(Message.builder()
                .chatRoom(chatRoom)
                .role(MessageRole.USER)
                .content("메시지-" + i)
                .parentMessage(parent)
                .build());
            parent = leaf;
        }

        List<Message> result = messageRepository.findAncestorChain(leaf.getId(), 20);

        assertThat(result).hasSize(20);
        assertThat(result.get(0).getContent()).isEqualTo("메시지-24");
        assertThat(result.get(19).getContent()).isEqualTo("메시지-5");
    }

    @Test
    @DisplayName("findAncestorChain은 조상 수가 limit보다 적으면 전부 반환하고 루트의 parentMessage는 null이다")
    void findAncestorChain_shorterThanLimit_returnsAll() {
        ChatRoom chatRoom = chatRoomRepository.save(ChatRoom.builder().title("대화").build());
        Message root = messageRepository.save(Message.builder()
            .chatRoom(chatRoom).role(MessageRole.USER).content("메시지-0").build());
        Message child = messageRepository.save(Message.builder()
            .chatRoom(chatRoom).role(MessageRole.ASSISTANT).content("메시지-1").parentMessage(root).build());

        List<Message> result = messageRepository.findAncestorChain(child.getId(), 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("메시지-1");
        assertThat(result.get(1).getContent()).isEqualTo("메시지-0");
        assertThat(result.get(1).getParentMessage()).isNull();
    }

}
