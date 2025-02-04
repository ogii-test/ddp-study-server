package org.broadinstitute.ddp.event;

import java.nio.file.Path;
import java.time.Instant;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.storage.Blob;
import com.google.common.annotations.VisibleForTesting;
import com.google.pubsub.v1.PubsubMessage;
import org.broadinstitute.ddp.client.GoogleBucketClient;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.DataExportDao;
import org.broadinstitute.ddp.db.dao.FileUploadDao;
import org.broadinstitute.ddp.model.files.FileScanResult;
import org.broadinstitute.ddp.model.files.FileUpload;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileScanResultReceiver implements MessageReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(FileScanResultReceiver.class);

    public static final String ATTR_BUCKET_ID = "bucketId";
    public static final String ATTR_OBJECT_ID = "objectId";
    public static final String ATTR_SCAN_RESULT = "scanResult";

    private final GoogleBucketClient storageClient;
    private final String uploadsBucket;
    private final String scannedBucket;
    private final String quarantineBucket;

    public FileScanResultReceiver(GoogleBucketClient storageClient,
                                  String uploadsBucket,
                                  String scannedBucket,
                                  String quarantineBucket) {
        this.storageClient = storageClient;
        this.uploadsBucket = uploadsBucket;
        this.scannedBucket = scannedBucket;
        this.quarantineBucket = quarantineBucket;
    }

    @Override
    public void receiveMessage(PubsubMessage message, AckReplyConsumer reply) {
        String msgId = message.getMessageId();
        Instant published = Instant.ofEpochSecond(
                message.getPublishTime().getSeconds(),
                message.getPublishTime().getNanos());
        LOG.info("Received file scan result message with id={} timestamp={}", msgId, published);

        String bucketName = message.getAttributesOrDefault(ATTR_BUCKET_ID, null);
        String fileName = message.getAttributesOrDefault(ATTR_OBJECT_ID, null);
        String result = message.getAttributesOrDefault(ATTR_SCAN_RESULT, null);
        LOG.info("File scan result: bucket={} file={} result={}", bucketName, fileName, result);

        if (bucketName == null || bucketName.isBlank()) {
            LOG.warn("File scan result message is missing bucket name, ack-ing");
            reply.ack();
            return;
        } else if (!bucketName.equals(uploadsBucket)) {
            LOG.warn("Expected source bucket '{}' but got message for bucket '{}', ack-ing", uploadsBucket, bucketName);
            reply.ack();
            return;
        }

        if (fileName == null || fileName.isBlank()) {
            LOG.warn("File scan result message is missing file name, ack-ing");
            reply.ack();
            return;
        }

        if (result == null || result.isBlank()) {
            LOG.warn("File scan result message is missing scan result, ack-ing");
            reply.ack();
            return;
        }

        FileScanResult scanResult;
        try {
            scanResult = FileScanResult.valueOf(result);
        } catch (Exception e) {
            LOG.error("Received unrecognized file scan result '{}', ack-ing", result);
            reply.ack();
            return;
        }

        Blob blob = storageClient.getBlob(uploadsBucket, fileName);
        if (blob == null || !blob.exists()) {
            // This might be duplicate due to pubsub at-least-once delivery. Let's report it for now.
            LOG.error("Could not find file '{}' in bucket '{}', might be missing or had been"
                    + " moved already, ack-ing", fileName, bucketName);
            reply.ack();
            return;
        }

        boolean shouldAck;
        try {
            shouldAck = withAPIsTxn(handle -> handleFileScanResult(handle, blob, scanResult, published));
        } catch (Exception e) {
            LOG.error("Error while processing file scan result, nack-ing and retrying", e);
            shouldAck = false;
        }
        if (shouldAck) {
            reply.ack();
        } else {
            reply.nack();
        }
    }

    private String parseFileUploadGuid(String fileName) {
        // For authorized uploads, the base file name should be the upload guid.
        return Path.of(fileName).getFileName().toString();
    }

    private boolean handleFileScanResult(Handle handle, Blob blob, FileScanResult scanResult, Instant scannedAt) {
        // Find and lock file upload so we can safely update and move file.
        var uploadDao = handle.attach(FileUploadDao.class);
        String uploadGuid = parseFileUploadGuid(blob.getName());
        FileUpload upload = uploadDao.findAndLockByGuid(uploadGuid).orElse(null);

        if (upload == null) {
            // If we didn't find it, then likely not a file we authorized. Let's report it.
            LOG.error("Could not find file upload with guid '{}', ack-ing", uploadGuid);
            return true;
        }

        if (upload.getScannedAt() != null) {
            // File was already scanned (so should have been moved to a different bucket), but somehow
            // we still found it in the uploads bucket. Let's report it for now.
            LOG.error("File was already scanned at {} with result {}, ack-ing", upload.getScannedAt(), upload.getScanResult());
            return true;
        }

        String destBucket = scanResult == FileScanResult.CLEAN ? scannedBucket : quarantineBucket;
        try {
            storageClient.moveBlob(blob, destBucket, blob.getName());
            LOG.info("Moved blob {} to destination bucket {} for file upload {}",
                    blob.getBlobId(), destBucket, uploadGuid);
        } catch (Exception e) {
            LOG.error("Error while moving blob {} to destination bucket {} for file upload {},"
                    + "nack-ing and retrying", blob.getBlobId(), destBucket, uploadGuid, e);
            return false;
        }

        Instant uploadedAt = Instant.ofEpochMilli(blob.getCreateTime());
        uploadDao.updateStatus(upload.getId(), uploadedAt, scannedAt, scanResult);
        handle.attach(DataExportDao.class)
                .queueDataSync(upload.getParticipantUserId(), upload.getStudyId());
        LOG.info("Finished processing file scan result for file upload {}", uploadGuid);

        return true;
    }

    @VisibleForTesting
    <T, X extends Exception> T withAPIsTxn(HandleCallback<T, X> callback) throws X {
        return TransactionWrapper.withTxn(TransactionWrapper.DB.APIS, callback);
    }
}
