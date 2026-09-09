package com.agilityhub.core.identity.application;

import java.util.ArrayList;
import java.util.List;

/** Strict quoted CSV reader; malformed input is rejected before any account is changed. */
final class LearnCsv {
    private LearnCsv() { }
    static List<List<String>> read(String text) {
        var rows = new ArrayList<List<String>>();
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int i = text.startsWith("\uFEFF") ? 1 : 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { field.append(ch); }
            } else if (ch == ',' || ch == '\n' || ch == '\r') {
                fields.add(field.toString()); field.setLength(0); closed = false;
                if (ch != ',') {
                    rows.add(List.copyOf(fields)); fields.clear();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                }
            } else if (ch == '"' && field.isEmpty() && !closed) { quoted = true; }
            else {
                if (closed || ch == '"') { throw new IllegalArgumentException("Invalid CSV quoting"); }
                field.append(ch);
            }
        }
        if (quoted) { throw new IllegalArgumentException("Unclosed CSV field"); }
        if (!fields.isEmpty() || !field.isEmpty() || closed) {
            fields.add(field.toString()); rows.add(List.copyOf(fields));
        }
        return List.copyOf(rows);
    }
}
