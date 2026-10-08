package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/** S16 §6 query contract shared by the club library and the platform library. */
public final class CourseListContract {
    private CourseListContract() { }
    public static final ListDefinition COURSES = new ListDefinition("courses",
            Map.of("owner", new Field("ownerType", Type.TEXT), "discipline", new Field("discipline", Type.TEXT),
                    "agilityhubLevel", new Field("agilityhubLevel", Type.TEXT), "levelId", new Field("levelIds", Type.TEXT),
                    "source", new Field("source", Type.TEXT), "tag", new Field("tags", Type.TEXT), "designerName", new Field("designerName", Type.TEXT),
                    "obstacleCount", new Field("stats.obstacleCount", Type.NUMBER), "updatedAt", new Field("updatedAt", Type.INSTANT)),
            Map.of("name", "name", "updatedAt", "updatedAt"), List.of("name", "designerName"),
            List.of("name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings"),
            List.of("name", "discipline", "level", "designerName", "source"), List.of("updatedAt,desc"),
            Set.of("id", "name", "discipline", "level", "designerName", "source", "thumbnailUrl", "stats", "ownerType", "activeOnRings"));
    public static void validate(MultiValueMap<String, String> query) { ListQuery.parse(COURSES, query); }
}
