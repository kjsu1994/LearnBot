package com.learnbot.service;

import com.learnbot.config.LearnBotProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@Service
public class ObjectStorageService {
    private final LearnBotProperties properties;
    private final S3Client s3Client;

    public ObjectStorageService(LearnBotProperties properties) {
        this.properties = properties;
        LearnBotProperties.Storage storage = properties.getStorage();
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(storage.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())
                ))
                .region(Region.of(storage.getRegion()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    public StoredObject store(UUID sourceId, MultipartFile file) {
        ensureBucket();

        String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "uploaded-file"
                : file.getOriginalFilename();
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream"
                : file.getContentType();
        String objectKey = "sources/" + sourceId + "/" + UUID.randomUUID() + "-" + sanitize(filename);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getStorage().getBucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return new StoredObject(properties.getStorage().getBucket(), objectKey, filename, contentType, file.getSize());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read uploaded file for object storage.", ex);
        } catch (S3Exception ex) {
            throw new IllegalArgumentException("Could not store original file: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    public StoredObject storeBytes(UUID sourceId, String filename, String contentType, byte[] content) {
        ensureBucket();

        String safeFilename = filename == null || filename.isBlank() ? "imported-file" : filename;
        String safeContentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        byte[] safeContent = content == null ? new byte[0] : content;
        String objectKey = "sources/" + sourceId + "/" + UUID.randomUUID() + "-" + sanitize(safeFilename);

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getStorage().getBucket())
                    .key(objectKey)
                    .contentType(safeContentType)
                    .contentLength((long) safeContent.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(safeContent));
            return new StoredObject(properties.getStorage().getBucket(), objectKey, safeFilename, safeContentType, safeContent.length);
        } catch (S3Exception ex) {
            throw new IllegalArgumentException("Could not store imported original file: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    public StoredFile load(StoredObject object) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(object.bucket())
                    .key(object.objectKey())
                    .build());
            return new StoredFile(object.originalFilename(), object.contentType(), response.asByteArray());
        } catch (S3Exception ex) {
            throw new IllegalArgumentException("Could not load original file: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    public void delete(StoredObject object) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(object.bucket())
                    .key(object.objectKey())
                    .build());
        } catch (S3Exception ex) {
            throw new IllegalArgumentException("Could not delete original file: " + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    private void ensureBucket() {
        String bucket = properties.getStorage().getBucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException ex) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                return;
            }
            throw new IllegalArgumentException("Could not access object storage bucket: "
                    + ex.awsErrorDetails().errorMessage(), ex);
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
