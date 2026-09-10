package com.agilityhub.core.clubs.signup.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;

/** A pure projection of Member.status, signup resolution and the presence of PENDING dogs.
 * DRAFT exists only in the client. Accounts, numbers, audit and events remain application effects.
 */
public record SignupState(MemberStatus memberStatus, Source source, Resolution resolution, boolean readmission) {
    public enum MemberStatus { DRAFT, PENDING, ACTIVE, LEFT }
    public enum Source { PUBLIC, APP_ADD_DOG }
    public enum Resolution { NONE, PENDING, VALIDATED, REJECTED }
    public enum Command { SUBMIT, EDIT, REMIND, VALIDATE, REJECT, ADD_DOG }
    public enum DogStatus { PENDING, ACTIVE, INACTIVE }
    public record Transition(SignupState state, DogStatus dogStatus, boolean provisionMembership) { }
    public Transition apply(Command command) {
        if (command == Command.SUBMIT && (memberStatus == MemberStatus.DRAFT || memberStatus == MemberStatus.LEFT)) {
            return new Transition(new SignupState(MemberStatus.PENDING, Source.PUBLIC, Resolution.PENDING,
                    memberStatus == MemberStatus.LEFT), DogStatus.PENDING, false);
        }
        if (command == Command.ADD_DOG && memberStatus == MemberStatus.ACTIVE) {
            return new Transition(new SignupState(MemberStatus.ACTIVE, Source.APP_ADD_DOG, Resolution.PENDING, readmission),
                    DogStatus.PENDING, false);
        }
        boolean publicPending = memberStatus == MemberStatus.PENDING && source == Source.PUBLIC;
        boolean dogPending = memberStatus == MemberStatus.ACTIVE && source == Source.APP_ADD_DOG;
        if (resolution != Resolution.PENDING || !(publicPending || dogPending)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        return switch (command) {
            case EDIT, REMIND -> new Transition(this, DogStatus.PENDING, false);
            case VALIDATE -> new Transition(new SignupState(MemberStatus.ACTIVE, source, Resolution.VALIDATED, readmission),
                    DogStatus.ACTIVE, publicPending);
            case REJECT -> new Transition(new SignupState(publicPending ? MemberStatus.LEFT : MemberStatus.ACTIVE,
                    source, Resolution.REJECTED, readmission), DogStatus.INACTIVE, false);
            default -> throw new ApiException(ErrorCode.INVALID_STATE);
        };
    }
}
