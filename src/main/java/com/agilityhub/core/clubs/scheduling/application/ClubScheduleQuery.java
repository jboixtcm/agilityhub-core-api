package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.platform.application.ClubScheduleAccess;
import org.springframework.stereotype.Service;

@Service
public class ClubScheduleQuery implements ClubScheduleAccess {
    private final ClassSessionRepository sessions;
    public ClubScheduleQuery(ClassSessionRepository sessions) { this.sessions = sessions; }
    @Override public boolean hasClasses() { return sessions.hasClasses(); }
}
