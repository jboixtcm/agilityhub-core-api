package com.agilityhub.core.clubs.census.persistence;

import java.util.*;
import org.springframework.data.mongodb.core.convert.*;

/** Reads legacy S03 consent objects and exposes the canonical append-only ledger through that projection. */
public class ConsentLedgerConverter implements MongoValueConverter<Map<String,Object>,Object> {
    @Override @SuppressWarnings("unchecked") public Map<String,Object> read(Object value, MongoConversionContext context) {
        if (value instanceof Map<?,?> map) { return new LinkedHashMap<>((Map<String,Object>)map); }
        var result = new LinkedHashMap<String,Object>();
        if (value instanceof List<?> entries) {
            result.put("history",entries);
            for (Object entry : entries) {
                var row = (Map<String,Object>)entry;
                if ("PRIVACY_POLICY".equals(row.get("type"))) { result.put("privacyPolicy",row); }
                if ("IMAGE_USE".equals(row.get("type"))) {
                    var image = new LinkedHashMap<>(row); image.put("at",row.get("acceptedAt")); result.put("imageRights",image);
                }
            }
        }
        return result;
    }
    @Override public Object write(Map<String,Object> value, MongoConversionContext context) {
        return value.containsKey("history") ? value.get("history") : value;
    }
}
