package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;

/** Partial compare-and-set writes preserve fields owned by future census verticals. */
public class CensusRepository<T extends CensusEntity> extends TenantRepository<T> {
    private final Class<T> type;
    private final Clock clock;
    public CensusRepository(MongoTemplate mongo, Class<T> type, Clock clock) {
        super(mongo, type); this.type = type; this.clock = clock;
    }
    public T require(String id) { return findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public List<T> matching(Criteria criteria) { return mongo.find(tenantQuery().addCriteria(criteria), type); }
    /** Case- and accent-insensitive comparison (primary strength), the collation of {@link #ensureNameIndex}. */
    public static final Collation NAME_COLLATION = Collation.of("en").strength(Collation.ComparisonLevel.primary());
    /** {@link #matching} compared with {@link #NAME_COLLATION}, so the `{clubId, nameKey}` index of that collation serves it. */
    public List<T> matchingIgnoringCase(Criteria criteria) { return mongo.find(tenantQuery().addCriteria(criteria).collation(NAME_COLLATION), type); }
    /** R-04-12 (E3-T09 round 2): the family lookup's `{clubId, nameKey}` index; it replaces the first round's `name_ci` on `name`. */
    public void ensureNameIndex() {
        var indexes = mongo.indexOps(type);
        if (indexes.getIndexInfo().stream().anyMatch(index -> "name_ci".equals(index.getName()))) { indexes.dropIndex("name_ci"); }
        indexes.ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("nameKey", Sort.Direction.ASC).collation(NAME_COLLATION).named("dog_name_key"));
    }
    /**
     * R-04-12 (E3-T09 round 2): the idempotent migration of `Dog.nameKey` for the dogs of every club stored before the key
     * existed. A dog that has its key is not touched (every census write keeps it current), so a second run changes nothing.
     * Like {@link #setField}, a derived technical field: no version bump.
     */
    public long backfillNameKeys() {
        String collection = mongo.getCollectionName(type); long updated = 0;
        var missing = Query.query(Criteria.where("name").type(org.springframework.data.mongodb.core.schema.JsonSchemaObject.Type.STRING).and("nameKey").exists(false));
        missing.fields().include("name");
        for (var dog : mongo.find(missing, Document.class, collection)) {
            updated += mongo.updateFirst(Query.query(Criteria.where("_id").is(dog.get("_id")).and("nameKey").exists(false)),
                    new Update().set("nameKey", Dog.nameKey(dog.getString("name"))), collection).getModifiedCount();
        }
        return updated;
    }
    public void ensureIndexes(String field, String bsonType, String name) {
        mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on(field, Sort.Direction.ASC)
                .unique().partial(PartialIndexFilter.of(new Document(field, new Document("$type", bsonType)))).named(name));
    }
    public void ensureLookup(String field) { mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on(field, Sort.Direction.ASC)); }
    @Override public T insert(T item) {
        item.beforeWrite(); item.version = 0L; item.createdAt = clock.instant(); item.updatedAt = item.createdAt;
        try { return super.insert(item); } catch (DuplicateKeyException duplicate) { throw duplicate(duplicate); }
    }
    /**
     * Compare-and-set save of the census-owned fields. {@link ForeignOwned} fields are never written here; a save whose
     * foreign field differs from the value it was read with (or, for an entity not read from Mongo, from the stored
     * value) fails fast, so a writer that forgot {@link #setField} cannot lose its write silently (E5-T11). A foreign
     * field changed in Mongo by {@link #setField} after the read is not a difference: the save just keeps the new value.
     */
    public T save(T item) {
        requireForeignUnchanged(item); item.beforeWrite();
        long expected = item.version();
        var query = tenantQuery(item.clubId).addCriteria(Criteria.where("_id").is(item.id));
        query.addCriteria(new Criteria().orOperator(Criteria.where("version").is(expected),
                Criteria.where("version").is(expected == 0 ? null : expected)));
        item.version = expected + 1; item.updatedAt = clock.instant();
        Document data = new Document(); mongo.getConverter().write(item, data);
        Update update = new Update();
        var foreign = new HashSet<String>();
        for (var field : foreignFields(type)) { foreign.add(field.getName()); }
        data.forEach((key, value) -> { if (!Set.of("_id", "_class", "clubId").contains(key) && !foreign.contains(key)) { update.set(key, value); } });
        for (var field : type.getDeclaredFields()) {
            if (!data.containsKey(field.getName()) && !foreign.contains(field.getName())) { update.unset(field.getName()); }
        }
        try {
            if (mongo.updateFirst(query, update, mongo.getCollectionName(type)).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
        } catch (DuplicateKeyException duplicate) { throw duplicate(duplicate); }
        return item;
    }
    private void requireForeignUnchanged(T item) {
        var fields = foreignFields(type);
        if (fields.isEmpty()) { return; }
        var baseline = item.loadedForeign;
        if (baseline == null) {
            var stored = mongo.findOne(tenantQuery(item.clubId).addCriteria(Criteria.where("_id").is(item.id)), type);
            baseline = stored == null ? foreignValues(item) : foreignValues(stored);
        }
        for (var field : fields) {
            if (!Objects.equals(read(field, item), baseline.get(field.getName()))) {
                throw new IllegalStateException(type.getSimpleName() + "." + field.getName() + " is @ForeignOwned: a census save never writes it; use CensusRepository.setField");
            }
        }
    }
    static List<java.lang.reflect.Field> foreignFields(Class<?> type) {
        var fields = new ArrayList<java.lang.reflect.Field>();
        for (var field : type.getDeclaredFields()) { if (field.isAnnotationPresent(ForeignOwned.class)) { fields.add(field); } }
        return fields;
    }
    static Map<String, Object> foreignValues(CensusEntity entity) {
        var values = new HashMap<String, Object>();
        for (var field : foreignFields(entity.getClass())) { values.put(field.getName(), read(field, entity)); }
        return values;
    }
    private static Object read(java.lang.reflect.Field field, Object entity) {
        try { return field.get(entity); } catch (IllegalAccessException inaccessible) { throw new IllegalStateException(inaccessible); }
    }
    private RuntimeException duplicate(DuplicateKeyException failure) {
        String message = failure.getMessage();
        if (message != null && message.contains("member_id_document")) { return new ApiException(ErrorCode.ID_DOCUMENT_ALREADY_EXISTS); }
        if (message != null && message.contains("dog_chip")) { return new ApiException(ErrorCode.CHIP_ALREADY_EXISTS); }
        return failure;
    }
    /**
     * Single-field write of a {@link ForeignOwned} field (S08 `Member.lastDogForClass`, S09 `Member.lastDogForTraining`):
     * no version bump and no `updatedAt`, so an admin editing the member never gets STALE_VERSION because the member
     * booked meanwhile (census saves skip the field). An unchanged value writes nothing, so two bookings of the same
     * booker do not conflict on the member document. The update goes to the collection by name: the entity-class
     * overload of `updateFirst` would `$inc` the `@Version` field by itself (E5-T08).
     */
    public void setField(String id, String field, Object value) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and(field).ne(value)), new Update().set(field, value), mongo.getCollectionName(type));
    }
    /**
     * Technical `$inc` owned by another vertical (S09 `Dog.trainingSeq` / `Member.trainingSeq`): no version bump (collection
     * by name, as {@link #setField}), and the field is not declared on the entity, so a census form save never rewrites it.
     */
    public void increment(String id, String field) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().inc(field, 1), mongo.getCollectionName(type));
    }
    public void lock() {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require() + ":census")), new Update().inc("sequence", 1), "census_write_locks");
    }
    public void documentIndex() {
        mongo.indexOps(type).ensureIndex(new Index().on("clubId", Sort.Direction.ASC).on("dogId", Sort.Direction.ASC)
                .on("type", Sort.Direction.ASC).unique().named("dog_document_type"));
    }
}
