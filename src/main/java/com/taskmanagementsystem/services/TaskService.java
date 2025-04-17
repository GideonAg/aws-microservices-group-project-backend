package com.taskmanagementsystem.services;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.util.DynamoDBUtil;

import java.util.HashMap;
import java.util.Map;

public class TaskService {

    private final AmazonDynamoDB dynamoDBClient;
    private final String tableName;

    public TaskService() {
        this.dynamoDBClient = DynamoDBUtil.getDynamoDBClient();
        this.tableName = System.getenv("TASK_TABLE");
    }

    public Tasks getTask(String taskId) {
        // Validate inputs
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId cannot be null or empty");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalStateException("Task table name not configured");
        }

        // Construct the GetItem request
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", new AttributeValue().withS(taskId));

        GetItemRequest request = new GetItemRequest()
                .withTableName(tableName)
                .withKey(key);

        // Execute the GetItem request
        GetItemResult result = dynamoDBClient.getItem(request);
        Map<String, AttributeValue> item = result.getItem();

        // If no item is found, return null
        if (item == null) {
            return null;
        }

        // Map the DynamoDB item to a Tasks object
        Tasks task = new Tasks();
        task.setTaskId(item.get("taskId").getS());
        task.setName(item.get("name") != null ? item.get("name").getS() : null);
        task.setDescription(item.get("description") != null ? item.get("description").getS() : null);
        task.setStatus(item.get("status") != null ? item.get("status").getS() : null);
        task.setDeadline(item.get("deadline") != null ? Long.parseLong(item.get("deadline").getN()) : null);
        task.setResponsibility(item.get("responsibility") != null ? item.get("responsibility").getS() : null);
        task.setAssignedUserEmail(item.get("assignedUserEmail") != null ? item.get("assignedUserEmail").getS() : null);
        task.setCompletedAt(item.get("completedAt") != null ? Long.parseLong(item.get("completedAt").getN()) : null);
        task.setUserComment(item.get("userComment") != null ? item.get("userComment").getS() : null);
        task.setCreatedBy(item.get("createdBy") != null ? item.get("createdBy").getS() : null);
        task.setCreatedAt(item.get("createdAt") != null ? Long.parseLong(item.get("createdAt").getN()) : null);
        task.setUpdatedAt(item.get("updatedAt") != null ? Long.parseLong(item.get("updatedAt").getN()) : null);

        return task;
    }
}

