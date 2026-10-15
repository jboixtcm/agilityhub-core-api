package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseEvent;
import com.agilityhub.core.courses.domain.CourseParts.InventoryItem;
import com.agilityhub.core.courses.domain.CourseTypes.InventoryScope;
import com.agilityhub.core.courses.persistence.ObstacleInventory;
import com.agilityhub.core.courses.persistence.ObstacleInventoryRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * R-16-12: the club's obstacle inventory and a ring's own one (`scope = RING`, the assumption E96 keeps). A ring's effective
 * inventory is its own when it has one, otherwise the club's (§3), and that is what `GET ring:{ringId}` answers, its `scope`
 * telling which one it is; with neither, `404 NOT_FOUND` (no inventory, no inventory warning). A save needs every `count ≥ 0` and
 * one line per obstacle type (`422 INVENTORY_INVALID`) and publishes `InventoryChanged{scope, ringId}`.
 */
@Service
public class ObstacleInventoryService {
    private final ObstacleInventoryRepository inventories;
    private final CourseContractGuards guards;
    private final EventPublisher events;
    private final Clock clock;
    public ObstacleInventoryService(ObstacleInventoryRepository inventories, CourseContractGuards guards, EventPublisher events, Clock clock) {
        this.inventories = inventories; this.guards = guards; this.events = events; this.clock = clock;
    }

    public ObstacleInventory get(String scope) {
        String ringId = guards.inventory(scope);
        var own = ringId == null ? Optional.<ObstacleInventory>empty() : inventories.find(InventoryScope.RING, ringId);
        return own.or(() -> inventories.find(InventoryScope.CLUB, null)).orElseThrow(() -> new ApiException(NOT_FOUND));
    }

    @Transactional
    public ObstacleInventory put(String scope, List<InventoryItem> items) {
        String ringId = guards.inventory(scope);
        var types = new HashSet<Object>();
        if (items.stream().anyMatch(item -> item.count() < 0 || !types.add(item.type()))) { throw new ApiException(INVENTORY_INVALID); }
        var target = ringId == null ? InventoryScope.CLUB : InventoryScope.RING;
        var now = clock.instant(); String actor = CourseAccess.accountId(); String clubId = TenantContext.require();
        var saved = inventories.upsert(new ObstacleInventory(null, clubId, target, ringId, List.copyOf(items), now, now, actor, actor));
        var payload = new LinkedHashMap<String, Object>(); payload.put("scope", target.name()); payload.put("ringId", ringId);
        events.publish(new CourseEvent(CourseEvent.Kind.InventoryChanged, clubId, saved.id(), now, payload, actor, null, DomainEvent.Origin.BACKOFFICE));
        return saved;
    }
}
