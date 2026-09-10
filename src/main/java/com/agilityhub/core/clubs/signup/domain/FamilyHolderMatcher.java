package com.agilityhub.core.clubs.signup.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class FamilyHolderMatcher {
    private FamilyHolderMatcher() { }
    public enum MemberStatus { ACTIVE, PENDING, LEFT }
    public enum DogStatus { ACTIVE, PENDING, INACTIVE }
    public record Dog(String name, DogStatus status) { }
    public record Candidate(String clubId, String memberId, MemberStatus status, String firstName, String lastName1,
                            String lastName2, List<Dog> dogs) {
        public Candidate { dogs = List.copyOf(dogs); }
    }
    public enum Outcome { FOUND, NOT_FOUND }
    /** This internal match includes the id for persistence; only publicResult may be sent anonymously. */
    public record Match(String holderMemberId, String holderDisplayName) {
        public PublicResult publicResult() { return new PublicResult(Outcome.FOUND, holderDisplayName); }
    }
    public record PublicResult(Outcome result, String holderDisplayName) { }
    public static Optional<Match> match(String clubId, String holderName, String dogName, List<Candidate> candidates) {
        String holder = normalize(holderName); String dog = normalize(dogName);
        if (holder.isEmpty() || dog.isEmpty()) { return Optional.empty(); }
        var tokens = Arrays.stream(holder.split(" ")).distinct().toList();
        if (tokens.size() < 2) { return Optional.empty(); }
        var found = candidates.stream().filter(c -> c.clubId().equals(clubId) && c.status() != MemberStatus.LEFT)
                .filter(c -> Arrays.asList(normalize(c.firstName() + " " + c.lastName1() + " "
                        + (c.lastName2() == null ? "" : c.lastName2())).split(" ")).containsAll(tokens))
                .filter(c -> c.dogs().stream().anyMatch(d -> d.status() != DogStatus.INACTIVE && normalize(d.name()).equals(dog)))
                .toList();
        if (found.size() != 1) { return Optional.empty(); }
        var candidate = found.getFirst();
        return Optional.of(new Match(candidate.memberId(), candidate.firstName().strip() + " "
                + candidate.lastName1().strip().substring(0, 1).toUpperCase(Locale.ROOT) + "."));
    }
    public static PublicResult lookup(String clubId, String holderName, String dogName, List<Candidate> candidates) {
        return match(clubId, holderName, dogName, candidates).map(Match::publicResult)
                .orElseGet(() -> new PublicResult(Outcome.NOT_FOUND, null));
    }
    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }
}
