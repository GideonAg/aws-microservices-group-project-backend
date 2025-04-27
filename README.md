# Field Team Task Management System Documentation

## Project Overview

The Field Team Task Management System is a serverless application built on AWS services that enables efficient task assignment, tracking,
and notifications for field teams. The system provides separate interfaces for administrators and team members, facilitating task management and team coordination.

Administrators can create tasks, assign them to specific team members, manage deadlines, and monitor task status. 
Team members can view their assigned tasks, update task status, and add comments. The system includes comprehensive notification features that keep all users informed about relevant task events.

This documentation provides a detailed breakdown of the system architecture, components, workflows, and implementation guidelines.

## Architecture Overview

The system utilizes AWS serverless services to create a scalable, reliable, and cost-effective task management solution:

- **Amazon Cognito**: Handles user authentication, authorization, and user management
- **AWS Lambda**: Processes business logic for task operations
- **Amazon DynamoDB**: Stores task and user data
- **Amazon SNS**: Manages notification delivery
- **Amazon SQS**: Ensures reliable task processing with retry capabilities
- **AWS Step Functions**: Orchestrates complex workflows and parallel executions
- **Amazon EventBridge**: Schedules deadline notifications
- **Amazon API Gateway**: Provides RESTful API endpoints

## Core Components

### 1. User Management System

#### Authentication and Authorization (Amazon Cognito)

The system uses Amazon Cognito for secure user authentication and role-based access control:

- **User Pools**: Manages user accounts, authentication, and authorization
- **Identity Pools**: Provides temporary AWS credentials for accessing backend resources

#### User Onboarding Flow

```
1. Administrator creates new user account in Cognito User Pool
    Request-Body:
    {
    "email": "juliusagbame@gmail.com",
    "role": "user",
    "firstName": "John",
    "lastName": "Doe"
    }
    
    Response-Boby: 201 Created
    {
    "role": "user",
    "userId": "47ede433-1e40-4b5e-95a4-3e667b0d46df",
    "email": "johndoe@gmail.com"
    }
2. Step Function initiates parallel processes:
   - User receives welcome email with login link and temporary password
   - User email is subscribed to required SNS topics:
     * TaskAssignmentNotificationTopic
     * TaskDeadlineNotificationTopic
     * ClosedTaskNotificationTopic
3. User completes first-time login and sets permanent password
```

### 2. Task Management System

#### Task Data Model (DynamoDB)

Tasks are stored in DynamoDB with the following attributes:

| Attribute       | Type      | Description                                    |
|-----------------|-----------|------------------------------------------------|
| taskId          | String    | Unique identifier for the task                 |
| name            | String    | Short name/title of the task                   |
| description     | String    | Detailed task description                      |
| status          | String    | Current task status (open, completed, expired) |
| deadline        | Timestamp | Task completion deadline                       |
| responsibility  | String    | ID of assigned team member                     |
| created_at      | Timestamp | Task creation timestamp                        |
| created_by      | String    | Email of admin who created the task            |
| completed_at    | Timestamp | When task was marked complete (if applicable)  |
| user_comment    | String    | Comments provided by assigned team member      |

#### Task Operations

The system supports the following task operations:

- **Create Task** (Admin only): Creates a new task and assigns it to a team member
- **Assign/Reassign Task** (Admin only): Changes the team member responsible for a task
- **Update Task Status** (Team Member): Updates task status to "completed"
- **Add Comment** (Team Member): Adds comments to assigned tasks
- **View Tasks** (All users): Different views based on user role
- **Reopen Task** (Admin only): Reopens an expired or completed task

### 3. Notification System

#### SNS Topics

The system uses multiple SNS topics for targeted notifications:

1. **TaskAssignmentNotificationTopic**: Notifies team members of new task assignments
2. **TaskDeadlineNotificationTopic**: Sends deadline reminders to team members
3. **ClosedTaskNotificationTopic**: Notifies about expired tasks
4. **TaskCompleteNotificationTopic**: Notifies admins when tasks are completed

#### Notification Filtering

SNS message filtering ensures users only receive relevant notifications:

- Team members receive notifications only for tasks assigned to them
- Admins receive notifications for task completions and expirations
- Email notifications contain relevant task details and action links

### 4. Task Processing and Queueing System

