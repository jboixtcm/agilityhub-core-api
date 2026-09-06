package com.agilityhub.core.platform.domain;

import java.util.List;

public record Pwa(String name, String shortName, List<Icon> icons) {
    public Pwa { icons = List.copyOf(icons); }
    public record Icon(String src, String sizes, String type, String purpose) { }
}
