package com.taskmanagementsystem.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.taskmanagementsystem.entities.Tasks;
import com.taskmanagementsystem.util.DynamoDBUtil;

public class TaskService {

    private final AmazonDynamoDB dynamoDBClient;
    private final String tableName;

    public TaskService() {
        this.dynamoDBClient = DynamoDBUtil.getDynamoDBClient();
        this.tableName = System.getenv("TASK_TABLE");
    }

    public Tasks getTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId cannot be null or empty");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalStateException("Task table name not configured");
        }

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", new AttributeValue().withS(taskId));

        GetItemRequest request = new GetItemRequest()
                .withTableName(tableName)
                .withKey(key);

        GetItemResult result = dynamoDBClient.getItem(request);
        Map<String, AttributeValue> item = result.getItem();

        if (item == null) {
            return null;
        }

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

    public List<Tasks> getIncompleteTasks() {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalStateException("Task table name not configured");
        }

        Map<String, Condition> scanFilter = new HashMap<>();
        scanFilter.put("status", new Condition()
                .withComparisonOperator(ComparisonOperator.NE)
                .withAttributeValueList(new AttributeValue().withS("completed")));

        ScanRequest scanRequest = new ScanRequest()
                .withTableName(tableName)
                .withScanFilter(scanFilter);

        ScanResult result = dynamoDBClient.scan(scanRequest);

        List<Tasks> tasksList = new ArrayList<>();
        for (Map<String, AttributeValue> item : result.getItems()) {
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

            tasksList.add(task);
        }

        return tasksList;
    }

    public void updateTaskStatusToExpired(String taskId) {
        long now = Instant.now().toEpochMilli();

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("taskId", new AttributeValue().withS(taskId));

        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#s", "status");

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":status", new AttributeValue().withS("expired"));
        expressionValues.put(":updatedAt", new AttributeValue().withN(String.valueOf(now)));

        UpdateItemRequest updateRequest = new UpdateItemRequest()
                .withTableName(tableName)
                .withKey(key)
                .withUpdateExpression("SET #s = :status, updatedAt = :updatedAt")
                .withExpressionAttributeNames(expressionNames)
                .withExpressionAttributeValues(expressionValues);

        this.dynamoDBClient.updateItem(updateRequest);
    }
}
