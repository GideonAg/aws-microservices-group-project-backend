package com.taskmanagementsystem.auth;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;

import java.util.Map;

import static software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType.USER_PASSWORD_AUTH;

public class AuthenticationService {
    private final String CLIENT_ID = System.getenv("USER_POOL_CLIENT_ID");
    private final String REGION = System.getenv("REGION");

    public Map<String, String> login(String email, String password) {
        var cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(REGION))
                .build();

        var authParams = Map.of(
                "USERNAME", email,
                "PASSWORD", password
        );

        var authRequest = InitiateAuthRequest.builder()
                .clientId(CLIENT_ID)
                .authFlow(USER_PASSWORD_AUTH)
                .authParameters(authParams)
                .build();

        var authResponse = cognitoClient.initiateAuth(authRequest);
        return Map.of(
                "message", "Login successful",
                "accessToken", authResponse.authenticationResult().accessToken(),
                "refreshToken", authResponse.authenticationResult().refreshToken(),
                "idToken", authResponse.authenticationResult().idToken()
        );
    }
}
