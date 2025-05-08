package com.taskmanagementsystem.auth;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmanagementsystem.DTO.CreateUserRequest;
import com.taskmanagementsystem.entities.Users;
import com.taskmanagementsystem.util.DynamoDBUtil;
import com.taskmanagementsystem.util.PasswordGenerator;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

public class CreateUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
            .region(Region.of(System.getenv("REGION")))
            .build();

    private final DynamoDBMapper dynamoDBMapper;
    
    private final SfnClient sfnClient = SfnClient.builder()
            .region(Region.of(System.getenv("REGION")))
            .build();
    
    private final String userPoolId = System.getenv("USER_POOL_ID");
    private final String userTableName = System.getenv("USER_TABLE");
    private final String userOnboardingStateMachineArn = System.getenv("USER_ONBOARDING_STATE_MACHINE_ARN");
    
    public CreateUserHandler() {
        // Initialize DynamoDBMapper
        DynamoDBMapperConfig mapperConfig = DynamoDBMapperConfig.builder()
        .withTableNameOverride(DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(userTableName))
        .build();

        dynamoDBMapper = new DynamoDBMapper(DynamoDBUtil.getDynamoDBClient(), mapperConfig);
    }
    
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        
        try {

            //Check if authorized user is an admin
               
            Map<String, Object> authorizer = input.getRequestContext().getAuthorizer();
            @SuppressWarnings("unchecked")
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String role = claims.get("custom:role");
            if(!"admin".equals((role))) {
                return createErrorResponse(403, "Only admin can add user");
            }

            // Parse request body
            CreateUserRequest createUserRequest = objectMapper.readValue(input.getBody(), CreateUserRequest.class);
            
            // Validate request
            if (createUserRequest.getEmail() == null || createUserRequest.getEmail().isEmpty()) {
                return createErrorResponse(400, "Email is required");
            }
            
            if (createUserRequest.getRole() == null || 
                    (!createUserRequest.getRole().equals("admin") && !createUserRequest.getRole().equals("user"))) {
                return createErrorResponse(400, "Role must be either 'admin' or 'user'");
            }
            
            // Generate a temporary password
            String temporaryPassword = PasswordGenerator.generatePassword();
            
            // Create the user in Cognito
            createCognitoUser(createUserRequest, temporaryPassword);
            
            // Store user information in DynamoDB using the Users entity
            Users user = storeUserInDynamoDB(createUserRequest);
            String userId = user.getUserId();

            // Start onboarding workflow to set up notifications
            startOnboardingWorkflow(userId, createUserRequest);
            
            // Return success response
            Map<String, Object> userResponse = new HashMap<>();
            userResponse.put("userId", userId);
            userResponse.put("email", createUserRequest.getEmail());
            userResponse.put("role", createUserRequest.getRole());
            
            response.setStatusCode(201);
            response.setBody(objectMapper.writeValueAsString(userResponse));
            
            // Add CORS headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "POST, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Amz-Date,X-Api-Key");
            response.setHeaders(headers);

        } catch (UserNotFoundException e) {
            context.getLogger().log("User not found: " + e.getMessage());
            return createErrorResponse(404, "Admin User not found");
        } catch (UsernameExistsException e) {
            context.getLogger().log("Username already exists: " + e.getMessage());
            return createErrorResponse(409, "User with this email already exists");
        } catch (Exception e) {
            context.getLogger().log("Error creating user: " + e.getMessage());
            return createErrorResponse(500,  e.getMessage());
        }

        return response;
    }




    private AdminCreateUserResponse createCognitoUser(CreateUserRequest request, String temporaryPassword) {
        // Create Cognito user attributes
        AttributeType emailAttr = AttributeType.builder()
                .name("email")
                .value(request.getEmail())
                .build();

        AttributeType emailVerifiedAttr = AttributeType.builder()
                .name("email_verified")
                .value("true")
                .build();

        AttributeType roleAttr = AttributeType.builder()
                .name("custom:role")
                .value(request.getRole())
                .build();

        // Build the create user request
        AdminCreateUserRequest createRequest = AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(request.getEmail())
                .temporaryPassword(temporaryPassword)
                .userAttributes(emailAttr, emailVerifiedAttr, roleAttr)
                .build();

        // Create the user
        return cognitoClient.adminCreateUser(createRequest);
    }

    private Users storeUserInDynamoDB(CreateUserRequest request) {
        // Create and populate Users entity
        Users user = new Users();
        user.setEmail(request.getEmail());
        user.setCognitoUsername(request.getEmail());
        user.setUsername(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setAdmin("admin".equals(request.getRole()));
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());

        // Save the user to DynamoDB
        dynamoDBMapper.save(user);

        return user;
    }

    private void startOnboardingWorkflow(String userId, CreateUserRequest request) {
        try {
            // Create input for Step Function
            Map<String, String> stepFunctionInput = new HashMap<>();
            stepFunctionInput.put("userId", userId);
            stepFunctionInput.put("email", request.getEmail());
            stepFunctionInput.put("role", request.getRole());

            // Start execution
            StartExecutionRequest startExecutionRequest = StartExecutionRequest.builder()
                .stateMachineArn(userOnboardingStateMachineArn)
                .input(objectMapper.writeValueAsString(stepFunctionInput))
                .build();

            sfnClient.startExecution(startExecutionRequest);
        } catch (Exception e) {
            // Log error but don't fail the request
            System.err.println("Error starting onboarding workflow: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);

        try {
            Map<String, String> errorBody = new HashMap<>();
            errorBody.put("message", message);
            response.setBody(objectMapper.writeValueAsString(errorBody));
        } catch (Exception e) {
            // Fallback if JSON serialization fails
            response.setBody("{\"message\":\"" + message + "\"}");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        response.setHeaders(headers);
        
        return response;
    }
    
}