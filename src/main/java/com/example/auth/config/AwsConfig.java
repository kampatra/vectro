package com.example.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * AWS SDK v2 client beans.
 *
 * Credentials are resolved automatically via the DefaultCredentialsProvider chain:
 *   1. Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY) – local dev
 *   2. ~/.aws/credentials – local dev
 *   3. EKS Pod Identity / IRSA (IAM Role for Service Accounts) – production on EKS
 *
 * No static credentials are embedded in code or configuration.
 * On EKS, annotate the Kubernetes ServiceAccount with the IAM role ARN and
 * the SDK will pick up the credentials automatically from the pod's metadata endpoint.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Bean
    public CognitoIdentityProviderClient cognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                // Picks up IRSA / Pod Identity credentials on EKS automatically;
                // falls back to env vars or ~/.aws/credentials for local development.
                .credentialsProvider(DefaultCredentialsProvider.create())
                // Lightweight URL-connection HTTP client — no Netty required
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
}
