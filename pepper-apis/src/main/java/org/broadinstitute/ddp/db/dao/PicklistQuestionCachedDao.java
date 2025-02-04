package org.broadinstitute.ddp.db.dao;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.cache.Cache;
import javax.cache.expiry.Duration;

import org.broadinstitute.ddp.cache.CacheService;
import org.broadinstitute.ddp.cache.ModelChangeType;
import org.jdbi.v3.core.Handle;
import org.redisson.client.RedisException;

public class PicklistQuestionCachedDao extends SQLObjectWrapper<PicklistQuestionDao> implements PicklistQuestionDao {
    private static Cache<String, GroupAndOptionDtos> questionVersionKeyToGroupAndOptionsCache;

    public PicklistQuestionCachedDao(Handle handle) {
        super(handle, PicklistQuestionDao.class);
        initializeCache();
    }

    private void initializeCache() {
        if (questionVersionKeyToGroupAndOptionsCache == null) {
            questionVersionKeyToGroupAndOptionsCache = CacheService.getInstance().getOrCreateCache(
                    "questionVersionKeyToGroupAndOptionsCache",
                    new Duration(),
                    ModelChangeType.STUDY,
                    this.getClass());
        }
    }

    @Override
    public JdbiPicklistGroup getJdbiPicklistGroup() {
        return delegate.getJdbiPicklistGroup();
    }

    @Override
    public JdbiPicklistOption getJdbiPicklistOption() {
        return delegate.getJdbiPicklistOption();
    }

    @Override
    public JdbiPicklistGroupedOption getJdbiPicklistGroupedOption() {
        return delegate.getJdbiPicklistGroupedOption();
    }

    @Override
    public JdbiRevision getJdbiRevision() {
        return delegate.getJdbiRevision();
    }

    @Override
    public TemplateDao getTemplateDao() {
        return delegate.getTemplateDao();
    }

    @Override
    public Map<Long, GroupAndOptionDtos> findOrderedGroupAndOptionDtos(Iterable<Long> questionIds, long timestamp) {
        if (isNullCache(questionVersionKeyToGroupAndOptionsCache)) {
            return delegate.findOrderedGroupAndOptionDtos(questionIds, timestamp);
        } else {
            Set<Long> missingQuestionIds = new HashSet<>();
            Map<Long, GroupAndOptionDtos> result = new HashMap<>();

            for (var questionId : questionIds) {
                String key = questionId + ":" + timestamp;
                try {
                    GroupAndOptionDtos cachedDto = questionVersionKeyToGroupAndOptionsCache.get(key);
                    if (cachedDto != null) {
                        result.put(questionId, cachedDto);
                    } else {
                        missingQuestionIds.add(questionId);
                    }
                } catch (RedisException e) {
                    LOG.warn("Failed to retrieve value from Redis cache: " + questionVersionKeyToGroupAndOptionsCache.getName()
                            + " key:" + key + " Will try to retrieve from database", e);
                    missingQuestionIds.add(questionId);
                }
            }

            if (!missingQuestionIds.isEmpty()) {
                var found = delegate.findOrderedGroupAndOptionDtos(missingQuestionIds, timestamp);
                try {
                    found.forEach((questionId, dto) -> {
                        String key = questionId + ":" + timestamp;
                        questionVersionKeyToGroupAndOptionsCache.put(key, dto);
                    });
                } catch (RedisException e) {
                    LOG.warn("Failed to store values to Redis cache: " + questionVersionKeyToGroupAndOptionsCache.getName(), e);
                }
                result.putAll(found);
            }

            return result;
        }
    }
}
