package com.example.ratelimit.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Policies live in the same shared Redis as the counters, under a different namespace:
 * {@code ratelimit:policy:v1:*} for documents versus {@code rate-limit:v1:*} for counters. Namespacing
 * them apart is what lets a reset of counter state leave policy edits untouched, and vice versa.
 *
 * <p>Every mutation runs through one Lua script so the version check and the write cannot interleave.
 * Two admins saving the same policy concurrently means exactly one succeeds; the other gets
 * {@link PolicyConflictException}. Single Redis only: the script touches one document key and the
 * shared index key, which would need a hash tag to stay in one slot under Redis Cluster.
 */
@Component
public class ManagedPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(ManagedPolicyStore.class);

    static final String NS = "ratelimit:policy:v1";
    static final String INDEX = NS + ":index";
    static final String AUDIT = NS + ":audit";
    static final String SEEDED = NS + ":seeded";
    static final String META = NS + ":meta";
    static final String GROUP_INDEX = NS + ":group-index";
    static final String EPOCH = NS + ":epoch";
    static final String PROJECTIONS = NS + ":projections";
    private static final String DOC_PREFIX = NS + ":doc:";
    private static final String GROUP_PREFIX = NS + ":group:";

    /** Modes for {@link #WRITE}. */
    private static final int MODE_CREATE = 0;
    private static final int MODE_UPDATE = 1;
    private static final int MODE_DELETE = 2;

    /** Result codes returned by {@link #WRITE}. */
    private static final int OK = 1;
    private static final int MISSING = -1;
    private static final int CONFLICT = -2;

    private static final int AUDIT_MAX_ENTRIES = 200;

    /**
     * Compare-and-set on (version, document). KEYS[1]=document hash, KEYS[2]=index set.
     * ARGV[1]=mode ARGV[2]=id ARGV[3]=json ARGV[4]=attemptedVersion ARGV[5]=nowIso
     * ARGV[6]=createdAtIso
     *
     * <p>Returns {code, storedVersion}. Rejected writes touch nothing.
     */
    private static final RedisScript<List> WRITE = new DefaultRedisScript<>("""
            local mode = tonumber(ARGV[1])
            local id = ARGV[2]
            local exists = redis.call('EXISTS', KEYS[1])

            if mode == 2 then
              if exists == 0 then return {-1, 0} end
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], id)
              return {1, 0}
            end

            if mode == 0 then
              -- Create must not clobber an existing id; the caller retries as an update.
              if exists == 1 then return {-1, tonumber(redis.call('HGET', KEYS[1], 'version') or '0')} end
            else
              if exists == 0 then return {-1, 0} end
              local stored = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
              if stored ~= tonumber(ARGV[4]) - 1 then return {-2, stored} end
            end

            redis.call('HSET', KEYS[1], 'doc', ARGV[3], 'version', ARGV[4], 'updatedAt', ARGV[5])
            redis.call('HSETNX', KEYS[1], 'createdAt', ARGV[6])
            redis.call('SADD', KEYS[2], id)
            return {1, tonumber(ARGV[4])}
            """, List.class);

    /** Atomic batch read of all policy documents in one Redis round trip. KEYS[1] = INDEX. */
    private static final RedisScript<List> READ_ALL_DOCS = new DefaultRedisScript<>("""
            local ids = redis.call('SMEMBERS', KEYS[1])
            local docs = {}
            for i, id in ipairs(ids) do
              local doc = redis.call('HGET', 'ratelimit:policy:v1:doc:' .. id, 'doc')
              if doc then
                table.insert(docs, doc)
              end
            end
            return docs
            """, List.class);

    private static final RedisScript<Long> RESET = new DefaultRedisScript<>("""
            local removed = 0
            local ids = redis.call('SMEMBERS', KEYS[1])
            for _, id in ipairs(ids) do
              if redis.call('DEL', 'ratelimit:policy:v1:doc:' .. id) > 0 then removed = removed + 1 end
            end
            local groupIds = redis.call('SMEMBERS', KEYS[2])
            for _, id in ipairs(groupIds) do
              if redis.call('DEL', 'ratelimit:policy:v1:group:' .. id) > 0 then removed = removed + 1 end
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6],
              'ratelimit:policy:v1:global-rules', 'ratelimit:policy:v1:global-rules:version',
              'ratelimit:policy:v1:global-rules:updatedAt', 'ratelimit:policy:v1:global-rules:createdAt',
              'ratelimit:policy:v1:global-rules:projectionIds')
            redis.call('INCR', KEYS[7])
            return removed
            """, Long.class);

    private static final RedisScript<List> REPAIR_PROJECTIONS = new DefaultRedisScript<>("""
            local groupKey = KEYS[1]
            if redis.call('EXISTS', groupKey) == 0 then return {-1, 0, 0} end
            local storedVersion = tonumber(redis.call('HGET', groupKey, 'version') or '0')
            if storedVersion ~= tonumber(ARGV[1]) then return {-2, storedVersion, 0} end
            local epoch = tonumber(redis.call('GET', KEYS[2]) or '0')
            if epoch ~= tonumber(ARGV[2]) then return {-3, epoch, 0} end
            local oldIds = cjson.decode(redis.call('HGET', groupKey, 'projectionIds') or '[]')
            local desiredIds = cjson.decode(ARGV[4])
            local desired = {}
            for _, id in ipairs(desiredIds) do desired[id] = true end
            local deleted = 0
            for _, id in ipairs(oldIds) do
              if not desired[id] and redis.call('SISMEMBER', KEYS[3], id) == 1 then
                if redis.call('DEL', 'ratelimit:policy:v1:doc:' .. id) > 0 then deleted = deleted + 1 end
                redis.call('SREM', KEYS[3], id)
                redis.call('SREM', KEYS[4], id)
              end
            end
            local projections = cjson.decode(ARGV[5])
            local written = 0
            for _, proj in ipairs(projections) do
              local key = 'ratelimit:policy:v1:doc:' .. proj.id
              if redis.call('HGET', key, 'doc') ~= proj.json
                  or redis.call('SISMEMBER', KEYS[3], proj.id) == 0
                  or redis.call('SISMEMBER', KEYS[4], proj.id) == 0 then written = written + 1 end
              redis.call('HSET', key, 'doc', proj.json, 'version', tostring(proj.version))
              redis.call('SADD', KEYS[3], proj.id)
              redis.call('SADD', KEYS[4], proj.id)
            end
            redis.call('HSET', groupKey, 'doc', ARGV[3], 'projectionIds', ARGV[4])
            redis.call('INCR', KEYS[2])
            return {1, written, deleted}
            """, List.class);

    /**
     * Atomic group write with projections. KEYS[1]=group key, KEYS[2]=group index, KEYS[3]=epoch,
     * KEYS[4]=projection index, KEYS[5]=policy index.
     * ARGV[1]=mode (0=create, 1=update) ARGV[2]=groupId ARGV[3]=groupJson ARGV[4]=version
     * ARGV[5]=expectedEpoch ARGV[6]=updatedAt ARGV[7]=createdAt ARGV[8]=updatedBy
     * ARGV[9]=projectionsJson (list of {id, json}) ARGV[10]=projectionCount
     *
     * Returns {code, storedVersion}. code: 1=ok, -1=missing, -2=version conflict, -3=epoch conflict.
     */
    private static final RedisScript<List> GROUP_WRITE = new DefaultRedisScript<>("""
            local mode = tonumber(ARGV[1])
            local groupId = ARGV[2]
            local groupKey = KEYS[1]
            local exists = redis.call('EXISTS', groupKey)
            local epoch = tonumber(redis.call('GET', KEYS[3]) or '0')
            if epoch ~= tonumber(ARGV[5]) then return {-3, epoch} end

            if mode == 0 then
              if exists == 1 then return {-1, tonumber(redis.call('HGET', groupKey, 'version') or '0')} end
            else
              if exists == 0 then return {-1, 0} end
              local stored = tonumber(redis.call('HGET', groupKey, 'version') or '0')
              if stored ~= tonumber(ARGV[4]) - 1 then return {-2, stored} end
            end

            redis.call('HSET', groupKey, 'doc', ARGV[3], 'version', ARGV[4], 'updatedAt', ARGV[6])
            redis.call('HSETNX', groupKey, 'createdAt', ARGV[7])
            redis.call('SADD', KEYS[2], groupId)

            -- delete old projections for this group
            local oldProj = redis.call('HGET', groupKey, 'projectionIds')
            if oldProj then
              local ids = cjson.decode(oldProj)
              for i, pid in ipairs(ids) do
                redis.call('DEL', 'ratelimit:policy:v1:doc:' .. pid)
                redis.call('SREM', KEYS[4], pid)
                redis.call('SREM', KEYS[5], pid)
              end
            end

            -- write new projections
            local projections = cjson.decode(ARGV[9])
            local projIds = {}
            for i, proj in ipairs(projections) do
              redis.call('HSET', 'ratelimit:policy:v1:doc:' .. proj.id, 'doc', proj.json, 'version', ARGV[4])
              redis.call('SADD', KEYS[4], proj.id)
              redis.call('SADD', KEYS[5], proj.id)
              table.insert(projIds, proj.id)
            end
            redis.call('HSET', groupKey, 'projectionIds', cjson.encode(projIds))

            redis.call('INCR', KEYS[3])
            return {1, tonumber(ARGV[4])}
            """, List.class);

    /**
     * Atomic group delete with projections. KEYS[1]=group key, KEYS[2]=group index, KEYS[3]=epoch,
     * KEYS[4]=projection index, KEYS[5]=policy index.
     * ARGV[1]=groupId ARGV[2]=expectedEpoch ARGV[3]=projectionIdsJson
     *
     * Returns {code}. code: 1=ok, -1=missing, -3=epoch conflict.
     */
    private static final RedisScript<List> GROUP_DELETE = new DefaultRedisScript<>("""
            local groupId = ARGV[1]
            local groupKey = KEYS[1]
            local exists = redis.call('EXISTS', groupKey)
            if exists == 0 then return {-1} end
            local epoch = tonumber(redis.call('GET', KEYS[3]) or '0')
            if epoch ~= tonumber(ARGV[2]) then return {-3, epoch} end

            local projIds = cjson.decode(redis.call('HGET', groupKey, 'projectionIds') or '[]')
            for i, pid in ipairs(projIds) do
              redis.call('DEL', 'ratelimit:policy:v1:doc:' .. pid)
              redis.call('SREM', KEYS[4], pid)
              redis.call('SREM', KEYS[5], pid)
            end
            redis.call('DEL', groupKey)
            redis.call('SREM', KEYS[2], groupId)
            redis.call('INCR', KEYS[3])
            return {1}
            """, List.class);

    /**
     * Atomic global-rules write with projections. KEYS[1]=global-rules key, KEYS[2]=epoch,
     * KEYS[3]=projection index, KEYS[4]=policy index.
     * ARGV[1]=mode (0=create, 1=update) ARGV[2]=rulesJson ARGV[3]=version
     * ARGV[4]=expectedEpoch ARGV[5]=updatedAt ARGV[6]=createdAt ARGV[7]=updatedBy
     * ARGV[8]=projectionsJson ARGV[9]=projectionCount
     *
     * Returns {code, storedVersion}. code: 1=ok, -1=missing, -2=version conflict, -3=epoch conflict.
     */
    private static final RedisScript<List> GLOBAL_WRITE = new DefaultRedisScript<>("""
            local mode = tonumber(ARGV[1])
            local rulesKey = KEYS[1]
            local exists = redis.call('EXISTS', rulesKey)
            local epoch = tonumber(redis.call('GET', KEYS[2]) or '0')
            if epoch ~= tonumber(ARGV[4]) then return {-3, epoch} end

            if mode == 0 then
              if exists == 1 then return {-1, tonumber(redis.call('GET', rulesKey .. ':version') or '0')} end
            else
              if exists == 0 then return {-1, 0} end
              local stored = tonumber(redis.call('GET', rulesKey .. ':version') or '0')
              if stored ~= tonumber(ARGV[3]) - 1 then return {-2, stored} end
            end

            redis.call('SET', rulesKey, ARGV[2])
            redis.call('SET', rulesKey .. ':version', ARGV[3])
            redis.call('SET', rulesKey .. ':updatedAt', ARGV[5])
            redis.call('SETNX', rulesKey .. ':createdAt', ARGV[6])

            -- delete old projections
            local oldProj = redis.call('GET', rulesKey .. ':projectionIds')
            if oldProj then
              local ids = cjson.decode(oldProj)
              for i, pid in ipairs(ids) do
                redis.call('DEL', 'ratelimit:policy:v1:doc:' .. pid)
                redis.call('SREM', KEYS[3], pid)
                redis.call('SREM', KEYS[4], pid)
              end
            end

            -- write new projections
            local projections = cjson.decode(ARGV[8])
            local projIds = {}
            for i, proj in ipairs(projections) do
              redis.call('HSET', 'ratelimit:policy:v1:doc:' .. proj.id, 'doc', proj.json, 'version', ARGV[3])
              redis.call('SADD', KEYS[3], proj.id)
              redis.call('SADD', KEYS[4], proj.id)
              table.insert(projIds, proj.id)
            end
            redis.call('SET', rulesKey .. ':projectionIds', cjson.encode(projIds))

            redis.call('INCR', KEYS[2])
            return {1, tonumber(ARGV[3])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ManagedPolicyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** True when a first-run seed has already happened, so admin edits are never overwritten. */
    public boolean isSeeded() {
        try {
            return Boolean.TRUE.equals(redis.hasKey(SEEDED));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable checking seeded marker", e);
        }
    }

    public void markSeeded() {
        try {
            redis.opsForValue().set(SEEDED, "1");
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable marking seeded", e);
        }
    }

    public Optional<PolicyDocument> find(String id) {
        try {
            var ops = redis.opsForHash();
            Object json = ops.get(docKey(id), "doc");
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json.toString(), PolicyDocument.class));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading policy " + id, e);
        } catch (Exception e) {
            throw new PolicyStoreException("stored policy " + id + " is not readable JSON", e);
        }
    }

    /** Every policy, ordered by id so the admin list is stable across instances. Executes in 1 Redis round trip. */
    public List<PolicyDocument> findAll() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> rawDocs = redis.execute(READ_ALL_DOCS, List.of(INDEX));
            if (rawDocs == null || rawDocs.isEmpty()) {
                return List.of();
            }
            var out = new ArrayList<PolicyDocument>(rawDocs.size());
            for (Object raw : rawDocs) {
                if (raw != null) {
                    try {
                        out.add(mapper.readValue(raw.toString(), PolicyDocument.class));
                    } catch (Exception e) {
                        log.warn("skipping unreadable policy doc in findAll: {}", e.getMessage());
                    }
                }
            }
            out.sort((a, b) -> a.id().compareTo(b.id()));
            return out;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable listing policies", e);
        }
    }

    /**
     * Atomically stores {@code document} and appends an audit entry.
     *
     * <p>Create requires the id to be unused. Update requires {@code document.version()} to be exactly
     * the stored version plus one, which is what prevents a lost update.
     *
     * @param existing the currently stored policy, or null when creating
     */
    public PolicyDocument save(PolicyDocument document, PolicyDocument existing, String actor) {
        document.validate();
        int mode = existing == null ? MODE_CREATE : MODE_UPDATE;

        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (Exception e) {
            throw new PolicyStoreException("policy could not be serialised", e);
        }

        String now = document.updatedAt().toString();
        String created = document.createdAt().toString();
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(document.id()), INDEX),
                    String.valueOf(mode), document.id(), json, String.valueOf(document.version()), now, created);
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable writing policy " + document.id(), e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the policy write", null);
        }
        int code = result.get(0).intValue();
        if (code == MISSING) {
            throw new PolicyNotFoundException(document.id());
        }
        if (code == CONFLICT) {
            throw new PolicyConflictException(result.get(1), document.version());
        }

        record(actor, document.id(), existing == null ? "CREATE" : "UPDATE", document.version(),
                changedFields(existing, document));
        log.info("policy {} {} by {} at version {}", document.id(),
                existing == null ? "created" : "updated", actor, document.version());
        return document;
    }

    public void delete(String id, String actor, long resultingVersion) {
        List<Long> result;
        try {
            result = redis.execute(WRITE, List.of(docKey(id), INDEX),
                    String.valueOf(MODE_DELETE), id, "", "0", "", "");
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable deleting policy " + id, e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the policy delete", null);
        }
        if (result.get(0).intValue() == MISSING) {
            throw new PolicyNotFoundException(id);
        }
        record(actor, id, "DELETE", resultingVersion, List.of());
        log.info("policy {} deleted by {}", id, actor);
    }

    /** Newest first, capped at {@link #AUDIT_MAX_ENTRIES} entries in Redis. */
    public List<AuditEntry> audit(int limit) {
        int capped = Math.max(1, Math.min(limit, AUDIT_MAX_ENTRIES));
        List<String> raw;
        try {
            raw = redis.opsForList().range(AUDIT, 0, capped - 1);
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading audit log", e);
        }
        if (raw == null) {
            return List.of();
        }
        var out = new ArrayList<AuditEntry>(raw.size());
        for (String json : raw) {
            try {
                out.add(mapper.readValue(json, AuditEntry.class));
            } catch (Exception e) {
                log.warn("skipping unreadable audit entry: {}", e.getMessage());
            }
        }
        return out;
    }

    /**
     * Records the audit entry in the same round trip style as the write, then trims the list so it
     * cannot grow without bound. Losing the oldest entries under heavy admin use is the intended
     * trade-off for a POC; a real deployment ships these to a durable log instead.
     */
    private void record(String actor, String policyId, String operation, long version,
            List<String> changedFields) {
        try {
            String json = mapper.writeValueAsString(new AuditEntry(Instant.now(), actor, policyId,
                    operation, version, changedFields));
            redis.opsForList().leftPush(AUDIT, json);
            redis.opsForList().trim(AUDIT, 0, AUDIT_MAX_ENTRIES - 1);
        } catch (DataAccessException e) {
            // The policy change already committed. Losing its audit line must not fail the save.
            log.warn("audit append failed for policy {}: {}", policyId, e.getMessage());
        } catch (Exception e) {
            log.warn("audit serialisation failed for policy {}: {}", policyId, e.getMessage());
        }
    }

    /** Field names whose values differ. Names only, never values. */
    private static List<String> changedFields(PolicyDocument before, PolicyDocument after) {
        if (before == null) {
            return List.of("id", "name", "method", "path", "algorithm", "scope", "enabled");
        }
        Set<String> changed = new LinkedHashSet<>();
        if (!java.util.Objects.equals(before.name(), after.name())) changed.add("name");
        if (!java.util.Objects.equals(before.method(), after.method())) changed.add("method");
        if (!java.util.Objects.equals(before.path(), after.path())) changed.add("path");
        if (before.algorithm() != after.algorithm()) changed.add("algorithm");
        if (before.scope() != after.scope()) changed.add("scope");
        if (!java.util.Objects.equals(before.window(), after.window())) changed.add("window");
        if (!java.util.Objects.equals(before.limit(), after.limit())) changed.add("limit");
        if (!java.util.Objects.equals(before.capacity(), after.capacity())) changed.add("capacity");
        if (!java.util.Objects.equals(before.refillInterval(), after.refillInterval())) changed.add("refillInterval");
        if (!java.util.Objects.equals(before.cost(), after.cost())) changed.add("cost");
        if (!java.util.Objects.equals(before.drainRate(), after.drainRate())) changed.add("drainRate");
        if (!java.util.Objects.equals(before.queueCapacity(), after.queueCapacity())) changed.add("queueCapacity");
        if (!java.util.Objects.equals(before.maxConcurrent(), after.maxConcurrent())) changed.add("maxConcurrent");
        if (!java.util.Objects.equals(before.leaseDuration(), after.leaseDuration())) changed.add("leaseDuration");
        if (before.enabled() != after.enabled()) changed.add("enabled");
        if (before.onRedisError() != after.onRedisError()) changed.add("onRedisError");
        return List.copyOf(changed);
    }

    /** Deletes every policy, group, global rules, the audit list and the seeded marker. Intentional local reset only. */
    public int reset(String actor) {
        try {
            Long removed = redis.execute(RESET, List.of(INDEX, GROUP_INDEX, AUDIT, SEEDED, META,
                    PROJECTIONS, EPOCH));
            int count = removed == null ? 0 : removed.intValue();
            log.warn("policy store reset by {}: {} policy documents removed", actor, count);
            return count;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable during policy store reset", e);
        }
    }

    private static String docKey(String id) {
        return DOC_PREFIX + id;
    }

    private static String groupKey(String id) {
        return GROUP_PREFIX + id;
    }

    // --- group and global-rules persistence ---

    public long getEpoch() {
        try {
            Object val = redis.opsForValue().get(EPOCH);
            return val == null ? 0 : Long.parseLong(val.toString());
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading epoch", e);
        }
    }

    public Optional<PolicyGroup> findGroup(String id) {
        try {
            var key = groupKey(id);
            Object json = redis.opsForHash().get(key, "doc");
            if (json == null) {
                return Optional.empty();
            }
            PolicyGroup group = mapper.readValue(json.toString(), PolicyGroup.class);
            Object ids = redis.opsForHash().get(key, "projectionIds");
            // The Lua writer encodes an empty list as {} (cjson cannot tell it from an empty object); a group
            // with no endpoints therefore has no projection ids, and must still be readable.
            if ((group.projectionIds() == null || group.projectionIds().isEmpty()) && ids != null
                    && ids.toString().trim().startsWith("[")) {
                group = withProjectionIds(group, mapper.readValue(ids.toString(),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            }
            return Optional.of(group);
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading group " + id, e);
        } catch (Exception e) {
            throw new PolicyStoreException("stored group " + id + " is not readable JSON", e);
        }
    }

    public List<PolicyGroup> findAllGroups() {
        try {
            var ids = redis.opsForSet().members(GROUP_INDEX);
            var out = new ArrayList<PolicyGroup>();
            if (ids == null) {
                return out;
            }
            for (var id : ids) {
                Object json = redis.opsForHash().get(groupKey(id.toString()), "doc");
                if (json != null) {
                    try {
                        out.add(mapper.readValue(json.toString(), PolicyGroup.class));
                    } catch (Exception e) {
                        log.warn("skipping unreadable group doc: {}", e.getMessage());
                    }
                }
            }
            out.sort((a, b) -> a.id().compareTo(b.id()));
            return out;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable listing groups", e);
        }
    }

    public PolicyGroup saveGroup(PolicyGroup group, PolicyGroup existing, String actor,
            List<PolicyDocument> projections, long expectedEpoch) {
        for (var proj : projections) {
            proj.validate();
        }
        int mode = existing == null ? MODE_CREATE : MODE_UPDATE;
        String groupJson;
        String projJson;
        PolicyGroup persisted = withProjectionIds(group, projections.stream().map(PolicyDocument::id).toList());
        try {
            groupJson = mapper.writeValueAsString(persisted);
            List<java.util.Map<String, String>> projList = new ArrayList<>();
            for (var proj : projections) {
                projList.add(java.util.Map.of("id", proj.id(), "json", mapper.writeValueAsString(proj)));
            }
            projJson = mapper.writeValueAsString(projList);
        } catch (Exception e) {
            throw new PolicyStoreException("group could not be serialised", e);
        }

        List<Long> result;
        try {
            result = redis.execute(GROUP_WRITE, List.of(groupKey(group.id()), GROUP_INDEX, EPOCH, PROJECTIONS, INDEX),
                    String.valueOf(mode), group.id(), groupJson, String.valueOf(group.version()),
                    String.valueOf(expectedEpoch), group.updatedAt().toString(),
                    group.createdAt().toString(), group.updatedBy(), projJson,
                    String.valueOf(projections.size()));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable writing group " + group.id(), e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the group write", null);
        }
        int code = result.get(0).intValue();
        if (code == MISSING) {
            throw new PolicyNotFoundException(group.id());
        }
        if (code == CONFLICT) {
            throw new PolicyConflictException(result.get(1), group.version());
        }
        if (code == -3) {
            throw new PolicyConflictException(result.get(1), group.version());
        }

        record(actor, group.id(), existing == null ? "CREATE_GROUP" : "UPDATE_GROUP", group.version(),
                List.of("group"));
        return persisted;
    }

    public void deleteGroup(String id, String actor, long expectedEpoch) {
        var existing = findGroup(id).orElseThrow(() -> new PolicyNotFoundException(id));
        List<Long> result;
        try {
            result = redis.execute(GROUP_DELETE, List.of(groupKey(id), GROUP_INDEX, EPOCH, PROJECTIONS, INDEX),
                    id, String.valueOf(expectedEpoch));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable deleting group " + id, e);
        } catch (Exception e) {
            throw new PolicyStoreException("group projection ids could not be serialised", e);
        }
        if (result == null || result.isEmpty()) {
            throw new PolicyStoreUnavailableException("redis returned no result for the group delete", null);
        }
        if (result.get(0).intValue() == MISSING) {
            throw new PolicyNotFoundException(id);
        }
        if (result.get(0).intValue() == -3) {
            throw new PolicyConflictException(0, 0);
        }
        record(actor, id, "DELETE_GROUP", existing.version(), List.of("group"));
    }

    public Optional<GlobalScopeRules> findGlobalRules() {
        try {
            Object json = redis.opsForValue().get(NS + ":global-rules");
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json.toString(), GlobalScopeRules.class));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable reading global rules", e);
        } catch (Exception e) {
            throw new PolicyStoreException("stored global rules are not readable JSON", e);
        }
    }

    public GlobalScopeRules saveGlobalRules(GlobalScopeRules rules, GlobalScopeRules existing, String actor,
            List<PolicyDocument> projections, long expectedEpoch) {
        int mode = existing == null ? MODE_CREATE : MODE_UPDATE;
        String rulesJson;
        String projJson;
        try {
            rulesJson = mapper.writeValueAsString(rules);
            List<java.util.Map<String, String>> projList = new ArrayList<>();
            for (var proj : projections) {
                projList.add(java.util.Map.of("id", proj.id(), "json", mapper.writeValueAsString(proj)));
            }
            projJson = mapper.writeValueAsString(projList);
        } catch (Exception e) {
            throw new PolicyStoreException("global rules could not be serialised", e);
        }

        List<Long> result;
        try {
            result = redis.execute(GLOBAL_WRITE, List.of(NS + ":global-rules", EPOCH, PROJECTIONS, INDEX),
                    String.valueOf(mode), rulesJson, String.valueOf(rules.version()),
                    String.valueOf(expectedEpoch), rules.updatedAt().toString(),
                    rules.createdAt().toString(), rules.updatedBy(), projJson,
                    String.valueOf(projections.size()));
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable writing global rules", e);
        }
        if (result == null || result.size() < 2) {
            throw new PolicyStoreUnavailableException("redis returned no result for the global rules write", null);
        }
        int code = result.get(0).intValue();
        if (code == MISSING) {
            throw new PolicyNotFoundException("global-rules");
        }
        if (code == CONFLICT) {
            throw new PolicyConflictException(result.get(1), rules.version());
        }
        if (code == -3) {
            throw new PolicyConflictException(result.get(1), rules.version());
        }

        record(actor, "global-rules", existing == null ? "CREATE_GLOBAL" : "UPDATE_GLOBAL", rules.version(),
                List.of("global-rules"));
        return rules;
    }

    public RepairCounts repairProjections(String groupId, String actor) {
        var groupOpt = findGroup(groupId);
        if (groupOpt.isEmpty()) {
            throw new PolicyNotFoundException(groupId);
        }
        var group = groupOpt.get();
        var projectionService = new com.example.ratelimit.policy.ProjectionService();
        var projections = projectionService.projectGroup(group);
        var ids = projections.stream().map(PolicyDocument::id).toList();
        var updatedGroup = withProjectionIds(group, ids);

        try {
            var projList = new ArrayList<java.util.Map<String, String>>();
            for (var proj : projections) {
                projList.add(java.util.Map.of("id", proj.id(), "json", mapper.writeValueAsString(proj),
                        "version", String.valueOf(proj.version())));
            }
            List<Long> result = redis.execute(REPAIR_PROJECTIONS,
                    List.of(groupKey(groupId), EPOCH, PROJECTIONS, INDEX),
                    String.valueOf(group.version()), String.valueOf(getEpoch()),
                    mapper.writeValueAsString(updatedGroup), mapper.writeValueAsString(ids),
                    mapper.writeValueAsString(projList));
            if (result == null || result.size() < 3) {
                throw new PolicyStoreUnavailableException("redis returned no result repairing projections", null);
            }
            if (result.get(0).intValue() == MISSING) throw new PolicyNotFoundException(groupId);
            if (result.get(0).intValue() == CONFLICT || result.get(0).intValue() == -3) {
                throw new PolicyConflictException(result.get(1), group.version());
            }
            var counts = new RepairCounts(result.get(1).intValue(), result.get(2).intValue());
            record(actor, groupId, "REPAIR", group.version(), List.of("projections"));
            return counts;
        } catch (DataAccessException e) {
            throw new PolicyStoreUnavailableException("redis unavailable repairing projections", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new PolicyStoreException("projection serialisation failed", e);
        }
    }

    public record RepairCounts(int written, int deleted) { }

    private static PolicyGroup withProjectionIds(PolicyGroup group, List<String> projectionIds) {
        return new PolicyGroup(group.id(), group.name(), group.enabled(), group.endpoints(),
                group.onRedisError(), group.version(), group.createdAt(), group.updatedAt(),
                group.updatedBy(), projectionIds);
    }

    /** Redis unreachable while reading or writing policy state. Maps to 503. */
    public static class PolicyStoreUnavailableException extends RuntimeException {
        public PolicyStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** No policy with that id. Maps to 404. */
    public static class PolicyNotFoundException extends RuntimeException {
        public PolicyNotFoundException(String id) {
            super("no policy with id '" + id + "'");
        }
    }

    /** Generic store failure, e.g. unreadable stored JSON. Maps to 500. */
    public static class PolicyStoreException extends RuntimeException {
        public PolicyStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
