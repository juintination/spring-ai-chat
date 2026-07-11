package com.example.springaichat.chat.repository;

import com.example.springaichat.chat.entity.Message;
import com.example.springaichat.chat.entity.MessageRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import java.util.List;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaRoot;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepositoryCustomImpl implements MessageRepositoryCustom {

    private static final String ID = "id";
    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String PARENT_MESSAGE = "parentMessage";
    private static final String PARENT_ID = "parentId";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Message> findAncestorChain(String messageId, int limit) {
        HibernateCriteriaBuilder builder = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();

        JpaCriteriaQuery<Tuple> baseQuery = builder.createTupleQuery();
        JpaRoot<Message> baseRoot = baseQuery.from(Message.class);
        baseQuery.select(builder.tuple(
            baseRoot.get(ID).alias(ID),
            baseRoot.get(ROLE).alias(ROLE),
            baseRoot.get(CONTENT).alias(CONTENT),
            baseRoot.get(PARENT_MESSAGE).get(ID).alias(PARENT_ID)));
        baseQuery.where(builder.equal(baseRoot.get(ID), messageId));

        JpaCriteriaQuery<Tuple> query = builder.createTupleQuery();
        JpaCteCriteria<Tuple> ancestorChain = query.withRecursiveUnionAll(baseQuery, cte -> {
            JpaCriteriaQuery<Tuple> recursiveQuery = builder.createTupleQuery();
            JpaRoot<Message> messageRoot = recursiveQuery.from(Message.class);
            JpaRoot<Tuple> cteRoot = recursiveQuery.from(cte);
            recursiveQuery.select(builder.tuple(
                messageRoot.get(ID).alias(ID),
                messageRoot.get(ROLE).alias(ROLE),
                messageRoot.get(CONTENT).alias(CONTENT),
                messageRoot.get(PARENT_MESSAGE).get(ID).alias(PARENT_ID)));
            recursiveQuery.where(builder.equal(messageRoot.get(ID), cteRoot.get(PARENT_ID)));
            return recursiveQuery;
        });

        JpaRoot<Tuple> root = query.from(ancestorChain);
        query.select(builder.tuple(
            root.get(ID).alias(ID),
            root.get(ROLE).alias(ROLE),
            root.get(CONTENT).alias(CONTENT),
            root.get(PARENT_ID).alias(PARENT_ID)));

        List<Tuple> rows = entityManager.createQuery(query).setMaxResults(limit).getResultList();
        return rows.stream().map(this::toMessage).toList();
    }

    private Message toMessage(Tuple row) {
        String parentId = row.get(PARENT_ID, String.class);
        Message parent = (parentId == null) ? null : Message.builder().id(parentId).build();
        return Message.builder()
            .id(row.get(ID, String.class))
            .role(row.get(ROLE, MessageRole.class))
            .content(row.get(CONTENT, String.class))
            .parentMessage(parent)
            .build();
    }

}
