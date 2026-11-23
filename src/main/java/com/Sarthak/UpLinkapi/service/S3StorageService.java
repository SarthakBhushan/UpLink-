package com.Sarthak.UpLinkapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket:uplink-bucket}")
    private String bucketName;

    /**
     * Generates a temporary pre-signed PUT URL for direct client-to-S3 uploads.
     */
    public String generatePresignedUploadUrl(String s3Key, String contentType, Duration duration) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(objectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    /**
     * Generates a temporary pre-signed GET URL for secure, direct file downloads.
     */
    public String generatePresignedDownloadUrl(String s3Key, String originalFilename, Duration duration) {
        String contentDisposition = "attachment; filename=\"" + originalFilename + "\"";

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .responseContentDisposition(contentDisposition)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Retrieves an input stream for server-side processing of an S3 object.
     */
    public java.io.InputStream getObjectInputStream(String s3Key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
        return s3Client.getObject(getObjectRequest);
    }

    /**
     * Verifies that the client actually uploaded the object to S3.
     */
    public boolean doesObjectExist(String s3Key) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.headObject(headObjectRequest);
            return true;
        } catch (NoSuchKeyException e) {
            log.warn("Object with key {} does not exist in bucket {}", s3Key, bucketName);
            return false;
        } catch (Exception e) {
            log.error("Error checking object existence for key: {}", s3Key, e);
            return false;
        }
    }

    /**
     * Deletes an object from S3.
     */
    public void deleteObject(String s3Key) {
        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.deleteObject(deleteObjectRequest);
            log.info("Deleted object with key {} from bucket {}", s3Key, bucketName);
        } catch (Exception e) {
            log.error("Failed to delete object with key: {}", s3Key, e);
        }
    }
}
