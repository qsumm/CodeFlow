package com.ainative.storage;

import com.ainative.branding.PlatformBrandingService;
import com.ainative.taskpackage.TaskPackager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Set;

@Configuration
public class ObjectStorageConfig {

    private static final Set<String> RETIRED_BUCKETS = Set.of(
            "ainative-task-pkg-daily-tmp",
            "ainative-artifacts-daily-tmp");

    @Bean
    @ConditionalOnProperty(prefix = "s3", name = "enabled", havingValue = "true")
    public ObjectStorage s3ObjectStorage(S3Properties props) {
        props.validate();
        return new S3ObjectStorage(props.getEndpoint(), props.resolvePublicEndpoint(),
                props.getRegion(), props.getAccessKeyId(), props.getAccessKeySecret(),
                props.isForcePathStyle());
    }

    @Bean
    public TaskPackager taskPackager(ObjectStorage objectStorage, OssProperties props,
                                     PlatformBrandingService brandingService) {
        validateConfiguredBuckets(props);
        require("oss.task-pkg-bucket or oss.bucket", props.resolveTaskPkgBucket());
        return new TaskPackager(objectStorage, props.resolveTaskPkgBucket(), brandingService::effectiveMcpBaseUrl);
    }

    private static void require(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required");
        }
    }

    private static void validateConfiguredBuckets(OssProperties props) {
        rejectRetiredBucket("oss.task-pkg-bucket", props.getTaskPkgBucket());
        rejectRetiredBucket("oss.artifact-bucket", props.getArtifactBucket());
    }

    private static void rejectRetiredBucket(String property, String bucket) {
        if (bucket != null && RETIRED_BUCKETS.contains(bucket.trim())) {
            throw new IllegalStateException(property + " references retired OSS bucket " + bucket
                    + "; use the corresponding *-tmp-new bucket");
        }
    }
}
