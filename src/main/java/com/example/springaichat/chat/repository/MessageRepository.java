package com.example.springaichat.chat.repository;

import com.example.springaichat.chat.entity.Message;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, String>, MessageRepositoryCustom {

    boolean existsByChatRoomId(String chatRoomId);

    List<Message> findByChatRoomIdOrderByIdAsc(String chatRoomId);

    List<Message> findByParentMessageId(String parentMessageId);

    List<Message> findByChatRoomIdAndParentMessageIsNull(String chatRoomId);

}
