package com.agilityhub.core.clubs.scheduling.application.ports;
import java.util.*;
public interface ActivityTitlePort {
    Map<String, String> titles(Collection<String> activityIds, Locale locale);
}
