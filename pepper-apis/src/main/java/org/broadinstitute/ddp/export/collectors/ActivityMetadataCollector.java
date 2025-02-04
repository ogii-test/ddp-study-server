package org.broadinstitute.ddp.export.collectors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.db.dto.ActivityInstanceStatusDto;
import org.broadinstitute.ddp.elastic.MappingUtil;
import org.broadinstitute.ddp.export.DataExporter;
import org.broadinstitute.ddp.model.activity.instance.ActivityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActivityMetadataCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityMetadataCollector.class);

    public List<String> emptyRow(boolean hasParent) {
        List<String> row = new ArrayList<>(Arrays.asList("", "", "", "", ""));
        if (hasParent) {
            row.add("");
        }
        return row;
    }

    public Map<String, String> emptyRecord(String activityTag, boolean hasParent) {
        return records(activityTag, null, hasParent, true);
    }

    public Map<String, String> records(String activityTag, ActivityResponse instance) {
        boolean hasParent = StringUtils.isNotBlank(instance.getParentInstanceGuid());
        return records(activityTag, instance, hasParent, false);
    }

    private Map<String, String> records(String activityTag,
                                        ActivityResponse instance,
                                        boolean hasParent,
                                        boolean isEmpty) {
        List<String> headers = headers(activityTag, hasParent);
        List<String> values = null;
        if (!isEmpty) {
            values = format(instance);
        }

        Map<String, String> records = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            records.put(headers.get(i), isEmpty ? null : values.get(i));
        }

        return records;
    }

    public Map<String, Object> mappings(String activityTag, boolean hasParent) {
        String timestampFormats = MappingUtil.appendISOTimestampFormats(DataExporter.TIMESTAMP_PATTERN);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(activityTag, MappingUtil.newKeywordType());
        if (hasParent) {
            props.put(activityTag + "_parent", MappingUtil.newKeywordType());
        }
        props.put(activityTag + "_status", MappingUtil.newKeywordType());
        props.put(activityTag + "_created_at", MappingUtil.newDateType(timestampFormats, false));
        props.put(activityTag + "_updated_at", MappingUtil.newDateType(timestampFormats, false));
        props.put(activityTag + "_completed_at", MappingUtil.newDateType(timestampFormats, false));
        return props;
    }

    public List<String> headers(String activityTag, boolean hasParentActivity) {
        List<String> header = new ArrayList<>();
        header.add(activityTag);
        if (hasParentActivity) {
            header.add(activityTag + "_parent");
        }
        header.addAll(Arrays.asList(
                activityTag + "_status",
                activityTag + "_created_at",
                activityTag + "_updated_at",
                activityTag + "_completed_at"));
        return header;
    }

    public List<String> headers(String activityTag, boolean hasParentActivity, int instanceNumber) {
        List<String> header = new ArrayList<>();
        header.add(activityTag + "_" + instanceNumber);
        if (hasParentActivity) {
            header.add(activityTag + "_" + instanceNumber + "_parent");
        }
        header.addAll(Arrays.asList(
                activityTag + "_" + instanceNumber + "_status",
                activityTag + "_" + instanceNumber + "_created_at",
                activityTag + "_" + instanceNumber + "_updated_at",
                activityTag + "_" + instanceNumber + "_completed_at"));
        return header;
    }

    public List<String> format(ActivityResponse instance) {
        Instant createdAtMillis = Instant.ofEpochMilli(instance.getCreatedAt());

        Instant firstCompletedAtMillis = null;
        if (instance.getFirstCompletedAt() != null) {
            firstCompletedAtMillis = Instant.ofEpochMilli(instance.getFirstCompletedAt());
        }

        ActivityInstanceStatusDto latestStatusDto = instance.getLatestStatus();
        Instant updatedAtMillis = Instant.ofEpochMilli(latestStatusDto.getUpdatedAt());

        List<String> row = new ArrayList<>();
        row.add(instance.getGuid());
        if (StringUtils.isNotBlank(instance.getParentInstanceGuid())) {
            row.add(instance.getParentInstanceGuid());
        }
        row.addAll(Arrays.asList(
                latestStatusDto.getType().name(),
                DataExporter.TIMESTAMP_FMT.format(createdAtMillis),
                DataExporter.TIMESTAMP_FMT.format(updatedAtMillis),
                firstCompletedAtMillis == null ? null : DataExporter.TIMESTAMP_FMT.format(firstCompletedAtMillis)));
        return row;
    }
}
