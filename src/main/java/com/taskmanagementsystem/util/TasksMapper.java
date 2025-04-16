package com.taskmanagementsystem.util;

import com.taskmanagementsystem.entities.Tasks;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public class TasksMapper {

    public static Tasks fromItem(Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }

        return Tasks.builder()
                .taskId(item.getOrDefault("taskId", AttributeValue.fromS("")).s())
                .name(item.getOrDefault("name", AttributeValue.fromS("")).s())
                .description(item.getOrDefault("description", AttributeValue.fromS("")).s())
                .status(item.getOrDefault("status", AttributeValue.fromS("")).s())
                .deadline(item.containsKey("deadline") ? Long.parseLong(item.get("deadline").n()) : null)
                .responsibility(item.getOrDefault("responsibility", AttributeValue.fromS("")).s())
                .assignedUserEmail(item.getOrDefault("assignedUserEmail", AttributeValue.fromS("")).s())
                .completedAt(item.containsKey("completedAt") ? Long.parseLong(item.get("completedAt").n()) : null)
                .userComment(item.getOrDefault("userComment", AttributeValue.fromS("")).s())
                .createdBy(item.getOrDefault("createdBy", AttributeValue.fromS("")).s())
                .createdAt(item.containsKey("createdAt") ? Long.parseLong(item.get("createdAt").n()) : null)
                .updatedAt(item.containsKey("updatedAt") ? Long.parseLong(item.get("updatedAt").n()) : null)
                .build();
    }

    public static Map<String, AttributeValue> toItem(Tasks task) {
        Map<String, AttributeValue> item = new HashMap<>();

        if (task.getTaskId() != null) item.put("taskId", AttributeValue.builder().s(task.getTaskId()).build());
        if (task.getName() != null) item.put("name", AttributeValue.builder().s(task.getName()).build());
        if (task.getDescription() != null) item.put("description", AttributeValue.builder().s(task.getDescription()).build());
        if (task.getStatus() != null) item.put("status", AttributeValue.builder().s(task.getStatus()).build());
        if (task.getDeadline() != null) item.put("deadline", AttributeValue.builder().n(String.valueOf(task.getDeadline())).build());
        if (task.getResponsibility() != null) item.put("responsibility", AttributeValue.builder().s(task.getResponsibility()).build());
        if (task.getAssignedUserEmail() != null) item.put("assignedUserEmail", AttributeValue.builder().s(task.getAssignedUserEmail()).build());
        if (task.getCompletedAt() != null) item.put("completedAt", AttributeValue.builder().n(String.valueOf(task.getCompletedAt())).build());
        if (task.getUserComment() != null) item.put("userComment", AttributeValue.builder().s(task.getUserComment()).build());
        if (task.getCreatedBy() != null) item.put("createdBy", AttributeValue.builder().s(task.getCreatedBy()).build());
        if (task.getCreatedAt() != null) item.put("createdAt", AttributeValue.builder().n(String.valueOf(task.getCreatedAt())).build());
        if (task.getUpdatedAt() != null) item.put("updatedAt", AttributeValue.builder().n(String.valueOf(task.getUpdatedAt())).build());

        return item;
    }

}
