package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.CountryProfile;
import com.agilityhub.core.platform.domain.GenericCountryProfile;
import com.agilityhub.core.platform.domain.SpanishCountryProfile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CountryProfileRegistry {
    private final Map<String, CountryProfile> profiles;
    public CountryProfileRegistry() {
        Map<String, List<CountryProfile.Town>> towns = new LinkedHashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(new ClassPathResource("country/es/postal-codes.csv")
                .getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String line; (line = reader.readLine()) != null;) {
                // Dataset is generated as RFC 4180 CSV; quoted town names can contain commas.
                List<String> fields = csv(line);
                String name = fields.get(1).replace(" De ", " de ").replace(" Del ", " del ");
                towns.computeIfAbsent(fields.get(0), ignored -> new ArrayList<>()).add(new CountryProfile.Town(name, fields.get(2)));
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        towns.replaceAll((code, values) -> List.copyOf(values));
        profiles = Map.of("ES", new SpanishCountryProfile(towns), "GENERIC", new GenericCountryProfile());
    }
    public CountryProfile get(String code) { return profiles.getOrDefault(code, profiles.get("GENERIC")); }
    static List<String> csv(String line) {
        List<String> fields = new ArrayList<>(); StringBuilder field = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else { quoted = !quoted; }
            } else if (ch == ',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else { field.append(ch); }
        }
        fields.add(field.toString()); return fields;
    }
}
