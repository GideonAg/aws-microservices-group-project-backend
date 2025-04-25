package com.taskmanagementsystem.util;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.taskmanagementsystem.entities.Tasks;

import java.util.HashMap;
import java.util.Map;

public class TasksMapper {

    public static Tasks fromItem(Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }

        return Tasks.builder()
                .taskId(item.getOrDefault("taskId", new AttributeValue().withS("")).getS())
                .name(item.getOrDefault("name", new AttributeValue().withS("")).getS())
                .description(item.getOrDefault("description", new AttributeValue().withS("")).getS())
                .status(item.getOrDefault("status", new AttributeValue().withS("")).getS())
                .deadline(item.containsKey("deadline") ? Long.parseLong(item.get("deadline").getN()) : null)
                .responsibility(item.getOrDefault("responsibility", new AttributeValue().withS("")).getS())
                .assignedUserEmail(item.getOrDefault("assignedUserEmail", new AttributeValue().withS("")).getS())
                .completedAt(item.containsKey("completedAt") ? Long.parseLong(item.get("completedAt").getN()) : null)
                .userComment(item.getOrDefault("userComment", new AttributeValue().withS("")).getS())
                .createdBy(item.getOrDefault("createdBy", new AttributeValue().withS("")).getS())
                .createdAt(item.containsKey("createdAt") ? Long.parseLong(item.get("createdAt").getN()) : null)
                .updatedAt(item.containsKey("updatedAt") ? Long.parseLong(item.get("updatedAt").getN()) : null)
                .build();
    }

    public static Map<String, AttributeValue> toItem(Tasks task) {
        Map<String, AttributeValue> item = new HashMap<>();

        if (task.getTaskId() != null) item.put("taskId", new AttributeValue().withS(task.getTaskId()));
        if (task.getName() != null) item.put("name", new AttributeValue().withS(task.getName()));
        if (task.getDescription() != null) item.put("description", new AttributeValue().withS(task.getDescription()));
        if (task.getStatus() != null) item.put("status", new AttributeValue().withS(task.getStatus()));
        if (task.getDeadline() != null) item.put("deadline", new AttributeValue().withN(String.valueOf(task.getDeadline())));
        if (task.getResponsibility() != null) item.put("responsibility", new AttributeValue().withS(task.getResponsibility()));
        if (task.getAssignedUserEmail() != null) item.put("assignedUserEmail", new AttributeValue().withS(task.getAssignedUserEmail()));
        if (task.getCompletedAt() != null) item.put("completedAt", new AttributeValue().withN(String.valueOf(task.getCompletedAt())));
        if (task.getUserComment() != null) item.put("userComment", new AttributeValue().withS(task.getUserComment()));
        if (task.getCreatedBy() != null) item.put("createdBy", new AttributeValue().withS(task.getCreatedBy()));
        if (task.getCreatedAt() != null) item.put("createdAt", new AttributeValue().withN(String.valueOf(task.getCreatedAt())));
        if (task.getUpdatedAt() != null) item.put("updatedAt", new AttributeValue().withN(String.valueOf(task.getUpdatedAt())));

        return item;
    }
}