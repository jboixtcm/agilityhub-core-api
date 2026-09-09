package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionPresenceRepository;
import com.agilityhub.core.platform.application.ClubScheduleAccess;
import org.springframework.stereotype.Service;

@Service
public class ClubScheduleQuery implements ClubScheduleAccess {
    private final ClassSessionPresenceRepository sessions;
    public ClubScheduleQuery(ClassSessionPresenceRepository sessions) { this.sessions = sessions; }
    @Override public boolean hasClasses() { return sessions.hasClasses(); }
}
