package com.agilityhub.core.courses.domain;

/** S16 core spellings; the planner JSON retains its own wire values. */
public final class CourseTypes {
    private CourseTypes() { }
    public enum OwnerType { CLUB, AGILITYHUB, ACCOUNT }
    public enum Visibility { PRIVATE, CLUB, PUBLIC }
    public enum Discipline { AGILITY, JUMPING, OTHER }
    public enum CourseSource { SMARTER, EDITOR, IMAGE, AGILITYHUB_COPY }
    public enum SizeCategory { GRAND, GARDEN }
    public enum UploadPurpose { SMARTER_SOURCE, COURSE_IMAGE, THUMBNAIL, BUILD_SHEET }
    public enum Surface { GRASS, SAND, ARTIFICIAL, INDOOR, OTHER }
    public enum DoorSide { NORTH, SOUTH, EAST, WEST }
    public enum DoorFlow { IN, OUT, BOTH }
    public enum CalibrationOutcome { GREEN, YELLOW, RED, ABORTED }
    public enum PlacementMode { PRESERVE_METERS, CENTERED, FIT_TO_RING }
    public enum PlacementStatus { DRAFT, READY_TO_BUILD, ARCHIVED }
    public enum PlacementUse { EVENT, TRAINING, FREE_FLOATING }
    public enum DogSize { XS, S, M, I, L, ALL }
    public enum SetupKind { AGILITY, JUMPING, FUN, OBSTACLE_DRILL, EMPTY }
    public enum SetupStatus { ACTIVE, EXPIRED, DISMANTLED }
    public enum BuildStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, ABANDONED }
    public enum BuildStrategy { EQUIPMENT_TYPE, CLOSER_OBSTACLE_ORDER, FREE_BUILD }
    public enum ObstacleStatus { NOT_PLACED, CURRENT, PLACED, SKIPPED, NEEDS_CHECK }
    public enum JoinedVia { QR, CODE }
    public enum InventoryScope { CLUB, RING }
    public enum IndoorOutdoor { INDOOR, OUTDOOR, MIXED }
    public enum VenueVisibility { PRIVATE_LINK, MEMBERS_ONLY, PUBLIC_PREVIEW }
    public enum PartnerStatus { INACTIVE, TRIAL, ACTIVE, EXPIRED }
    public enum PaperSize { A4, LETTER }
    public enum ChallengeStatus { DRAFT, PUBLISHED, CLOSED }
    public enum AttemptStatus { SUBMITTED, VALIDATED, REJECTED }
}
