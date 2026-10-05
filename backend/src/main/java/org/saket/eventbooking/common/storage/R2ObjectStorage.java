package org.saket.eventbooking.common.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cloudflare R2 through its S3-compatible API. Signing (SigV4) is local; nothing here talks to R2.
 * Reads go through the bucket's public URL (an r2.dev subdomain or a custom domain / CDN).
 */
public class R2ObjectStorage implements ObjectStorage, AutoCloseable {

    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBaseUrl;

    public R2ObjectStorage(StorageProperties.R2 r2) {
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create("https://" + r2.accountId() + ".r2.cloudflarestorage.com"))
                .region(Region.of("auto")) // R2 ignores regions but SigV4 needs one
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.accessKeyId(), r2.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.bucket = r2.bucket();
        this.publicBaseUrl = r2.publicBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public boolean supportsDirectUpload() {
        return true;
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration validFor) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(p -> p
                .signatureDuration(validFor)
                .putObjectRequest(put));

        // Content-Type and Content-Length are signed; the browser sets Content-Length itself.
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(presigned.url().toString(), headers, publicBaseUrl + "/" + objectKey,
                presigned.expiration());
    }

    @Override
    public void close() {
        presigner.close();
    }
}
