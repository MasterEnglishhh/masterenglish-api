package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class R2SmokeCheckTests {
    @Test
    void roundTripChecksContentAndDeletesOnlyItsGeneratedObject() {
        var client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), R2SmokeCheck.CONTENT));
        String key = R2SmokeCheck.verifyStorage(client, "fixture-bucket");
        assertThat(key).startsWith("_checks/").endsWith(".txt");
        verify(client)
                .deleteObject(
                        DeleteObjectRequest.builder().bucket("fixture-bucket").key(key).build());
    }

    @Test
    void corruptedDownloadFailsAndStillCleansUp() {
        var client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), new byte[] {1}));
        assertThatThrownBy(() -> R2SmokeCheck.verifyStorage(client, "fixture-bucket"))
                .isInstanceOf(IllegalStateException.class);
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void cleanupFailureDoesNotReportSuccessfulStorageCheck() {
        var client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), R2SmokeCheck.CONTENT));
        doThrow(new IllegalStateException("fixture cleanup failure"))
                .when(client)
                .deleteObject(any(DeleteObjectRequest.class));
        assertThatThrownBy(() -> R2SmokeCheck.verifyStorage(client, "fixture-bucket"))
                .isInstanceOf(IllegalStateException.class);
    }
}