#### Task Assignment Queue (SQS FIFO Queue)

New task assignments follow a reliable processing workflow:

```
1. Admin creates task → Task data sent to SQS FIFO Queue
2. Lambda processes queue messages and updates DynamoDB
3. Failed processing attempts move to Dead Letter Queue
4. Successful processing triggers SNS notification to assigned user
```

#### Task Deadline Management

```
1. EventBridge Scheduler creates events for task deadlines
2. One hour before deadline: 
   - Lambda function sends reminder via TaskDeadlineNotificationTopic
3. At deadline (if task not completed):
   - Task added to Expired Tasks SQS Queue
   - Step Function initiates parallel processes:
     * Update task status to "expired" in DynamoDB
     * Notify team member and admin via ClosedTaskNotificationTopic
```

## Workflow Diagrams

### User Onboarding Workflow

```
Admin Portal → Create Onboards a User → Step Function
                                               ├─→ Subscribe to TaskAssignmentNotificationTopic
                                               ├─→ Subscribe to TaskDeadlineNotificationTopic
                                               ├─→ Subscribe to ClosedTaskNotificationTopic
                                               └─→ Send Welcome Email with Credentials
```

### Task Creation and Assignment Workflow

```
Admin Portal → Create Task API → SQS FIFO Queue → Lambda Function
                                                          ├─→ Save to DynamoDB
                                                          └─→ SNS Notification
                                                                └─→ Email to Assigned Member
```

### Task Deadline Workflow

```
Task Creation → EventBridge Scheduler
                    ├─→ 1-hour Reminder
                    │     └─→ SNS Notification to Team Member
                    │
                    └─→ Deadline Event → SQS Queue → Step Function
                                                           ├─→ Update DynamoDB Status
                                                           └─→ SNS Notification to Member & Admin
```

### Task Status Update Workflow

```
Team Member Portal → Update Task API → Lambda Function
                                             ├─→ Update DynamoDB
                                             └─→ If status="completed"
                                                   └─→ SNS Notification to Admin
```

### Task Reassignment Workflow

```
Admin Portal → Reassign Task API → Lambda Function
                                         ├─→ Update DynamoDB
                                         └─→ SNS Notification to New Assignee
```

## Implementation Details

### DynamoDB Table Structure

#### Tasks Table

Primary Key: `taskId` (String)
Global Secondary Indexes:
- `responsibility-index`: Enables efficient querying of tasks by assigned team member
- `status-index`: Facilitates filtering tasks by status

#### Sample DynamoDB Item

```json
{
  "taskId": "task-123456",
  "name": "Inspect North Site Equipment",
  "description": "Perform routine maintenance inspection of equipment at North Site location",
  "status": "open",
  "deadline": "2025-05-10T17:00:00Z",
  "responsibility": "user-789012",
  "created_at": "2025-04-20T09:30:00Z",
  "created_by": "johndoe@gmail.com",
  "completed_at": null,
  "user_comment": ""
}
```

### Lambda Functions

#### Core Lambda Functions

1. **CreateTaskFunction**
    - Validates task data
    - Sends task to SQS FIFO queue
    - Returns success response to API

2. **ProcessTaskQueueFunction**
    - Triggered by SQS FIFO queue
    - Writes task data to DynamoDB
    - Publishes to TaskAssignmentNotificationTopic with team member filter

3. **UpdateTaskStatusFunction**
    - Updates task status and comments in DynamoDB
    - If status changes to "completed", sends notification to admin

4. **ProcessExpiredTasksFunction**
    - Triggered by Expired Tasks SQS Queue
    - Invokes Step Function for expired task processing

5. **DeadlineReminderFunction**
    - Triggered by EventBridge scheduled events
    - Sends reminder notifications via SNS

#### Supporting Lambda Functions

1. **UserOnboardingFunction**
    - Integrated with Cognito post-confirmation trigger
    - Starts Step Function for parallel SNS subscriptions

2. **ReassignTaskFunction**
    - Updates task responsibility in DynamoDB
    - Sends notification to new assignee

3. **ReopenTaskFunction**
    - Updates task status from "expired"/"completed" to "open"
    - Optionally reassigns task and notifies new assignee



### SNS Message Filtering

#### Task Assignment Topic Subscription Filter Policy

