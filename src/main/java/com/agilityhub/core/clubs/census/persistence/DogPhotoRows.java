package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * WEB-05: `Dog.photoThumbnailFileKey`, the photo's rendition, in the current club. A technical field beside the photo: written by
 * collection name (no version bump, like {@link CensusRepository#setField}), and only while the dog still shows the photo it
 * belongs to, so a rendition never outlives a replaced photo.
 */
@Repository
public class DogPhotoRows {
    static final String DOGS = "dogs", FIELD = "photoThumbnailFileKey";
    private final MongoTemplate mongo;
    public DogPhotoRows(MongoTemplate mongo) { this.mongo = mongo; }

    private static Criteria dog(String dogId) { return Criteria.where("clubId").is(TenantContext.require()).and("_id").is(dogId); }
    /** The dog not erased, showing {@code photoKey}, without a rendition. */
    private static Query bare(String dogId, String photoKey) {
        return Query.query(dog(dogId).and("photoFileKey").is(photoKey).and("erasedAt").is(null).and(FIELD).is(null));
    }

    /** Takes the dog's rendition away and returns its key (null without one), in one write: two photo changes never both read it. */
    public String takeThumbnail(String dogId) {
        var before = mongo.findAndModify(Query.query(dog(dogId).and(FIELD).exists(true)), new Update().unset(FIELD),
                FindAndModifyOptions.options().returnNew(false), Document.class, DOGS);
        return before == null ? null : before.getString(FIELD);
    }
    /** True when the dog, not erased, still shows {@code photoKey} and has no rendition. */
    public boolean lacksThumbnail(String dogId, String photoKey) { return mongo.exists(bare(dogId, photoKey), DOGS); }
    /** Binds the rendition under the same conditions in one conditional write; false when the photo changed, the dog was erased or another one was bound. */
    public boolean bindThumbnail(String dogId, String photoKey, String thumbnailKey) {
        return mongo.updateFirst(bare(dogId, photoKey), new Update().set(FIELD, thumbnailKey), DOGS).getModifiedCount() == 1;
    }
    /** `census:photo-thumbnails`' work: the club's dogs, not erased, with a photo and no rendition, by id (`_id` and `photoFileKey`). */
    public List<Document> missingThumbnails() {
        var query = Query.query(Criteria.where("clubId").is(TenantContext.require()).and("photoFileKey").nin(Arrays.asList(null, ""))
                .and("erasedAt").is(null).and(FIELD).is(null)).with(Sort.by("_id"));
        query.fields().include("_id").include("photoFileKey");
        return mongo.find(query, Document.class, DOGS);
    }
}
