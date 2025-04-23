package com.taskmanagementsystem.util;

public class TaskAssignmentMessage {
    private String taskId;
    private String userId;

    // Default constructor needed for deserialization
    public TaskAssignmentMessage() {
    }

    public TaskAssignmentMessage(String taskId, String userId) {
        this.taskId = taskId;
        this.userId = userId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
