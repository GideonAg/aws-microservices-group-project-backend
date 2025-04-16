package com.taskmanagementsystem.util;

import com.taskmanagementsystem.entities.Tasks;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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
}
