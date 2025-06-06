package com.taskmanagementsystem.entities;

import com.amazonaws.services.dynamodbv2.datamodeling.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamoDBTable(tableName = "")
public class Users {

    static {
        String tableName = System.getenv("USER_TABLE");
        if (tableName != null && !tableName.isEmpty()) {
            DynamoDBMapperConfig.Builder builder = DynamoDBMapperConfig.builder();
            builder.setTableNameOverride(DynamoDBMapperConfig.TableNameOverride.withTableNameReplacement(tableName));
            builder.build();
        }
    }

    @DynamoDBHashKey
    @DynamoDBAutoGeneratedKey
    private String userId;

    @DynamoDBAttribute
    private String username;

    @DynamoDBAttribute
    private String email;

    @DynamoDBAttribute
    private String cognitoUsername;

    @DynamoDBAttribute
    private String firstName;

    @DynamoDBAttribute
    private String lastName;

    @DynamoDBAttribute
    private boolean isAdmin;

    @DynamoDBAttribute
    private Long createdAt;

    @DynamoDBAttribute
    private Long updatedAt;


}