```json
{
  "assigneeId": [
    "${user_id}"
  ]
}
```

#### Expired Task Topic Subscription Filter Policy

For team members:
```json
{
  "assigneeId": [
    "${user_id}"
  ]
}
```

For admins:
```json
{
  "assigneeId": [
    "*"
  ]
}
```

### API Gateway Endpoints

| HTTP Method | Endpoint                 | Description                     | Access         |
|-------------|--------------------------|---------------------------------|----------------|
| POST        | /tasks                   | Create new task                 | Admin          |
| GET         | /tasks                   | List all tasks                  | Admin          |
| GET         | /tasks                   | List user's tasks               | Team Member    |
| GET         | /tasks/{taskId}          | Get task details                | All (filtered) |
| PUT         | /tasks/{taskId}          | Update task (admin)             | Admin          |
| PATCH       | /tasks/{taskId}          | Update task status and comments | Team Member    |
| POST        | /tasks/{taskId}/reassign | Reassign task to another user   | Admin          |
| POST        | /tasks/{taskId}/reopen   | Reopen expired task             | Admin          |

### Security Implementation

#### API Authorization

API Gateway endpoints are secured using Cognito Authorizers that validate JWT tokens and enforce role-based access:

```
API Gateway → Cognito Authorizer → Lambda → DynamoDB
```

#### Fine-grained Access Control

- Team members can only view and update their own tasks status.
- Admins can view and manage all tasks.
- IAM roles enforce least-privilege access for Lambda functions.

## Testing Guide

### Testing User Onboarding

1. Create a new user in Cognito console.
2. Verify user receives welcome email with login link.
3. Confirm SNS topic subscriptions in user's email.
4. Test user login with temporary credentials.

### Testing Task Management

1. Create tasks with different deadlines
2. Verify assigned team member receives notification
3. Test task updates by team member
4. Verify admin receives completion notifications
5. Test task expiration workflow by setting a near-future deadline
6. Verify expired task notifications
7. Test task reassignment flow

### Testing Deadline Notifications

1. Create a task with deadline 1 hour in the future
2. Verify reminder notification is sent
3. Let task expire and verify expiration workflow

## Deployment Guide

### SAM Template Structure

The system can be deployed using AWS SAM. Key resource sections include:

- Cognito User Pool and Identity Pool configuration
- DynamoDB Tables with indexes
- SNS Topics with subscription filter policies
- SQS Queues with dead-letter configurations
- Lambda Functions with IAM roles
- EventBridge Scheduler rules
- Step Function State Machines
- API Gateway configuration



## Front-End Integration

### Authentication Flow

1. Implement Cognito authentication using Amplify
2. Handle first-time login and password change requirements
3. Store and refresh JWT tokens for API access

### Admin Dashboard Features

- Task creation form with team member assignment
- Task detail view with history and comments
- Team member management interface

### Team Member Dashboard Features

- View assigned tasks with deadline indicators
- Update task status and add comments
- View task history and details


## System Maintenance and Monitoring

### Monitoring Strategy

- CloudWatch Dashboards for system metrics


### Key Metrics to Monitor

- SQS queue depth and processing time
- Lambda function errors and timeouts
- API Gateway latency and error rates
- DynamoDB throughput consumption

### Common Issues and Troubleshooting

1. **SQS Dead Letter Queue Messages**
    - Check CloudWatch Logs for processing errors
    - Verify Lambda function permissions

2. **Missing Notifications**
    - Verify SNS subscription status
    - Check message filter policies
    - Review CloudWatch Logs for delivery issues

3. **Task Processing Delays**
    - Monitor SQS queue metrics
    - Check Lambda concurrency limits

## Conclusion

The Field Team Task Management System provides a comprehensive solution for managing field teams through AWS serverless architecture. The system's key strengths are:

1. **Reliability**: Task processing with retry mechanisms
2. **Scalability**: Serverless architecture adapts to team size
3. **Security**: Fine-grained access control and authentication
4. **Automation**: Notifications and deadline tracking
5. **Flexibility**: Easily customizable for specific team needs

By leveraging AWS services like Cognito, Lambda, DynamoDB, SNS, SQS, and Step Functions, the system delivers a robust task management solution that ensures field teams stay coordinated and productive.