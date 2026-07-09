package com.example.springaichat.chat.entity;

import com.example.springaichat.common.entity.BaseEntity;
import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "chat_rooms")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SQLDelete(sql = "UPDATE chat_rooms SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at is null")
public class ChatRoom extends BaseEntity {

	@Id
	@Tsid
	@Column(length = 13)
	private String id;

	@Column(nullable = false)
	private String title;

	public void updateTitle(String title) {
		this.title = title;
	}

}
