package org.broadinstitute.ddp.service;

import static org.broadinstitute.ddp.service.FileUploadService.AuthorizeResultType.FILE_SIZE_EXCEEDS_MAXIMUM;
import static org.broadinstitute.ddp.service.FileUploadService.AuthorizeResultType.MIME_TYPE_NOT_ALLOWED;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.HttpMethod;
import com.typesafe.config.Config;
import org.broadinstitute.ddp.client.GoogleBucketClient;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.db.dao.FileUploadDao;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.interfaces.FileUploadSettings;
import org.broadinstitute.ddp.model.files.FileScanResult;
import org.broadinstitute.ddp.model.files.FileUpload;
import org.broadinstitute.ddp.util.ConfigUtil;
import org.broadinstitute.ddp.util.GoogleCredentialUtil;
import org.broadinstitute.ddp.util.GuidUtils;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileUploadService {

    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    public static final int DEFAULT_BATCH_SIZE = 100;

    private static final Logger LOG = LoggerFactory.getLogger(FileUploadService.class);

    private final ServiceAccountSigner signer;
    private final GoogleBucketClient storageClient;
    private final String uploadsBucket;
    private final String scannedBucket;
    private final String quarantineBucket;
    private final int maxSignedUrlMins;
    private final long removalExpireTime;
    private final TimeUnit removalExpireUnit;
    private final int removalBatchSize;

    public static FileUploadService fromConfig(Config cfg) {
        String signerJson = ConfigUtil.toJson(cfg.getConfig(ConfigFile.FileUploads.SIGNER_SERVICE_ACCOUNT));
        InputStream signerStream = new ByteArrayInputStream(signerJson.getBytes(StandardCharsets.UTF_8));
        ServiceAccountCredentials signerCredentials;
        try {
            signerCredentials = ServiceAccountCredentials.fromStream(signerStream);
        } catch (IOException e) {
            throw new DDPException("Could not get signer credentials", e);
        }

        GoogleCredentials bucketCredentials;
        boolean ensureDefault = cfg.getBoolean(ConfigFile.REQUIRE_DEFAULT_GCP_CREDENTIALS);
        bucketCredentials = GoogleCredentialUtil.initCredentials(ensureDefault);
        if (bucketCredentials == null) {
            LOG.error("Could not get bucket credentials, defaulting to signer credentials");
            bucketCredentials = signerCredentials;
        }

        String projectId = cfg.getString(ConfigFile.GOOGLE_PROJECT_ID);
        int removalBatchSize = cfg.getInt(ConfigFile.FileUploads.REMOVAL_BATCH_SIZE);
        long removalExpireTime = cfg.getLong(ConfigFile.FileUploads.REMOVAL_EXPIRE_TIME);
        TimeUnit removalExpireUnit = TimeUnit.valueOf(cfg.getString(ConfigFile.FileUploads.REMOVAL_EXPIRE_UNIT));

        return new FileUploadService(
                signerCredentials,
                new GoogleBucketClient(projectId, bucketCredentials),
                cfg.getString(ConfigFile.FileUploads.UPLOADS_BUCKET),
                cfg.getString(ConfigFile.FileUploads.SCANNED_BUCKET),
                cfg.getString(ConfigFile.FileUploads.QUARANTINE_BUCKET),
                cfg.getInt(ConfigFile.FileUploads.MAX_SIGNED_URL_MINS),
                removalExpireTime, removalExpireUnit, removalBatchSize);
    }

    public FileUploadService(ServiceAccountSigner signer, GoogleBucketClient storageClient,
                             String uploadsBucket, String scannedBucket, String quarantineBucket,
                             int maxSignedUrlMins, long removalExpireTime, TimeUnit removalExpireUnit, int removalBatchSize) {
        this.signer = signer;
        this.storageClient = storageClient;
        this.uploadsBucket = uploadsBucket;
        this.scannedBucket = scannedBucket;
        this.quarantineBucket = quarantineBucket;
        this.maxSignedUrlMins = maxSignedUrlMins;
        this.removalExpireTime = removalExpireTime;
        this.removalExpireUnit = removalExpireUnit;
        this.removalBatchSize = removalBatchSize;
        if (removalExpireUnit == TimeUnit.MICROSECONDS || removalExpireUnit == TimeUnit.NANOSECONDS) {
            throw new IllegalArgumentException("Granularity of time unit is not supported: " + removalExpireUnit);
        }
    }

    public String getUploadsBucket() {
        return uploadsBucket;
    }

    /**
     * Get name of bucket that hosts the given file upload.
     *
     * @param upload the file upload
     * @return the bucket name
     */
    public String getBucketForUpload(FileUpload upload) {
        if (upload.getScanResult() == FileScanResult.CLEAN) {
            return scannedBucket;
        } else if (upload.getScanResult() == FileScanResult.INFECTED) {
            return quarantineBucket;
        } else {
            return uploadsBucket;
        }
    }

    /**
     * Authorize a file upload by creating a file record and generating a signed URL.
     *
     * @param handle            the database handle
     * @param studyId           the study to authorize upload for
     * @param operatorUserId    the operator who instantiated this request
     * @param participantUserId the participant who will own the file
     * @param fileUploadSettings file upload parameters
     * @param blobPrefix        a prefix to prepend to blob name, e.g. for organizational purposes
     * @param mimeType          the user-reported mime type
     * @param fileName          the user-reported name for the file
     * @param fileSize          the user-reported file size
     * @param resumable         whether to allow resumable upload
     * @return authorization result
     */
    public AuthorizeResult authorizeUpload(Handle handle, long studyId, long operatorUserId, long participantUserId,
                                           FileUploadSettings fileUploadSettings,
                                           String blobPrefix, String mimeType,
                                           String fileName, long fileSize, boolean resumable) {
        if (fileSize > fileUploadSettings.getMaxFileSize()) {
            return new AuthorizeResult(FILE_SIZE_EXCEEDS_MAXIMUM, null, null, fileUploadSettings);
        }
        if (mimeType != null && fileUploadSettings.getMimeTypes() != null && !fileUploadSettings.getMimeTypes().isEmpty()
                && !fileUploadSettings.getMimeTypes().contains(mimeType)) {
            return new AuthorizeResult(MIME_TYPE_NOT_ALLOWED, null, null, fileUploadSettings);
        }

        blobPrefix = blobPrefix != null ? blobPrefix + "/" : "";
        mimeType = mimeType != null ? mimeType : DEFAULT_MIME_TYPE;

        HttpMethod method = resumable ? HttpMethod.POST : HttpMethod.PUT;
        String uploadGuid = GuidUtils.randomFileUploadGuid();
        String blobName = blobPrefix + uploadGuid;

        FileUpload upload = handle.attach(FileUploadDao.class).createAuthorized(
                uploadGuid, studyId, operatorUserId, participantUserId,
                blobName, mimeType, fileName, fileSize);
        Map<String, String> headers = Map.of("Content-Type", mimeType);
        URL signedURL = storageClient.generateSignedUrl(
                signer, uploadsBucket, blobName,
                maxSignedUrlMins, TimeUnit.MINUTES,
                method, headers);

        return new AuthorizeResult(AuthorizeResultType.OK, upload, signedURL, fileUploadSettings);
    }

    // Convenience helper to lock file upload before verifying.
    public Optional<VerifyResult> verifyUpload(Handle handle, long studyId, long participantUserId, long uploadId) {
        return handle.attach(FileUploadDao.class)
                .findAndLockById(uploadId)
                .map(upload -> verifyUpload(handle, studyId, participantUserId, upload));
    }

    /**
     * Determine if file meets criteria, and update its status appropriately. E.g. file must be uploaded, matches
     * reported file size, and belong to the participant. Files already verified will not be checked again.
     *
     * <p>File upload should be "locked" to ensure its status doesn't change midway or file gets moved to a different
     * bucket. Since the file upload is locked, caller should commit transaction as soon as possible to unblock other
     * transactions waiting on the file upload row.
     *
     * @param handle            the database handle
     * @param studyId           the study that participant is in
     * @param participantUserId the participant who this upload will be associated to
     * @param upload            the file upload
     * @return check result
     */
    public VerifyResult verifyUpload(Handle handle, long studyId, long participantUserId, FileUpload upload) {
        if (studyId != upload.getStudyId() || participantUserId != upload.getParticipantUserId()) {
            return VerifyResult.OWNER_MISMATCH;
        }

        if (!upload.isVerified()) {
            // File scan usually finish within a minute of upload, and verification call from user should happen
            // relatively quick right after the upload, so it might be rare for it to be scanned already. But let's
            // check for this condition anyways.
            if (upload.getScannedAt() != null && upload.getScanResult() == FileScanResult.INFECTED) {
                return VerifyResult.QUARANTINED;
            }

            String bucket = getBucketForUpload(upload);
            Blob blob = storageClient.getBlob(bucket, upload.getBlobName());
            boolean exists = (blob != null && blob.exists());
            Long size = blob == null ? null : blob.getSize();

            if (!exists || blob.getCreateTime() == null) {
                return VerifyResult.NOT_UPLOADED;
            }

            if (size == null || size != upload.getFileSize()) {
                return VerifyResult.SIZE_MISMATCH;
            }

            handle.attach(FileUploadDao.class).markVerified(upload.getId());
            LOG.info("File upload {} is now marked as verified", upload.getGuid());
        } else {
            LOG.info("File upload {} was already verified", upload.getGuid());
        }

        return VerifyResult.OK;
    }

    // Convenience helper to run removal using the service's configured settings.
    public int removeUnusedUploads(Handle handle) {
        LOG.info("Starting removal of unused file uploads older than {} {}", removalExpireTime, removalExpireUnit);
        long duration = removalExpireUnit.toMillis(removalExpireTime);
        Instant olderThanTimestamp = Instant.now().minusMillis(duration);
        return removeUnusedUploads(handle, olderThanTimestamp, removalBatchSize);
    }

    /**
     * Remove file uploads that are older than the given timestamp and is not used. And unused file upload is one that
     * is either not verified or is not associated with any file answers.
     *
     * @param handle             the database handle
     * @param olderThanTimestamp look for authorized uploads older than this timestamp
     * @param removalBatchSize   optional, will use the default batch size if not provided
     * @return number of file uploads removed
     */
    public int removeUnusedUploads(Handle handle, Instant olderThanTimestamp, Integer removalBatchSize) {
        var uploadDao = handle.attach(FileUploadDao.class);
        Set<Long> uploadIdsToDelete = new HashSet<>();
        int offset = 0;

        int batchSize = removalBatchSize != null ? removalBatchSize : DEFAULT_BATCH_SIZE;
        while (true) {
            ArrayDeque<FileUpload> queue;
            try (var stream = uploadDao.findUnverifiedOrUnassociatedUploads(olderThanTimestamp, offset, batchSize)) {
                queue = stream.collect(Collectors.toCollection(ArrayDeque::new));
            }
            if (queue.isEmpty()) {
                break;
            }

            int numFound = queue.size();
            while (!queue.isEmpty()) {
                FileUpload upload = queue.remove();
                String bucket = getBucketForUpload(upload);
                Blob blob = storageClient.getBlob(bucket, upload.getBlobName());
                if (blob != null && blob.exists()) {
                    try {
                        storageClient.deleteBlob(blob);
                        LOG.info("Deleted blob {} for unused file upload {}", blob.getBlobId(), upload.getGuid());
                    } catch (Exception e) {
                        LOG.error("Unable to delete file {} from bucket {}, skipping removal of unused file upload {}",
                                upload.getBlobName(), bucket, upload.getGuid());
                        continue;
                    }
                } else if (upload.getUploadedAt() != null) {
                    LOG.error("Unable to locate file {} in bucket {}, skipping removal of unused file upload {}",
                            upload.getBlobName(), bucket, upload.getGuid());
                    continue;
                } else {
                    // Nothing to do. Actual file is not uploaded to bucket.
                }
                uploadIdsToDelete.add(upload.getId());
            }

            offset += numFound;
        }

        uploadDao.deleteByIds(uploadIdsToDelete);
        LOG.info("Removed {} unused file uploads", uploadIdsToDelete.size());
        return uploadIdsToDelete.size();
    }

    public enum VerifyResult {
        /**
         * File hasn't been uploaded yet.
         */
        NOT_UPLOADED,
        /**
         * File does not belong to participant or the study.
         */
        OWNER_MISMATCH,
        /**
         * File was scanned already and found to be infected.
         */
        QUARANTINED,
        /**
         * File size does not match up.
         */
        SIZE_MISMATCH,
        /**
         * No issues found.
         */
        OK,
    }

    public enum AuthorizeResultType {
        FILE_SIZE_EXCEEDS_MAXIMUM,
        MIME_TYPE_NOT_ALLOWED,
        OK
    }

    public static class AuthorizeResult {
        private final AuthorizeResultType authorizeResultType;
        private final FileUpload fileUpload;
        private final URL signedUrl;
        private final FileUploadSettings fileUploadSettings;

        public AuthorizeResult(
                AuthorizeResultType authorizeResultType,
                FileUpload fileUpload,
                URL signedUrl,
                FileUploadSettings fileUploadSettings) {
            this.authorizeResultType = authorizeResultType;
            this.fileUpload = fileUpload;
            this.signedUrl = signedUrl;
            this.fileUploadSettings = fileUploadSettings;
        }

        public AuthorizeResultType getAuthorizeResultType() {
            return authorizeResultType;
        }

        public FileUpload getFileUpload() {
            return fileUpload;
        }

        public URL getSignedUrl() {
            return signedUrl;
        }

        public FileUploadSettings getFileUploadSettings() {
            return fileUploadSettings;
        }
    }
}
