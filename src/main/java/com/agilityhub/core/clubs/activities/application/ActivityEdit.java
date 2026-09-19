package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.Activity;
import com.agilityhub.core.clubs.activities.persistence.Activity.*;
import com.agilityhub.core.clubs.activities.domain.*;
import java.time.*;
import java.util.List;

/** Mutable copy confined to one transaction. */
final class ActivityEdit {
    String id;
    String clubId;
    com.agilityhub.core.shared.domain.LocalizedText title;
    ActivityType type;
    com.agilityhub.core.shared.domain.LocalizedText typeLabel;
    com.agilityhub.core.shared.domain.LocalizedText shortDescription;
    com.agilityhub.core.shared.domain.LocalizedText longDescription;
    Image image;
    List<ActivityDocument> documents;
    Location location;
    List<String> ringIds;
    LocalDate date;
    String startTime;
    String endTime;
    Instant startsAt;
    Instant endsAt;
    Activity.RingBlockWindow ringBlockWindow;
    LocalDate registrationFrom;
    LocalDate registrationTo;
    Instant registrationOpensAt;
    Instant registrationClosesAt;
    Integer minPlaces;
    Integer maxPlaces;
    List<String> levelIds;
    boolean waitlistEnabled;
    String visibility;
    List<Void> priceTiers;
    String slug;
    ActivityState state;
    Instant publishedAt;
    String publishedByAccountId;
    Instant finishedAt;
    Cancellation cancellation;
    Counters counters;
    String internalNotes;
    List<String> ringBlockIds;
    Long version;
    Instant createdAt;
    String createdByAccountId;
    Instant updatedAt;
    String updatedByAccountId;
    ActivityEdit() { }
    ActivityEdit(Activity a) {
        id=a.id();
        clubId=a.clubId();
        title=a.title();
        type=a.type();
        typeLabel=a.typeLabel();
        shortDescription=a.shortDescription();
        longDescription=a.longDescription();
        image=a.image();
        documents=a.documents();
        location=a.location();
        ringIds=a.ringIds();
        date=a.date();
        startTime=a.startTime();
        endTime=a.endTime();
        startsAt=a.startsAt();
        endsAt=a.endsAt();
        ringBlockWindow=a.ringBlockWindow();
        registrationFrom=a.registrationFrom();
        registrationTo=a.registrationTo();
        registrationOpensAt=a.registrationOpensAt();
        registrationClosesAt=a.registrationClosesAt();
        minPlaces=a.minPlaces();
        maxPlaces=a.maxPlaces();
        levelIds=a.levelIds();
        waitlistEnabled=a.waitlistEnabled();
        visibility=a.visibility();
        priceTiers=a.priceTiers();
        slug=a.slug();
        state=a.state();
        publishedAt=a.publishedAt();
        publishedByAccountId=a.publishedByAccountId();
        finishedAt=a.finishedAt();
        cancellation=a.cancellation();
        counters=a.counters();
        internalNotes=a.internalNotes();
        ringBlockIds=a.ringBlockIds();
        version=a.version();
        createdAt=a.createdAt();
        createdByAccountId=a.createdByAccountId();
        updatedAt=a.updatedAt();
        updatedByAccountId=a.updatedByAccountId();
    }
    Activity snapshot() { return new Activity(id, clubId, title, type, typeLabel, shortDescription, longDescription, image, documents, location, ringIds, date, startTime, endTime, startsAt, endsAt, ringBlockWindow, registrationFrom, registrationTo, registrationOpensAt, registrationClosesAt, minPlaces, maxPlaces, levelIds, waitlistEnabled, visibility, priceTiers, slug, state, publishedAt, publishedByAccountId, finishedAt, cancellation, counters, internalNotes, ringBlockIds, version, createdAt, createdByAccountId, updatedAt, updatedByAccountId); }
    ActivityRules.Input input() { return new ActivityRules.Input(title,type,location.atClub(),location.name(),ringIds,date,startTime,endTime,registrationFrom,registrationTo,minPlaces,maxPlaces,levelIds); }
}
